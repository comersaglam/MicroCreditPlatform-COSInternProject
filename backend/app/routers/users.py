"""
Registration and profile.

Registration is separate from sign-in on purpose: POST /auth/otp/verify does not
auto-register, so an unknown phone comes here first and signs in afterwards. Keeping the
two apart is what lets the sign-up flow change without touching sign-in.
"""

import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Response, status
from sqlalchemy import select, update

from .. import models, schemas
from ..deps import CurrentUser, DbSession
from ..phone import to_stored
from ..security import api_error
from ..serializers import user_out

router = APIRouter(tags=["users"])


@router.post("/users")
def register(body: schemas.UserCreate, db: DbSession, response: Response) -> schemas.User:
    """
    Register a user. Idempotent by phone.

    An existing phone answers 200 with the row that is already there, rather than 409.
    The client reaches this endpoint after a 404 from verify, and a retry (a dropped
    response, a double tap) must not turn into an error the user has to interpret --
    "you already have an account" and "here is your account" are the same outcome.

    Note that an existing row is returned UNCHANGED: display_name and is_seller from the
    request are ignored. This endpoint may not be used to edit somebody else's profile,
    since it needs no authentication to call.
    """
    phone = to_stored(body.phone)
    if phone is None:
        raise api_error(400, "invalid_phone", "Phone must be a valid Turkish mobile number")

    existing = db.execute(
        select(models.User).where(models.User.phone == phone)
    ).scalar_one_or_none()
    if existing is not None:
        response.status_code = status.HTTP_200_OK
        return user_out(existing)

    user = models.User(
        user_id=f"u_{uuid.uuid4().hex[:12]}",
        phone=phone,
        display_name=body.display_name,
        # Everyone is a buyer; selling is the role you opt into.
        is_buyer=True,
        is_seller=body.is_seller,
        email=None,
        shop_name=None,
        shop_phone=None,
        created_at=datetime.now(UTC),
    )
    db.add(user)
    db.commit()

    response.status_code = status.HTTP_201_CREATED
    return user_out(user)


@router.get("/users/me")
def me(current_user: CurrentUser) -> schemas.User:
    return user_out(current_user)


@router.patch("/users/me")
def update_profile(
    body: schemas.UserPatch, current_user: CurrentUser, db: DbSession
) -> schemas.User:
    """
    Update name and email.

    Phone is deliberately absent: it is the identity the account is keyed by, and
    changing it here would silently move the account to a number nobody proved they
    hold. That belongs behind its own OTP flow.

    `exclude_unset` distinguishes "field omitted" from "field sent as null" -- clearing
    an email is a real edit, and treating the two the same would make it impossible.
    """
    changes = body.model_dump(exclude_unset=True)

    if "display_name" in changes:
        if not changes["display_name"]:
            raise api_error(400, "invalid_name", "Display name cannot be empty")
        current_user.display_name = changes["display_name"]

    if "email" in changes:
        current_user.email = changes["email"]

    db.commit()
    return user_out(current_user)


@router.post("/users/me/become-seller")
def become_seller(
    body: schemas.BecomeSeller, current_user: CurrentUser, db: DbSession
) -> schemas.User:
    """
    Turn the account into a seller.

    is_seller and shop_name are set together, never separately: the domain's type says
    `is_seller <=> seller_info != null`, and a row with the flag but no shop name would
    serialise as a seller with no seller_info -- a shape the client's type cannot hold.

    Calling this again updates the shop details, which is also how a shop gets renamed.
    """
    if not body.shop_name:
        raise api_error(400, "invalid_shop_name", "Shop name is required")

    current_user.is_seller = True
    current_user.shop_name = body.shop_name
    current_user.shop_phone = body.shop_phone
    db.commit()

    return user_out(current_user)


@router.post("/users/me/claim")
def claim_my_records(current_user: CurrentUser, db: DbSession) -> list[schemas.Customer]:
    """
    Take over every UNCLAIMED book entry written against this account's phone.

    This is the bridge between the two halves of the model: a shopkeeper writes a customer
    down as a row that only records what they know, and that row becomes a person's own
    record the moment somebody signs in holding the number. Without it a buyer who installs
    the app sees nothing -- the debt exists, but nothing connects it to them.

    THE PHONE COMES FROM THE TOKEN, never from a body. It is the entire authorisation:
    claiming is "these records are mine", and letting the caller name the number would let
    any account claim a stranger's debts (and, through /me/debts, read their history).
    Sign-in already proved possession of this number via OTP.

    Idempotent, and deliberately so: the client calls it on every sign-in. A second call
    matches nothing (the rows are no longer UNCLAIMED) and answers 200 with the records
    already held, which is the same answer as the first call.

    Only UNCLAIMED rows are touched. A row somebody else holds is never reassigned here --
    two accounts disagreeing over one number is a conflict this endpoint must not resolve
    by simply overwriting the loser.
    """
    # Already canonical: it was stored through to_stored at registration. Normalising
    # again would be harmless but would imply the column might hold something else.
    db.execute(
        update(models.Customer)
        .where(
            models.Customer.claim_status == "UNCLAIMED",
            models.Customer.phone == current_user.phone,
        )
        .values(claim_status="CLAIMED", claimed_by_user_id=current_user.user_id)
    )
    db.commit()

    claimed = db.execute(
        select(models.Customer).where(
            models.Customer.claimed_by_user_id == current_user.user_id
        )
    ).scalars().all()

    # balance_minor is 0 for every row: a claim links an identity and is seller-independent,
    # while a balance only means something within one shop's book. The buyer's real numbers
    # come from /me/debts, which sums them per shop.
    return [_claimed_customer_out(customer) for customer in claimed]


def _claimed_customer_out(customer: models.Customer) -> schemas.Customer:
    """A claimed record on the wire. Mirrors customers.py's _customer_out."""
    return schemas.Customer(
        customer_id=customer.customer_id,
        display_name=customer.display_name,
        phone=customer.phone,
        claim_status=customer.claim_status,
        claimed_by_user_id=customer.claimed_by_user_id,
        created_by_seller_id=customer.created_by_seller_id,
        balance_minor=0,
    )
