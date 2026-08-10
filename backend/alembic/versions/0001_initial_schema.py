"""Initial schema -- the six Bölüm A tables from docs/db-schema.md.

Revision ID: 0001
Revises:
Create Date: 2026-08-10
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("user_id", sa.String(), primary_key=True),
        sa.Column("phone", sa.String(), nullable=False, unique=True),
        sa.Column("display_name", sa.String(), nullable=False),
        sa.Column("is_buyer", sa.Boolean(), nullable=False),
        sa.Column("is_seller", sa.Boolean(), nullable=False),
        sa.Column("email", sa.String(), nullable=True),
        sa.Column("shop_name", sa.String(), nullable=True),
        sa.Column("shop_phone", sa.String(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )

    op.create_table(
        "customers",
        sa.Column("customer_id", sa.String(), primary_key=True),
        sa.Column("display_name", sa.String(), nullable=False),
        sa.Column("phone", sa.String(), nullable=False),
        sa.Column("claim_status", sa.String(), nullable=False),
        sa.Column(
            "claimed_by_user_id",
            sa.String(),
            sa.ForeignKey("users.user_id"),
            nullable=True,
        ),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("idx_customers_phone", "customers", ["phone"])

    op.create_table(
        "baskets",
        sa.Column("basket_id", sa.String(), primary_key=True),
        sa.Column("create_invoice", sa.Boolean(), nullable=False),
        sa.Column("document_type", sa.Integer(), nullable=False),
        sa.Column("is_void", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )

    op.create_table(
        "basket_items",
        sa.Column("id", sa.String(), primary_key=True),
        sa.Column(
            "basket_id", sa.String(), sa.ForeignKey("baskets.basket_id"), nullable=False
        ),
        sa.Column("name", sa.String(), nullable=False),
        sa.Column("price_minor", sa.BigInteger(), nullable=False),
        sa.Column("quantity", sa.BigInteger(), nullable=False),
        sa.Column("tax_percent", sa.BigInteger(), nullable=False),
        sa.Column("section_no", sa.Integer(), nullable=False),
        sa.Column("status", sa.Integer(), nullable=False),
        sa.Column("type", sa.Integer(), nullable=False),
        sa.Column("item_limit", sa.BigInteger(), nullable=False),
    )
    op.create_index("idx_basket_items_basket", "basket_items", ["basket_id"])

    op.create_table(
        "transactions",
        sa.Column("transaction_id", sa.String(), primary_key=True),
        sa.Column("seller_id", sa.String(), nullable=False),
        sa.Column(
            "customer_id",
            sa.String(),
            sa.ForeignKey("customers.customer_id"),
            nullable=False,
        ),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("type", sa.String(), nullable=False),
        sa.Column("description", sa.String(), nullable=False),
        sa.Column(
            "basket_id", sa.String(), sa.ForeignKey("baskets.basket_id"), nullable=True
        ),
        sa.Column(
            "settled_via_pgw", sa.Boolean(), nullable=False, server_default=sa.false()
        ),
        sa.Column("receipt_no", sa.String(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("idx_tx_seller_customer", "transactions", ["seller_id", "customer_id"])
    op.create_index("idx_tx_customer", "transactions", ["customer_id"])

    op.create_table(
        "approvals",
        sa.Column("approval_id", sa.String(), primary_key=True),
        sa.Column("initiator_user_id", sa.String(), nullable=False),
        sa.Column("initiator_role", sa.String(), nullable=False),
        sa.Column("target_user_id", sa.String(), nullable=False),
        sa.Column("seller_id", sa.String(), nullable=False),
        sa.Column("shop_name", sa.String(), nullable=False),
        sa.Column("customer_id", sa.String(), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("type", sa.String(), nullable=False),
        sa.Column("description", sa.String(), nullable=True),
        sa.Column("channel", sa.String(), nullable=False),
        sa.Column("status", sa.String(), nullable=False),
        sa.Column("requested_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("idx_approvals_target", "approvals", ["target_user_id", "status"])

    # Append-only, enforced by the database rather than by convention. A correction is a
    # new row with the opposite type; nothing may rewrite history. This is a trigger and
    # not a REVOKE because the app connects as the schema owner in development, and an
    # owner's privileges cannot be revoked from itself.
    op.execute(
        """
        CREATE OR REPLACE FUNCTION transactions_append_only()
        RETURNS TRIGGER AS $$
        BEGIN
            RAISE EXCEPTION 'transactions is append-only: % is not permitted', TG_OP;
        END;
        $$ LANGUAGE plpgsql;
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_transactions_append_only
        BEFORE UPDATE OR DELETE ON transactions
        FOR EACH ROW EXECUTE FUNCTION transactions_append_only();
        """
    )


def downgrade() -> None:
    op.execute("DROP TRIGGER IF EXISTS trg_transactions_append_only ON transactions")
    op.execute("DROP FUNCTION IF EXISTS transactions_append_only()")
    op.drop_table("approvals")
    op.drop_table("transactions")
    op.drop_table("basket_items")
    op.drop_table("baskets")
    op.drop_table("customers")
    op.drop_table("users")
