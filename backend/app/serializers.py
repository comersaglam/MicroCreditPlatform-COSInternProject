"""
Model -> wire conversions that more than one router needs.

Kept out of the routers so the nested SellerInfo is rebuilt identically everywhere: a
user serialised one way by /auth and another way by /users would be a bug the client
only notices on whichever screen happens to read the odd one.
"""

from sqlalchemy.orm import Session

from . import models, schemas
from .baskets import load_baskets


def user_out(user: models.User) -> schemas.User:
    """
    Rebuild the domain's nested `seller_info` from the two flat storage columns.

    Storage keeps shop_name/shop_phone flat; the domain keeps a nested SellerInfo? whose
    type carries the invariant "is_seller <=> seller_info != null". The condition below
    is the mapper for that invariant, and it deliberately requires BOTH flags -- a row
    with a shop name but is_seller false is not a seller, and trusting either field alone
    would let a half-written row present as one.
    """
    seller_info = (
        schemas.SellerInfo(shop_name=user.shop_name, shop_phone=user.shop_phone)
        if user.is_seller and user.shop_name
        else None
    )
    return schemas.User(
        user_id=user.user_id,
        phone=user.phone,
        display_name=user.display_name,
        is_buyer=user.is_buyer,
        is_seller=user.is_seller,
        email=user.email,
        seller_info=seller_info,
        created_at=user.created_at,
    )


def transaction_out(
    tx: models.Transaction,
    db: Session | None = None,
    basket: schemas.OrderBody | None = None,
) -> schemas.Transaction:
    """
    A ledger entry on the wire.

    Shared by the seller-scoped and buyer-scoped readers so the same row looks identical
    from both directions -- the two sides are describing one entry, and a field rendered
    differently depending on who asked would be a contradiction the client cannot resolve.

    The basket travels WITH the entry rather than behind a `basket_id` the client could
    follow, because there is nothing to follow it to: no endpoint serves a basket on its
    own, and a device that stored only the id would hold a reference it can never resolve.

    Two ways to supply it, and callers should not mix them up. `db` reads the one basket
    this entry needs -- fine for a single response. `basket` takes an already-loaded one,
    which is how `transactions_out` avoids a query per row. Passing neither renders the
    entry without its basket.
    """
    if basket is None and db is not None and tx.basket_id is not None:
        basket = load_baskets(db, [tx.basket_id]).get(tx.basket_id)

    return schemas.Transaction(
        transaction_id=tx.transaction_id,
        seller_id=tx.seller_id,
        customer_id=tx.customer_id,
        amount_minor=tx.amount_minor,
        type=tx.type,
        description=tx.description,
        basket_id=tx.basket_id,
        basket=basket,
        settled_via_pgw=tx.settled_via_pgw,
        receipt_no=tx.receipt_no,
        created_at=tx.created_at,
    )


def transactions_out(
    rows: list[models.Transaction], db: Session
) -> list[schemas.Transaction]:
    """
    A history on the wire, with every basket fetched in one read.

    The batch exists to keep a known problem from getting worse: pullBook already issues
    one request per customer (docs/deferred.md F.5), and reading a basket per entry inside
    each of those would multiply the two together. Here the cost of the baskets does not
    grow with the length of the history.
    """
    basket_ids = [tx.basket_id for tx in rows if tx.basket_id is not None]
    baskets = load_baskets(db, basket_ids)

    return [
        transaction_out(tx, basket=baskets.get(tx.basket_id) if tx.basket_id else None)
        for tx in rows
    ]
