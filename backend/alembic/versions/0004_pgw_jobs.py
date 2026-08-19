"""Add pgw_jobs, and approvals.origin -- work the server leaves for a POS terminal.

Paths 3, 4 and 5 all end with the terminal handing something to the payment gateway, but
they START somewhere else (the seller's phone, or the buyer's). The server cannot call a
terminal: it is behind NAT and there is no push channel yet, so the only way to reach one
is to leave the work where it will come and look.

A separate table rather than a flag on `approvals`, because the questions differ. An
approval records who must DECIDE; a job records who must DELIVER, and whether they have.
That delivered flag is the whole reason this cannot be derived from the approval alone --
without it a reinstalled terminal would re-print every receipt it ever handled.

`transaction_id` and `order_body` are nullable because COLLECT jobs have neither: the
money has not been taken yet, so there is no entry to point at and no slip to print.

No foreign key on `transaction_id`. The RECEIPT job is written in the same commit as the
entry it names, but a COLLECT job carries NULL, and the column is better read as "the
entry this accompanies, if any" than as a constraint that only half the rows can satisfy.

`approvals.origin` comes along in the same revision because it is what decides whether a
job is queued at all. An approval raised AT the terminal needs none -- that terminal is
already at the gateway and fires the intent itself -- so without this column approving a
POS-raised sale would hand the same receipt over twice.

Existing rows backfill to POS. Strictly they were all phone-raised (only app-mobile could
open one before this turn), but the value is chosen for what it DOES, not for what it
records: POS is the branch that queues nothing, and an old pending approval decided after
this migration must not suddenly print a receipt for a sale that was completed weeks ago.
The honest reading of the column for those rows is "no gateway work", and that is the
value that says so.

Revision ID: 0004
Revises: 0003
Create Date: 2026-08-19
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0004"
down_revision: str | None = "0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "pgw_jobs",
        sa.Column("job_id", sa.String(), nullable=False),
        sa.Column("seller_id", sa.String(), nullable=False),
        sa.Column("kind", sa.String(), nullable=False),
        sa.Column("transaction_id", sa.String(), nullable=True),
        sa.Column("customer_id", sa.String(), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("order_body", sa.String(), nullable=True),
        sa.Column("status", sa.String(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["seller_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("job_id"),
    )
    # The only query this table serves: "what is still waiting for MY terminal".
    op.create_index(
        "idx_pgw_jobs_seller", "pgw_jobs", ["seller_id", "status"], unique=False
    )

    # Three steps, like 0003: the table is populated in every seeded environment, and a
    # NOT NULL column cannot be added to existing rows without giving them a value first.
    op.add_column("approvals", sa.Column("origin", sa.String(), nullable=True))
    op.execute("UPDATE approvals SET origin = 'POS' WHERE origin IS NULL")
    op.alter_column("approvals", "origin", nullable=False)


def downgrade() -> None:
    op.drop_column("approvals", "origin")
    op.drop_index("idx_pgw_jobs_seller", table_name="pgw_jobs")
    op.drop_table("pgw_jobs")
