"""Add approvals.updated_at -- when the row last CHANGED, not when it was raised.

`requested_at` records the moment the request was opened and is never touched again, so a
decided approval is indistinguishable in time from a pending one. Nothing stamps the
PENDING -> APPROVED/REJECTED transition, which means the trail cannot answer "when was
this answered?" and a future delta sync has nothing to filter on.

Filled from `requested_at` rather than now(): a row that was never decided genuinely last
changed when it was created, and back-dating that way keeps the column honest for the
seeded history instead of claiming every old row changed at migration time.

Written in three steps because the table is populated in every seeded environment: a
NOT NULL column cannot be added to existing rows without a value, and the codebase has no
server_default convention for timestamps (Python owns them -- every write site calls
datetime.now(UTC) explicitly).

Revision ID: 0003
Revises: 0002
Create Date: 2026-08-14
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0003"
down_revision: str | None = "0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "approvals",
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.execute("UPDATE approvals SET updated_at = requested_at WHERE updated_at IS NULL")
    op.alter_column("approvals", "updated_at", nullable=False)


def downgrade() -> None:
    op.drop_column("approvals", "updated_at")
