"""
Model -> wire conversions that more than one router needs.

Kept out of the routers so the nested SellerInfo is rebuilt identically everywhere: a
user serialised one way by /auth and another way by /users would be a bug the client
only notices on whichever screen happens to read the odd one.
"""

from . import models, schemas


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
