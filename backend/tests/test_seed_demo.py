"""
The demo seed: enough data to plot, without disturbing the accounts people test with.

The rule that matters most here is the first one. Every device scenario in
docs/test-hesaplari.md is written against u_owner, u1 and u_market, and the demo runs on
the same database. If seeding demo data moved those balances, every scenario in that
document would quietly become wrong -- and it would be discovered on a phone, mid-demo,
rather than here.
"""

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app import models
from app.db import Base
from app.ledger import _BALANCE, balance_of
from app.seed_demo import already_seeded, seed_demo


@pytest.fixture
def db():
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)()
    seed_demo(session)
    yield session
    session.close()


# The same seven balances test_seed_balances.py pins, re-checked with demo data layered on
# top. That file proves the sums are right; this one proves the demo did not move them.
@pytest.mark.parametrize(
    "seller_id,customer_id,expected",
    [
        ("u_owner", "c1", 4000),
        ("u_owner", "c2", 16500),
        ("u_owner", "c3", 0),
        ("u_owner", "c4", 2550),
        ("u_owner", "c5", 21000),
        ("u_market", "m1", 10000),
        ("u_market", "o1", 6000),
    ],
)
def test_the_documented_accounts_are_untouched(
    db, seller_id: str, customer_id: str, expected: int
) -> None:
    assert balance_of(db, seller_id, customer_id) == expected


def test_the_test_phone_numbers_still_resolve(db) -> None:
    # What the device scenarios actually sign in with.
    for phone in ("+905554443322", "+905551112233", "+905553334455"):
        user = db.execute(
            select(models.User).where(models.User.phone == phone)
        ).scalar_one_or_none()
        assert user is not None


def test_demo_phone_numbers_do_not_collide_with_seeded_ones(db) -> None:
    # phone is UNIQUE, so a collision would fail the insert -- but it would fail it
    # halfway through, leaving a half-seeded database that looks seeded.
    total = db.execute(select(func.count()).select_from(models.User)).scalar_one()
    distinct = db.execute(
        select(func.count(func.distinct(models.User.phone)))
    ).scalar_one()
    assert total == distinct


def test_running_it_twice_changes_nothing(db) -> None:
    # Alembic runs on every container start; an unguarded second pass would double every
    # balance, which is the exact symptom that is supposed to mean "client and server have
    # drifted apart".
    before = db.execute(select(func.count()).select_from(models.Transaction)).scalar_one()

    seed_demo(db)

    assert already_seeded(db)
    after = db.execute(select(func.count()).select_from(models.Transaction)).scalar_one()
    assert after == before


def test_no_account_is_overpaid(db) -> None:
    # A payment larger than the debt would leave a negative balance -- which the indexer
    # deliberately refuses to touch, so the fault would surface later as missing indexation
    # rows, far from its cause.
    balances = db.execute(
        select(models.Transaction.seller_id, models.Transaction.customer_id, _BALANCE)
        .group_by(models.Transaction.seller_id, models.Transaction.customer_id)
    ).all()

    assert [row for row in balances if row[2] < 0] == []


def test_there_is_enough_data_to_plot(db) -> None:
    # The whole reason this file exists. A chart over fifteen entries has nothing to show.
    shops = db.execute(
        select(func.count()).select_from(models.User).where(models.User.is_seller)
    ).scalar_one()
    entries = db.execute(select(func.count()).select_from(models.Transaction)).scalar_one()

    assert shops >= 5
    assert entries > 1_000


def test_entries_span_a_full_year(db) -> None:
    # Twelve distinct months, so a monthly series has twelve points rather than a spike.
    months = db.execute(
        select(func.count(func.distinct(func.strftime("%Y-%m", models.Transaction.created_at))))
    ).scalar_one()

    assert months >= 12


def test_the_rate_series_covers_every_entry(db) -> None:
    # Indexation is silent where the index is unknown, so a gap here would show up as
    # accounts that inexplicably never accrue.
    first_entry = db.execute(
        select(func.min(models.Transaction.created_at))
    ).scalar_one()
    first_rate = db.execute(select(func.min(models.FxRate.as_of))).scalar_one()
    last_rate = db.execute(select(func.max(models.FxRate.as_of))).scalar_one()

    assert first_rate <= first_entry.date()
    assert last_rate >= first_entry.date()


def test_the_index_only_rises(db) -> None:
    # Deflation is legal in fx.py and would be honoured, but a demo that happened to
    # produce a falling month would show a customer's debt shrinking on its own -- which
    # is not the story this data is here to tell.
    values = db.execute(
        select(models.FxRate.cpi_index).order_by(models.FxRate.as_of)
    ).scalars().all()

    assert values == sorted(values)


def test_some_customers_are_unclaimed(db) -> None:
    # The app-less customer is the majority case in a real shop and the one the claim flow
    # exists for. A demo of only claimed customers would never exercise it.
    unclaimed = db.execute(
        select(func.count())
        .select_from(models.Customer)
        .where(models.Customer.claim_status == "UNCLAIMED")
    ).scalar_one()

    assert unclaimed > 0


def test_buyers_hold_records_at_several_shops(db) -> None:
    # /me/debts groups BY shop; with one shop per person it would always render a list of
    # one and the grouping would never be seen to work.
    most_shops = db.execute(
        select(func.count())
        .select_from(models.Customer)
        .where(models.Customer.claimed_by_user_id.is_not(None))
        .group_by(models.Customer.claimed_by_user_id)
        .order_by(func.count().desc())
        .limit(1)
    ).scalar_one()

    assert most_shops >= 2


def test_traffic_is_recorded_for_the_admin_panel(db) -> None:
    # Seeded only -- nothing writes these live (deferred.md §L.1).
    rows = db.execute(select(func.count()).select_from(models.AuditLog)).scalar_one()
    failures = db.execute(
        select(func.count())
        .select_from(models.AuditLog)
        .where(models.AuditLog.status_code >= 400)
    ).scalar_one()

    assert rows > 1_000
    # A traffic chart with no errors in it teaches the reader that errors do not happen.
    assert failures > 0
