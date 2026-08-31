"""Add audit_log -- who did what, and when.

Two things ask for this. KVKK compliance wants a trail that outlives the record it
describes, and the admin panel wants to show traffic: who is active, what is being called,
when the load falls.

A table of its own rather than columns on the rows being changed, because the trail has to
survive its subject. A deleted customer must still leave evidence that someone deleted
them, and a "deleted_by" column on a row that no longer exists records nothing.

`actor_user_id` deliberately carries NO foreign key. With one, deleting a user would
either be blocked by the trail or would take the trail with it -- and both defeat the
point of keeping it.

NOTHING WRITES TO THIS TABLE YET. The rows come from the demo seed and the admin panel
reads them; the middleware that would record live requests was deliberately not written
(deferred.md §L.1), because the system is not live and demo traffic would be too thin to
plot. The table lands now so that adding that middleware later is one new file rather than
a migration plus a file.

Revision ID: 0007
Revises: 0006
Create Date: 2026-08-31
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0007"
down_revision: str | None = "0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "audit_log",
        sa.Column("id", sa.String(), nullable=False),
        # Nullable: an unauthenticated attempt (a failed login) still belongs in the trail,
        # and it has no actor to name.
        sa.Column("actor_user_id", sa.String(), nullable=True),
        sa.Column("action", sa.String(), nullable=False),
        # Null for actions that touch no single row.
        sa.Column("entity_type", sa.String(), nullable=True),
        sa.Column("entity_id", sa.String(), nullable=True),
        sa.Column("ip", sa.String(), nullable=True),
        sa.Column("user_agent", sa.String(), nullable=True),
        # A refused attempt is worth more than a successful one: repeated 401s are the
        # shape of someone trying keys.
        sa.Column("status_code", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    # The panel asks two questions of this table: "what has this person done" and "what
    # happened in this window". One index each.
    op.create_index("idx_audit_actor", "audit_log", ["actor_user_id", "created_at"])
    op.create_index("idx_audit_created", "audit_log", ["created_at"])


def downgrade() -> None:
    op.drop_index("idx_audit_created", table_name="audit_log")
    op.drop_index("idx_audit_actor", table_name="audit_log")
    op.drop_table("audit_log")
