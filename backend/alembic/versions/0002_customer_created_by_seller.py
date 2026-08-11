"""Add customers.created_by_seller_id -- book membership for uncharged customers.

Book membership is otherwise derived from the ledger ("this seller has written an entry
against this customer"), which leaves a customer who has been added but not yet charged
belonging to no book: POST /customers would succeed and GET /customers would not show
them. Nullable because every seeded row predates the column and, more importantly,
because the customer row is SHARED between shops -- this records who wrote the person
down first, not who owns them.

Revision ID: 0002
Revises: 0001
Create Date: 2026-08-10
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0002"
down_revision: str | None = "0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "customers",
        sa.Column("created_by_seller_id", sa.String(), nullable=True),
    )
    op.create_foreign_key(
        "fk_customers_created_by_seller",
        "customers",
        "users",
        ["created_by_seller_id"],
        ["user_id"],
    )
    op.create_index(
        "idx_customers_created_by", "customers", ["created_by_seller_id"]
    )


def downgrade() -> None:
    op.drop_index("idx_customers_created_by", table_name="customers")
    op.drop_constraint("fk_customers_created_by_seller", "customers", type_="foreignkey")
    op.drop_column("customers", "created_by_seller_id")
