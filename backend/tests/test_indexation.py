"""
Monthly indexation: the arithmetic, the guards, and the endpoints that trigger it.

The rules being pinned here are the ones that would cost real money if they broke:
compounding must apply each month to a balance that already carries the months before it,
a month already written must never be written twice, and a settled or overpaid account
must not accrue anything at all.

The index runs at a clean 3% per month (see the `rates` fixture), so every expected figure
below can be checked by hand.
"""

from datetime import UTC, date, datetime

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app import models
from app.db import Base
from app.indexation import ensure_indexed, ensure_indexed_book, ensure_indexed_for_buyer
from app.ledger import balance_of

SELLER = "s1"
CUSTOMER = "c1"


@pytest.fixture
def db():
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)()

    session.add(
        models.Customer(
            customer_id=CUSTOMER,
            display_name="Test",
            phone="+905551112233",
            claim_status="UNCLAIMED",
            claimed_by_user_id=None,
            created_by_seller_id=SELLER,
            created_at=datetime(2026, 1, 1, tzinfo=UTC),
        )
    )
    # A clean 3% a month, so an expected total is 10000 * 1.03**n and can be read off.
    cpi = 100_000
    for month in range(1, 13):
        session.add(
            models.FxRate(
                as_of=date(2026, month, 1),
                usd_minor=3_200,
                eur_minor=3_500,
                gold_minor=400_000,
                cpi_index=cpi,
            )
        )
        cpi = round(cpi * 1.03)
    session.commit()

    yield session
    session.close()


def entry(db, tx_id: str, amount: int, tx_type: str, when: datetime) -> None:
    db.add(
        models.Transaction(
            transaction_id=tx_id,
            seller_id=SELLER,
            customer_id=CUSTOMER,
            amount_minor=amount,
            type=tx_type,
            description="test",
            basket_id=None,
            settled_via_pgw=False,
            receipt_no=None,
            created_at=when,
        )
    )
    db.commit()


def indexation_rows(db) -> list[models.Transaction]:
    return list(
        db.execute(
            select(models.Transaction)
            .where(models.Transaction.type == "INDEXATION")
            .order_by(models.Transaction.created_at)
        ).scalars().all()
    )


def test_indexation_compounds_month_over_month(db) -> None:
    # 100,00 TL owed from January, read in July: six elapsed months at 3%.
    entry(db, "t1", 10_000, "DEBT", datetime(2026, 1, 1, tzinfo=UTC))

    assert ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 7, 15)) == 6

    # Each month's adjustment is larger than the last -- that difference IS the compounding.
    # Simple interest would have written 300 six times.
    amounts = [row.amount_minor for row in indexation_rows(db)]
    assert amounts == sorted(amounts)
    assert amounts[0] == 300
    assert amounts[-1] > amounts[0]

    # 10000 * 1.03**6 = 11940.5..., and the month-by-month rounding lands on 11941.
    assert balance_of(db, SELLER, CUSTOMER) == 11_941


def test_a_month_is_never_indexed_twice(db) -> None:
    # The guard the lazy trigger rests on: every balance read calls this, so a second call
    # writing anything would inflate the debt a little more on each screen refresh.
    entry(db, "t1", 10_000, "DEBT", datetime(2026, 1, 1, tzinfo=UTC))

    first = ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 7, 15))
    balance_after_first = balance_of(db, SELLER, CUSTOMER)

    assert ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 7, 15)) == 0
    assert ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 7, 15)) == 0

    assert len(indexation_rows(db)) == first
    assert balance_of(db, SELLER, CUSTOMER) == balance_after_first


def test_row_ids_are_derived_from_the_month(db) -> None:
    # What makes the double-write impossible rather than merely unlikely: the id is a
    # function of the data, so a repeat insert collides with the primary key.
    entry(db, "t1", 10_000, "DEBT", datetime(2026, 1, 1, tzinfo=UTC))
    ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 4, 1))

    ids = [row.transaction_id for row in indexation_rows(db)]
    assert ids == [
        f"idx_{SELLER}_{CUSTOMER}_2026-02",
        f"idx_{SELLER}_{CUSTOMER}_2026-03",
        f"idx_{SELLER}_{CUSTOMER}_2026-04",
    ]


def test_rows_are_dated_to_the_month_they_belong_to(db) -> None:
    # Not to the day the balance happened to be read. A row stamped "today" would sort
    # after later purchases and make the history read as though the shop back-dated it.
    entry(db, "t1", 10_000, "DEBT", datetime(2026, 1, 1, tzinfo=UTC))
    ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 5, 20))

    assert [row.created_at.date() for row in indexation_rows(db)] == [
        date(2026, 2, 1),
        date(2026, 3, 1),
        date(2026, 4, 1),
        date(2026, 5, 1),
    ]


def test_a_payment_lowers_the_next_month_adjustment(db) -> None:
    # The behaviour that makes indexation fair: it follows the balance, so paying part of
    # a debt immediately reduces what the rest accrues.
    entry(db, "t1", 10_000, "DEBT", datetime(2026, 1, 1, tzinfo=UTC))
    entry(db, "t2", 5_000, "PAYMENT", datetime(2026, 3, 15, tzinfo=UTC))

    ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 6, 1))
    amounts = [row.amount_minor for row in indexation_rows(db)]

    # March was indexed before the payment landed; April sees the halved balance.
    assert amounts[2] < amounts[1]


def test_a_settled_account_accrues_nothing(db) -> None:
    entry(db, "t1", 10_000, "DEBT", datetime(2026, 1, 1, tzinfo=UTC))
    entry(db, "t2", 10_000, "PAYMENT", datetime(2026, 1, 20, tzinfo=UTC))

    assert ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 8, 1)) == 0


def test_a_customer_in_credit_is_not_charged(db) -> None:
    # A negative balance means the shop owes the customer. Indexing it would charge them
    # inflation on the shop's own debt, and amount_minor on a ledger row is always positive.
    entry(db, "t1", 5_000, "DEBT", datetime(2026, 1, 1, tzinfo=UTC))
    entry(db, "t2", 9_000, "PAYMENT", datetime(2026, 1, 10, tzinfo=UTC))

    assert ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 8, 1)) == 0


def test_the_current_month_is_not_indexed(db) -> None:
    # A month's inflation is not known until it has finished.
    entry(db, "t1", 10_000, "DEBT", datetime(2026, 5, 10, tzinfo=UTC))

    assert ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 5, 28)) == 0


def test_a_later_debt_is_picked_up(db) -> None:
    # Indexation reads the balance at each month start, so a debt added after indexing
    # began is carried from the next month on without anything being recomputed.
    entry(db, "t1", 10_000, "DEBT", datetime(2026, 1, 1, tzinfo=UTC))
    ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 4, 1))

    entry(db, "t9", 20_000, "DEBT", datetime(2026, 4, 10, tzinfo=UTC))
    ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 6, 1))

    amounts = [row.amount_minor for row in indexation_rows(db)]
    # April was already written against the old balance; May carries all three hundred lira.
    assert amounts[-1] > 900


def test_nothing_happens_without_rates(db) -> None:
    # The honest answer when the index is unknown. Assuming 1.0 would claim prices held
    # flat, which is a statement about the world we cannot make from missing data.
    db.execute(models.FxRate.__table__.delete())
    db.commit()

    entry(db, "t1", 10_000, "DEBT", datetime(2026, 1, 1, tzinfo=UTC))

    assert ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 7, 1)) == 0
    assert balance_of(db, SELLER, CUSTOMER) == 10_000


def test_an_empty_ledger_indexes_nothing(db) -> None:
    assert ensure_indexed(db, SELLER, "never-charged", today=date(2026, 7, 1)) == 0


def test_book_wide_indexing_covers_every_customer(db) -> None:
    # What the shop's main screen calls. Each customer's history is genuinely their own,
    # so the pass is per customer -- but the book is read once.
    db.add(
        models.Customer(
            customer_id="c2",
            display_name="Second",
            phone="+905552223344",
            claim_status="UNCLAIMED",
            claimed_by_user_id=None,
            created_by_seller_id=SELLER,
            created_at=datetime(2026, 1, 1, tzinfo=UTC),
        )
    )
    db.commit()

    entry(db, "t1", 10_000, "DEBT", datetime(2026, 1, 1, tzinfo=UTC))
    db.add(
        models.Transaction(
            transaction_id="t2",
            seller_id=SELLER,
            customer_id="c2",
            amount_minor=20_000,
            type="DEBT",
            description="test",
            basket_id=None,
            settled_via_pgw=False,
            receipt_no=None,
            created_at=datetime(2026, 1, 1, tzinfo=UTC),
        )
    )
    db.commit()

    written = ensure_indexed_book(db, SELLER, today=date(2026, 4, 1))

    assert written == 6  # three months each
    assert balance_of(db, SELLER, CUSTOMER) > 10_000
    assert balance_of(db, SELLER, "c2") > 20_000


def test_buyer_side_indexing_covers_every_shop(db) -> None:
    # A buyer holds one customer record per shop. /me/debts must bring all of them up to
    # date, or the total on the buyer's screen would disagree with the shops' own.
    db.add(
        models.Customer(
            customer_id="m1",
            display_name="Test",
            phone="+905551112233",
            claim_status="CLAIMED",
            claimed_by_user_id="u1",
            created_by_seller_id="s2",
            created_at=datetime(2026, 1, 1, tzinfo=UTC),
        )
    )
    db.commit()

    entry(db, "t1", 10_000, "DEBT", datetime(2026, 1, 1, tzinfo=UTC))
    db.add(
        models.Transaction(
            transaction_id="t2",
            seller_id="s2",
            customer_id="m1",
            amount_minor=30_000,
            type="DEBT",
            description="test",
            basket_id=None,
            settled_via_pgw=False,
            receipt_no=None,
            created_at=datetime(2026, 1, 1, tzinfo=UTC),
        )
    )
    db.commit()

    written = ensure_indexed_for_buyer(db, [CUSTOMER, "m1"], today=date(2026, 3, 1))

    assert written == 4  # two months in each of two books
    assert balance_of(db, "s2", "m1") > 30_000


def test_indexation_is_positive_and_typed(db) -> None:
    # The invariants the balance formula depends on: every row adds, and the sign lives in
    # the type rather than in the amount.
    entry(db, "t1", 10_000, "DEBT", datetime(2026, 1, 1, tzinfo=UTC))
    ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 6, 1))

    rows = indexation_rows(db)
    assert rows
    for row in rows:
        assert row.amount_minor > 0
        assert row.type == "INDEXATION"
        assert row.basket_id is None
        # The month and the rate, so a customer can see where the figure came from.
        assert "enflasyon farkı" in row.description


def test_indexation_rows_are_not_double_counted_by_the_sum(db) -> None:
    # The whole design rests on the balance being a plain SUM over three types. If
    # INDEXATION were counted as a PAYMENT -- which the Room DAOs' original
    # `ELSE -amountMinor` would have done -- the balance would fall as inflation rose.
    entry(db, "t1", 10_000, "DEBT", datetime(2026, 1, 1, tzinfo=UTC))
    ensure_indexed(db, SELLER, CUSTOMER, today=date(2026, 6, 1))

    total_indexation = db.execute(
        select(func.sum(models.Transaction.amount_minor)).where(
            models.Transaction.type == "INDEXATION"
        )
    ).scalar_one()

    assert balance_of(db, SELLER, CUSTOMER) == 10_000 + total_indexation
