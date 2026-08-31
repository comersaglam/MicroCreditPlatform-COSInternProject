"""Add fx_rates -- what a lira was worth on a given day.

The ledger records what was owed and when, but not what that amount was WORTH at the
time. Without the second half, a debt taken on a year ago and settled today is settled in
money that buys less, and the shopkeeper who extended the credit absorbs the difference
silently. That difference is the thing this table makes computable.

Kept rather than fetched: the question is always about a past day, and a live rate cannot
answer it. Fetching on demand would also put an outbound network call in the path of a
balance read, which is the one read that must never be slow or fail.

One row per day. Rates move continuously, but a ledger entry is only ever compared with
another day, so a finer key would store precision nothing reads.

Every column is an integer. Money is kuruş (usd/eur/gold: what ONE unit costs), and
cpi_index is the consumer price index ×100. Only ratios of the index are ever taken, so
the base period it is anchored to is not part of the contract.

This table is populated by the demo seed, not by a live feed -- see deferred.md §L.4. The
shape is what matters here; swapping the source later changes no schema and no caller.

Revision ID: 0006
Revises: 0005
Create Date: 2026-08-31
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0006"
down_revision: str | None = "0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "fx_rates",
        # The day IS the key. A separate surrogate id would allow two rows for one day,
        # and "the rate on the 3rd" would stop having a single answer.
        sa.Column("as_of", sa.Date(), nullable=False),
        sa.Column("usd_minor", sa.BigInteger(), nullable=False),
        sa.Column("eur_minor", sa.BigInteger(), nullable=False),
        sa.Column("gold_minor", sa.BigInteger(), nullable=False),
        sa.Column("cpi_index", sa.BigInteger(), nullable=False),
        sa.PrimaryKeyConstraint("as_of"),
    )


def downgrade() -> None:
    op.drop_table("fx_rates")
