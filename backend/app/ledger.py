"""
Balance derivation -- the server's half of a rule the device also implements.

This mirrors core-domain/Ledger.kt::balanceOf exactly: DEBT and INDEXATION add to what is
owed, PAYMENT subtracts, and the amount is always positive with the sign carried by the
type. The two implementations must agree, because the shopkeeper compares the number on
the terminal with the number the server reports and any drift reads as money going missing.

That agreement is why indexation is a ROW and not a formula. The rule below is written out
ten times across this codebase -- here, twice in Kotlin, and seven times as SQL inside Room
DAOs -- and seven of those copies live in strings no compiler checks. Adding a type to the
sum touches all ten; multiplying the sum by an index would have touched all ten AND
required the rate table on every device.

The balance is computed, never stored. A stored column would be the one place the two
could diverge without anything noticing.

Book MEMBERSHIP lives here too, next to the balance it scopes. Both answer "which rows
belong to this seller", and both routers that ask -- the book screen and the ledger write
-- must get the same answer, which they only do while there is one implementation.
"""

from sqlalchemy import BigInteger, case, func, select
from sqlalchemy.orm import Session

from . import models

# DEBT and INDEXATION count positive, PAYMENT negative. Anything else contributes 0 rather
# than being guessed at: an unrecognised type must never silently land on one side of the
# sum.
#
# INDEXATION is the month's inflation on what was still owed (see indexation.py). It adds
# for the same reason DEBT does -- it is part of what the customer owes -- and carrying it
# as a row here rather than as a factor is what keeps this a plain SUM. The seven copies of
# this formula in the Room DAOs must list the same three cases; they were written with an
# `ELSE -amountMinor` that would have counted indexation as a PAYMENT and subtracted it.
_SIGNED_AMOUNT = case(
    (models.Transaction.type == "DEBT", models.Transaction.amount_minor),
    (models.Transaction.type == "INDEXATION", models.Transaction.amount_minor),
    (models.Transaction.type == "PAYMENT", -models.Transaction.amount_minor),
    else_=0,
)

# COALESCE so a customer with no ledger rows yet reports 0 instead of NULL.
_BALANCE = func.coalesce(func.sum(_SIGNED_AMOUNT), 0).cast(BigInteger)


def book_customer_ids(
    db: Session, seller_id: str, phone: str | None = None
) -> set[str]:
    """
    Which customer rows belong to this seller's book.

    Membership has two sources, and both are needed. The ledger covers everyone this
    seller has ever charged, which is the normal case. `created_by_seller_id` covers the
    customer who was written down but not yet charged -- they have no ledger rows, and on
    the ledger alone they would belong to no book at all.

    Optionally narrowed to one phone, which is how the duplicate check asks the same
    question about a single person.
    """
    charged = select(models.Transaction.customer_id).where(
        models.Transaction.seller_id == seller_id
    )
    added = select(models.Customer.customer_id).where(
        models.Customer.created_by_seller_id == seller_id
    )

    if phone is not None:
        # Restrict both halves to the rows for this phone, so the union stays about one
        # person instead of pulling the whole book back to filter in Python.
        matching = select(models.Customer.customer_id).where(
            models.Customer.phone == phone
        )
        charged = charged.where(models.Transaction.customer_id.in_(matching))
        added = added.where(models.Customer.phone == phone)

    return set(db.execute(charged.union(added)).scalars().all())


def is_in_book(db: Session, seller_id: str, customer_id: str) -> bool:
    """
    Whether this seller may write against this customer.

    Asked as a single EXISTS rather than by loading the whole book: the write path runs
    on every entry, and it only needs a yes or no about one person.
    """
    charged = select(models.Transaction.customer_id).where(
        models.Transaction.seller_id == seller_id,
        models.Transaction.customer_id == customer_id,
    )
    added = select(models.Customer.customer_id).where(
        models.Customer.created_by_seller_id == seller_id,
        models.Customer.customer_id == customer_id,
    )
    return db.execute(select(charged.union(added).exists())).scalar_one()


def balance_of(db: Session, seller_id: str, customer_id: str) -> int:
    """The (seller, customer) pair's summed ledger."""
    return db.execute(
        select(_BALANCE).where(
            models.Transaction.seller_id == seller_id,
            models.Transaction.customer_id == customer_id,
        )
    ).scalar_one()


def balances_by_customer(db: Session, seller_id: str) -> dict[str, int]:
    """
    Every customer's balance in one seller's book, as a single grouped query.

    Batched rather than called per customer: the customer list is the shop's main screen,
    and a per-row query would turn one screen into N round trips.
    """
    rows = db.execute(
        select(models.Transaction.customer_id, _BALANCE)
        .where(models.Transaction.seller_id == seller_id)
        .group_by(models.Transaction.customer_id)
    ).all()
    return {customer_id: balance for customer_id, balance in rows}


def debts_by_seller(db: Session, customer_ids: list[str]) -> dict[str, int]:
    """
    What a buyer owes each shop, keyed by seller_id.

    Takes the buyer's customer rows (one per shop) rather than a user id, because the
    ledger is written against customer records -- the same person is c1 in one book and
    m1 in another.
    """
    if not customer_ids:
        return {}

    rows = db.execute(
        select(models.Transaction.seller_id, _BALANCE)
        .where(models.Transaction.customer_id.in_(customer_ids))
        .group_by(models.Transaction.seller_id)
    ).all()
    return {seller_id: balance for seller_id, balance in rows}
