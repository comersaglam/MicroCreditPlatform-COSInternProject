"""
Make the demo's inflation real. Run by hand, never over HTTP:

    docker compose exec api python -m app.warm_indexation

WHY THIS EXISTS. Indexation is LAZY: `indexation.ensure_indexed*` runs when a balance is
read by the person it belongs to, so a month's inflation only becomes a ledger row once
someone looks. The demo population has never been looked at -- `seed_demo` writes debts
and payments directly, and no phone has ever signed in as one of its 40 accounts.

Measured before this file existed: 22 INDEXATION rows on the whole platform, all of them
belonging to the two accounts from the core seed that device testing had opened. Total
inflation across a year of 5800 entries: 204,70 lira. So the product's central argument --
that a shopkeeper's receivable quietly loses value, and this system charges for it -- was
the one number the admin panel would have shown as roughly zero.

The code was right and the data was absent. That is deferred.md §L.17's finding, and this
command is its fix.

NOT AN ENDPOINT OF ITS OWN, for the same reason `reset.py` is not one: indexation rows
land in an append-only table (migration 0001's trigger) and cannot be taken back
(deferred.md §L.7). `ensure_indexed` is idempotent per month, so a second run is harmless
-- but a button that writes irreversible rows should not be one mistap away, and the panel
stays a pure reader (routers/admin.py).

The one exception is POST /admin/reset, which calls `warm_indexation` below as the last
step of restoring the demo. That is not the panel deciding to index: it is the panel
putting back what the reset just deleted.
"""

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import models
from .db import SessionLocal
from .indexation import ensure_indexed_book


def warm_indexation(db: Session) -> int:
    """
    Index every book on the platform. Returns rows written.

    Per seller rather than per customer: ensure_indexed_book already fetches the book once
    and loops inside, which is the batching the customer list needed.
    """
    sellers = db.execute(
        select(models.Transaction.seller_id).distinct()
    ).scalars().all()

    return sum(ensure_indexed_book(db, seller_id) for seller_id in sellers)


def main() -> None:
    db = SessionLocal()
    try:
        before = _rows(db)
        written = warm_indexation(db)
        after = _rows(db)

        print(f"INDEXATION rows: {before} -> {after} (+{written})")
        if not written:
            print(
                "Nothing written. Either every month is already indexed, or fx_rates "
                "does not reach the months in question (see fx.cpi_ratio)."
            )
    finally:
        db.close()


def _rows(db) -> int:
    return db.execute(
        select(func.count())
        .select_from(models.Transaction)
        .where(models.Transaction.type == "INDEXATION")
    ).scalar_one()


if __name__ == "__main__":
    main()
