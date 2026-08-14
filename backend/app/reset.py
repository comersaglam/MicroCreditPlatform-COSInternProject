"""
Wipe the database back to the demo seed. Run by hand, never over HTTP:

    docker compose exec api python -m app.reset

DELIBERATELY NOT AN ENDPOINT (deferred.md §F.4). Not even a token-gated or debug-gated one:
the decision was that a user being *able* to erase their ledger by accident is a problem on
its own, regardless of how well the door is locked. A shell command cannot be reached by a
mistapped button, a stale client, or a leaked token.

Why this exists at all: the devices no longer seed themselves (Turn 39 removed SeedCallback
from both apps), so the server holds the only demo data. Re-running a device test therefore
means resetting the server, and doing that by hand meant remembering the FK order and the
append-only trigger every time.

TRUNCATE rather than DELETE, for a concrete reason: `transactions` carries a BEFORE UPDATE
OR DELETE trigger (migration 0001) that raises on every row, so a DELETE cannot clear it at
all. TRUNCATE is a table-level operation and the row trigger never fires -- the append-only
guarantee stays fully intact for the application, which is the only thing it is protecting
against. RESTART IDENTITY CASCADE keeps the FK graph consistent in one statement.
"""

from sqlalchemy import text
from sqlalchemy.orm import Session

from .db import SessionLocal
from .seed import is_empty, seed

# Every table Alembic owns. Listed explicitly rather than reflected so that a table added
# later is a deliberate edit here -- a reset that silently skips a new table would leave
# rows behind and look like a bug in whatever read them next.
_TABLES = (
    "approvals",
    "transactions",
    "basket_items",
    "baskets",
    "customers",
    "users",
)


def reset(db: Session) -> None:
    """Clear every application table, then re-seed. `alembic_version` is left alone."""
    db.execute(
        text(f"TRUNCATE TABLE {', '.join(_TABLES)} RESTART IDENTITY CASCADE")
    )
    # One transaction: a crash between the wipe and the seed would otherwise leave an
    # empty database that the startup guard would happily treat as "never seeded" only on
    # the next boot, and as broken until then.
    seed(db)
    db.commit()


def main() -> None:
    db = SessionLocal()
    try:
        reset(db)
        # is_empty() is the same marker the startup path uses; if it still says empty, the
        # seed did not take and the operator should know before running a device test.
        state = "EMPTY -- seed did not take" if is_empty(db) else "seeded"
        print(f"Reset complete: {state}.")
    finally:
        db.close()


if __name__ == "__main__":
    main()
