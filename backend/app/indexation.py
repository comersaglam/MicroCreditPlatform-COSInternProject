"""
Monthly indexation: the inflation on an outstanding balance, written as ledger rows.

WHY A ROW AND NOT A FORMULA
The obvious way to index a debt is to compute `amount x cpi_now / cpi_then` inside the
balance query. It was rejected. The balance formula exists in ten places -- once here on
the server, twice in Kotlin, and seven times as SQL inside Room DAOs -- and all ten must
agree, because the shopkeeper compares the terminal's number with the server's and any
drift reads as money going missing. Changing a formula in ten places, seven of them inside
strings the compiler never checks, is how that drift starts.

So the formula stays `SUM`, and indexation becomes something the sum can see: an ordinary
ledger row of type INDEXATION. Every consumer that could already add up DEBT and PAYMENT
gets the indexed balance for free.

Three things fall out of that choice, all of them good:
  * the adjustment is auditable -- each month is its own row, with the rate in its
    description, and a customer can be shown exactly where a figure came from
  * it compounds naturally -- next month's balance already contains this month's row, so
    reading the balance and applying one month's rate IS compounding
  * it survives offline -- the row syncs to the device like any other entry

WHEN THE ROWS GET WRITTEN
Lazily, when a balance is read (see `ensure_indexed`). There is no scheduler in this
project and adding one for this would mean the numbers were only correct while a container
happened to be running. Catching up on read means a database untouched for six months
still produces the right answer the moment someone asks.

WHAT MAKES IT SAFE TO CALL ON EVERY READ
The row id is derived from the data, not generated: `idx_{seller}_{customer}_{YYYY-MM}`.
A month already written cannot be written twice, because the primary key is the same. This
is the idempotency rule the write endpoint already uses (`Idempotency-Key ==
transaction_id`), applied to rows the server mints for itself.

Only the server writes these. `_VALID_TYPES` in routers/ledger.py stays {DEBT, PAYMENT},
so no client can mint indexation and inflate a debt at will.
"""

from datetime import UTC, date, datetime
from fractions import Fraction

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import models
from .fx import apply_ratio, month_start, monthly_index, next_month
from .ledger import _BALANCE

# Rows carry the type the balance formula recognises as adding to what is owed.
INDEXATION = "INDEXATION"

_MONTH_NAMES_TR = (
    "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
    "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık",
)


def _row_id(seller_id: str, customer_id: str, month: date) -> str:
    """
    The id a given month's row must have.

    Derived rather than random, so that computing the same month twice produces the same
    key and the second insert is refused by the primary key instead of doubling a debt.
    """
    return f"idx_{seller_id}_{customer_id}_{month:%Y-%m}"


def _description(month: date, ratio_pct: float) -> str:
    """
    What the customer reads on the row.

    Names the month and the rate, because an entry on someone's debt that says only
    "indexation" is the kind of line item that destroys trust in the whole ledger.
    """
    name = _MONTH_NAMES_TR[month.month - 1]
    return f"{name} {month.year} enflasyon farkı (%{ratio_pct:.2f})".replace(".", ",")


def _balance_before(db: Session, seller_id: str, customer_id: str, cutoff: date) -> int:
    """
    What was owed at the START of `cutoff` -- everything strictly before that instant.

    Includes indexation rows already written for earlier months, which is precisely what
    makes the result compound: each month is applied to a balance that carries every month
    before it.
    """
    return db.execute(
        select(_BALANCE).where(
            models.Transaction.seller_id == seller_id,
            models.Transaction.customer_id == customer_id,
            models.Transaction.created_at
            < datetime(cutoff.year, cutoff.month, cutoff.day, tzinfo=UTC),
        )
    ).scalar_one()


def _first_entry_month(db: Session, seller_id: str, customer_id: str) -> date | None:
    """The month this pair's ledger opens in, or None if they have no entries."""
    first = db.execute(
        select(models.Transaction.created_at)
        .where(
            models.Transaction.seller_id == seller_id,
            models.Transaction.customer_id == customer_id,
        )
        .order_by(models.Transaction.created_at)
        .limit(1)
    ).scalar_one_or_none()

    return None if first is None else month_start(first.date())


def _existing_months(db: Session, seller_id: str, customer_id: str) -> set[date]:
    """Months this pair has already been indexed for."""
    rows = db.execute(
        select(models.Transaction.created_at).where(
            models.Transaction.seller_id == seller_id,
            models.Transaction.customer_id == customer_id,
            models.Transaction.type == INDEXATION,
        )
    ).scalars().all()

    return {month_start(row.date()) for row in rows}


def ensure_indexed(
    db: Session, seller_id: str, customer_id: str, today: date | None = None
) -> int:
    """
    Bring one (seller, customer) pair's indexation up to date. Returns rows written.

    Walks forward one month at a time from the month AFTER their first entry, because a
    debt taken on in March has not yet been outstanding for a month when March begins.

    COMMITS. Callers read the balance immediately afterwards, and rows still sitting in an
    uncommitted session would be invisible to the separate query that follows -- the
    balance would come back short by exactly the amount just written.

    Skips a month, rather than writing a zero or negative row, when:
      * nothing was owed at the time  -- indexing nothing yields nothing, and a customer in
        credit is not charged inflation on the shop's own debt to them
      * the index is unknown for that span -- see fx.cpi_ratio; silence is the honest
        answer, and pretending prices held flat would be a claim we cannot support
      * the rounded difference is zero -- a row that changes no balance is noise in a
        ledger someone has to read
    """
    today = today or datetime.now(UTC).date()

    opened = _first_entry_month(db, seller_id, customer_id)
    if opened is None:
        return 0

    current_month = month_start(today)
    already = _existing_months(db, seller_id, customer_id)

    # Every month that has fully elapsed since the ledger opened. The current month is
    # excluded: its inflation is not known until it ends.
    months: list[date] = []
    cursor = next_month(opened)
    while cursor <= current_month:
        months.append(cursor)
        cursor = next_month(cursor)

    if not months:
        return 0

    # One query for the whole span rather than two per month.
    index_by_month = monthly_index(db, [opened, *months])

    written = 0
    # The month each one is measured against: the previous month in the walk, starting
    # from the month the ledger opened.
    reference = opened

    for month in months:
        previous, current = index_by_month.get(reference), index_by_month.get(month)
        # Advance the reference even when this month is skipped, so a gap in the index
        # does not silently make the NEXT month measure across two months of inflation.
        reference = month

        if month in already:
            continue
        if previous is None or current is None or previous <= 0 or current <= 0:
            continue

        ratio = Fraction(current, previous)
        balance = _balance_before(db, seller_id, customer_id, month)
        if balance <= 0:
            continue

        delta = apply_ratio(balance, ratio) - balance
        if delta <= 0:
            continue

        db.add(
            models.Transaction(
                transaction_id=_row_id(seller_id, customer_id, month),
                seller_id=seller_id,
                customer_id=customer_id,
                amount_minor=delta,
                type=INDEXATION,
                description=_description(month, (float(ratio) - 1) * 100),
                basket_id=None,
                settled_via_pgw=False,
                receipt_no=None,
                # Dated to the month it belongs to, not to the moment it was noticed.
                # A row stamped "today" would sort after later purchases and make the
                # history read as though the shop back-dated a charge.
                created_at=datetime(month.year, month.month, 1, tzinfo=UTC),
            )
        )
        # Flushed per month so `_balance_before` for the NEXT month sees this row. Without
        # it every month would index the same starting balance and the result would be
        # simple interest rather than compound.
        db.flush()
        written += 1

    if written:
        db.commit()

    return written


def ensure_indexed_book(db: Session, seller_id: str, today: date | None = None) -> int:
    """
    Index every customer in one seller's book. Returns rows written.

    The shop's main screen reads every balance at once, and calling `ensure_indexed` from a
    loop there would be the N+1 pattern the balance query itself was batched to avoid. The
    customer list is fetched once here; the per-customer work still runs per customer,
    because each one's balance history is genuinely its own.
    """
    customer_ids = db.execute(
        select(models.Transaction.customer_id)
        .where(models.Transaction.seller_id == seller_id)
        .distinct()
    ).scalars().all()

    return sum(ensure_indexed(db, seller_id, cid, today) for cid in customer_ids)


def ensure_indexed_for_buyer(
    db: Session, customer_ids: list[str], today: date | None = None
) -> int:
    """
    Index every (seller, customer) pair a buyer appears in. Returns rows written.

    A buyer holds one customer record per shop, and each of those records has its own
    ledger with its own seller. The pairs are read from the ledger rather than assumed,
    since the same customer id only ever belongs to one shop's book.
    """
    if not customer_ids:
        return 0

    pairs = db.execute(
        select(models.Transaction.seller_id, models.Transaction.customer_id)
        .where(models.Transaction.customer_id.in_(customer_ids))
        .distinct()
    ).all()

    return sum(ensure_indexed(db, seller, customer, today) for seller, customer in pairs)
