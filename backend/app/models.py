"""
The six Bölüm A tables from docs/db-schema.md.

Type conventions come straight from that document: *_minor is BIGINT (kuruş -- never a
float), booleans are BOOLEAN, timestamps are TIMESTAMPTZ. Column names are snake_case on
the wire and in Postgres; the Kotlin side keeps camelCase and maps at the edges.

`transactions` is append-only. Nothing here issues an UPDATE or DELETE against it, and
the migration additionally REVOKEs both from the application role so the rule is enforced
by the database rather than by everyone remembering it.
"""

from datetime import datetime

from sqlalchemy import BigInteger, Boolean, DateTime, ForeignKey, Index, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base


class User(Base):
    __tablename__ = "users"

    user_id: Mapped[str] = mapped_column(String, primary_key=True)
    phone: Mapped[str] = mapped_column(String, nullable=False, unique=True)
    display_name: Mapped[str] = mapped_column(String, nullable=False)
    is_buyer: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    is_seller: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    email: Mapped[str | None] = mapped_column(String, nullable=True)

    # SellerInfo flattened: the domain keeps a nested SellerInfo? whose type enforces
    # "is_seller <=> seller_info != null"; storage keeps two nullable columns and the
    # mapper rebuilds the nested shape.
    shop_name: Mapped[str | None] = mapped_column(String, nullable=True)
    shop_phone: Mapped[str | None] = mapped_column(String, nullable=True)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class Customer(Base):
    __tablename__ = "customers"

    customer_id: Mapped[str] = mapped_column(String, primary_key=True)
    display_name: Mapped[str] = mapped_column(String, nullable=False)
    phone: Mapped[str] = mapped_column(String, nullable=False)
    claim_status: Mapped[str] = mapped_column(String, nullable=False)
    claimed_by_user_id: Mapped[str | None] = mapped_column(
        String, ForeignKey("users.user_id"), nullable=True
    )

    # Which shop first wrote this person down. The customer ROW is shared -- several
    # shops know the same person -- so this is not ownership; it exists because book
    # membership is otherwise derived from the ledger, and a customer who has been added
    # but not yet charged has no ledger rows and would belong to no book at all.
    created_by_seller_id: Mapped[str | None] = mapped_column(
        String, ForeignKey("users.user_id"), nullable=True
    )

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    # No balance column on purpose: the balance is SUM(ledger), never stored. A stored
    # copy is the one place where the server and the device could drift apart.
    __table_args__ = (
        Index("idx_customers_phone", "phone"),
        Index("idx_customers_created_by", "created_by_seller_id"),
    )


class Basket(Base):
    __tablename__ = "baskets"

    basket_id: Mapped[str] = mapped_column(String, primary_key=True)
    create_invoice: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    document_type: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    is_void: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class BasketItem(Base):
    __tablename__ = "basket_items"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    basket_id: Mapped[str] = mapped_column(
        String, ForeignKey("baskets.basket_id"), nullable=False
    )
    name: Mapped[str] = mapped_column(String, nullable=False)
    price_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)

    # Both scaled ×1000 by the PGW's orderBody convention: quantity 1000 == 1 unit,
    # tax_percent 1000 == 10%. Kept as integers so no rounding creeps in.
    quantity: Mapped[int] = mapped_column(BigInteger, nullable=False)
    tax_percent: Mapped[int] = mapped_column(BigInteger, nullable=False)

    section_no: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    status: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    type: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    # orderBody calls this `limit`; renamed to avoid the SQL reserved word.
    item_limit: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)

    __table_args__ = (Index("idx_basket_items_basket", "basket_id"),)


class Transaction(Base):
    """Append-only ledger. A correction is a new row with the opposite type."""

    __tablename__ = "transactions"

    transaction_id: Mapped[str] = mapped_column(String, primary_key=True)
    seller_id: Mapped[str] = mapped_column(String, nullable=False)
    customer_id: Mapped[str] = mapped_column(
        String, ForeignKey("customers.customer_id"), nullable=False
    )

    # Always positive; the sign lives in `type` (DEBT adds, PAYMENT subtracts).
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    type: Mapped[str] = mapped_column(String, nullable=False)
    description: Mapped[str] = mapped_column(String, nullable=False)

    basket_id: Mapped[str | None] = mapped_column(
        String, ForeignKey("baskets.basket_id"), nullable=True
    )
    settled_via_pgw: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    receipt_no: Mapped[str | None] = mapped_column(String, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    __table_args__ = (
        Index("idx_tx_seller_customer", "seller_id", "customer_id"),
        Index("idx_tx_customer", "customer_id"),
    )


class Approval(Base):
    """
    One row per pending request, across all three approval lines.

    Approved and rejected rows are NOT deleted -- `status` changes and the pending query
    filters on PENDING. Behaviour is identical for the user, but the audit trail survives.
    """

    __tablename__ = "approvals"

    approval_id: Mapped[str] = mapped_column(String, primary_key=True)
    initiator_user_id: Mapped[str] = mapped_column(String, nullable=False)
    initiator_role: Mapped[str] = mapped_column(String, nullable=False)
    target_user_id: Mapped[str] = mapped_column(String, nullable=False)
    seller_id: Mapped[str] = mapped_column(String, nullable=False)

    # Denormalised so the buyer's card can name the shop without a join back to users.
    shop_name: Mapped[str] = mapped_column(String, nullable=False)

    # Required: the request is written to whichever book it was opened against. Resolving
    # it again at approval time could pick a different row, since one buyer may have a
    # record in several shops.
    customer_id: Mapped[str] = mapped_column(String, nullable=False)

    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    type: Mapped[str] = mapped_column(String, nullable=False)
    description: Mapped[str | None] = mapped_column(String, nullable=True)
    channel: Mapped[str] = mapped_column(String, nullable=False)

    # The basket this request was raised over, stored the moment it is asked rather than
    # when it is answered: the gateway handed the items over during the sale, and by the
    # time somebody taps approve -- possibly hours later -- that handoff is long gone.
    # Nullable because a money-only entry has no basket, and because a rejected request
    # keeps its basket as the record of what was actually asked for.
    basket_id: Mapped[str | None] = mapped_column(
        String, ForeignKey("baskets.basket_id"), nullable=True
    )

    # Which kind of device raised this: POS or PHONE. Stored rather than inferred at
    # decision time, because by then the fact is gone -- and it decides whether approving
    # leaves gateway work behind. A terminal-raised request hands its own intent over; a
    # phone-raised one has nobody at the gateway, so the server must queue the job.
    origin: Mapped[str] = mapped_column(String, nullable=False)

    status: Mapped[str] = mapped_column(String, nullable=False)
    requested_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )

    # When the row last CHANGED, which `requested_at` cannot say: that one is stamped at
    # creation and never touched, so it cannot tell a decided approval from a pending one.
    # Set by hand at every write site, matching how every other timestamp here works --
    # there is no onupdate/server_default convention in this codebase.
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )

    __table_args__ = (Index("idx_approvals_target", "target_user_id", "status"),)


class PgwJob(Base):
    """
    Work waiting for a POS terminal to hand on to the payment gateway.

    The server cannot reach a terminal -- it sits behind NAT with no push channel -- so
    anything the POS must DO on the server's behalf has to be left somewhere it will come
    and look. That is this table: the terminal polls it and acts on what it finds.

    Kept apart from `approvals` because the two answer different questions. An approval
    asks "who must decide this?"; a job asks "who must deliver it, and have they?". The
    second half is what makes a separate table necessary: without a delivered flag a
    reinstalled terminal would print every receipt in its history a second time.

    Rows are not deleted once delivered. The trail of what was handed to the gateway is
    worth as much as the ledger it accompanies, and it follows the same rule the
    approvals table already sets.
    """

    __tablename__ = "pgw_jobs"

    job_id: Mapped[str] = mapped_column(String, primary_key=True)

    # Whose terminal collects this. The POS polls with its own token, so this is the only
    # thing that routes a job to the right shop.
    seller_id: Mapped[str] = mapped_column(
        String, ForeignKey("users.user_id"), nullable=False
    )

    # RECEIPT -> print a slip for an entry already in the ledger (path 3).
    # COLLECT -> open the gateway to take money at the terminal (paths 4 and 5).
    kind: Mapped[str] = mapped_column(String, nullable=False)

    # Which ledger entry this accompanies. Null for COLLECT: there the money has not been
    # taken yet, so no entry exists to point at.
    transaction_id: Mapped[str | None] = mapped_column(String, nullable=True)

    customer_id: Mapped[str] = mapped_column(String, nullable=False)
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)

    # The gateway's orderBody, verbatim, as it will be handed over. Built here rather than
    # on the device so every terminal sends the same shape for the same job.
    order_body: Mapped[str | None] = mapped_column(String, nullable=True)

    status: Mapped[str] = mapped_column(String, nullable=False)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    __table_args__ = (Index("idx_pgw_jobs_seller", "seller_id", "status"),)
