"""
Buyer-scoped reads: the mirror image of the seller's book.

Where a seller asks "who owes me", a buyer asks "what do I owe, and to whom". The same
ledger answers both; only the direction of the question changes.

Scoping runs through `claimed_by_user_id`. A buyer holds one customer record PER SHOP --
the same person is c1 at one shop and m1 at another -- so these endpoints resolve the
signed-in user to their records first and read the ledger through those.
"""

from datetime import UTC, datetime

from fastapi import APIRouter, Query
from sqlalchemy import select

from .. import models, schemas
from ..deps import CurrentUser, DbSession
from ..indexation import ensure_indexed, ensure_indexed_for_buyer
from ..ledger import debts_by_seller
from ..ledger import balance_of as _seller_scoped_balance
from ..serializers import transactions_out

router = APIRouter(tags=["buyer"])


def _my_customer_ids(db: DbSession, user_id: str) -> list[str]:
    """Every customer record this user has claimed -- one per shop that knows them."""
    return list(
        db.execute(
            select(models.Customer.customer_id).where(
                models.Customer.claimed_by_user_id == user_id
            )
        ).scalars().all()
    )


def _my_customer_id_with(db: DbSession, user_id: str, seller_id: str) -> str | None:
    """
    This buyer's record in ONE seller's book.

    Deliberately no "any record of mine" fallback: a buyer may hold several records, and
    picking the wrong one would read from another shop's ledger entirely.
    """
    return db.execute(
        select(models.Customer.customer_id).where(
            models.Customer.claimed_by_user_id == user_id,
            models.Customer.customer_id.in_(
                select(models.Transaction.customer_id).where(
                    models.Transaction.seller_id == seller_id
                )
            ),
        )
        .limit(1)
    ).scalar_one_or_none()


@router.get("/me/debts")
def my_debts(current_user: CurrentUser, db: DbSession) -> list[schemas.SellerDebt]:
    """
    What this buyer owes each shop, grouped by shop -- app-mobile's main screen.

    Shops the buyer is square with are dropped: a 0 row is not a debt, and listing it
    would put shops on the screen the person has nothing outstanding with.

    `shop_name` falls back to the seller's own name, then to their phone. The field is
    non-nullable on the wire and the card is unreadable without it, so a seller who never
    named their shop must still produce something a human recognises.
    """
    my_ids = _my_customer_ids(db, current_user.user_id)

    # Every shop this person deals with, brought up to date in one pass. This is
    # app-mobile's main screen, and it is the one place a buyer sees all their debts at
    # once -- a figure that lagged here would contradict the shop's own screen.
    ensure_indexed_for_buyer(db, my_ids)

    balances = debts_by_seller(db, my_ids)
    if not balances:
        return []

    sellers = db.execute(
        select(models.User).where(models.User.user_id.in_(balances.keys()))
    ).scalars().all()
    by_id = {seller.user_id: seller for seller in sellers}

    debts = []
    for seller_id, balance_minor in balances.items():
        if balance_minor == 0:
            continue
        seller = by_id.get(seller_id)
        debts.append(
            schemas.SellerDebt(
                seller_id=seller_id,
                shop_name=(
                    (seller.shop_name or seller.display_name or seller.phone)
                    if seller
                    else seller_id
                ),
                # The shop's own number when it has one, else the account's. A buyer cannot
                # read the seller's user row, so this is their only way to reach them.
                shop_phone=(seller.shop_phone or seller.phone) if seller else None,
                balance_minor=balance_minor,
            )
        )

    return sorted(debts, key=lambda d: d.shop_name)


@router.get("/me/transactions")
def my_history(
    current_user: CurrentUser,
    db: DbSession,
    seller_id: str = Query(...),
) -> list[schemas.Transaction]:
    """This buyer's history with one shop, newest first."""
    customer_id = _my_customer_id_with(db, current_user.user_id, seller_id)
    if customer_id is None:
        # No shared history rather than an error: a buyer asking about a shop they have
        # never dealt with should see an empty list, not a failure.
        return []

    # Indexed here too, not just on the balance endpoints: this is the screen that lists
    # the entries, and a balance carrying adjustments the history does not show would look
    # like an arithmetic error.
    ensure_indexed(db, seller_id, customer_id)

    rows = db.execute(
        select(models.Transaction)
        .where(
            models.Transaction.seller_id == seller_id,
            models.Transaction.customer_id == customer_id,
        )
        .order_by(models.Transaction.created_at.desc())
    ).scalars().all()

    # Batched like the seller-scoped history: the buyer sees the same entries from the
    # other side, so they must arrive carrying the same baskets.
    return transactions_out(rows, db)


@router.get("/me/balances")
def my_balance(
    current_user: CurrentUser,
    db: DbSession,
    seller_id: str = Query(...),
) -> schemas.Balance:
    """This buyer's balance with one shop -- the same sum the seller sees, from the other side."""
    customer_id = _my_customer_id_with(db, current_user.user_id, seller_id)
    if customer_id is not None:
        ensure_indexed(db, seller_id, customer_id)

    return schemas.Balance(
        seller_id=seller_id,
        customer_id=customer_id or "",
        balance_minor=(
            _seller_scoped_balance(db, seller_id, customer_id) if customer_id else 0
        ),
        as_of=datetime.now(UTC),
    )
