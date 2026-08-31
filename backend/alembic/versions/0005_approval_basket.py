"""Add approvals.basket_id -- the basket a request was raised over.

A sale that starts at the gateway hands over a basket, and until now that basket had
nowhere to go on this path: the approval endpoint had no field for it, so every credit
sale that waited for a customer's tap arrived at the ledger money-only. The items were
gone by the time anyone approved.

Stored on the request rather than resolved at decision time, for the same reason `origin`
is (migration 0004): the decision can come hours later, and by then the handoff that
carried the basket no longer exists to ask.

Nullable, and staying that way. A money-only entry genuinely has no basket, and the column
must not imply otherwise.

Revision ID: 0005
Revises: 0004
Create Date: 2026-08-20
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0005"
down_revision: str | None = "0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("approvals", sa.Column("basket_id", sa.String(), nullable=True))
    # Named explicitly so downgrade can drop it: an anonymous constraint gets a
    # backend-generated name that this file would then have to guess.
    op.create_foreign_key(
        "fk_approvals_basket",
        "approvals",
        "baskets",
        ["basket_id"],
        ["basket_id"],
    )


def downgrade() -> None:
    op.drop_constraint("fk_approvals_basket", "approvals", type_="foreignkey")
    op.drop_column("approvals", "basket_id")
