"""
The seller's book.

Every read here is seller-scoped, and the scope comes from the bearer token rather than
from any request field. A customer row is shared -- the same person appears in several
shops' books -- but the BALANCE is per (seller, customer) pair, so the same row answers
with a different number depending on who is asking.
"""

import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Query, Response, status
from sqlalchemy import select

from .. import models, schemas
from ..breakdown import breakdown_for
from ..deps import CurrentUser, DbSession
from ..indexation import ensure_indexed, ensure_indexed_book
from ..ledger import balance_of, balances_by_customer, book_customer_ids
from ..phone import to_stored
from ..security import api_error

router = APIRouter(tags=["customers"])


def _customer_out(customer: models.Customer, balance_minor: int) -> schemas.Customer:
    return schemas.Customer(
        customer_id=customer.customer_id,
        display_name=customer.display_name,
        phone=customer.phone,
        claim_status=customer.claim_status,
        claimed_by_user_id=customer.claimed_by_user_id,
        created_by_seller_id=customer.created_by_seller_id,
        balance_minor=balance_minor,
    )


def _require_seller(user: models.User) -> None:
    """These endpoints are a shop's book; a buyer-only account has none."""
    if not user.is_seller:
        raise api_error(403, "not_a_seller", "This account is not a seller")


@router.post("/customers", status_code=status.HTTP_201_CREATED)
def create_customer(
    body: schemas.CustomerCreate, current_user: CurrentUser, db: DbSession
) -> schemas.Customer:
    """
    Open a book entry for a customer, typically one without the app.

    The row starts UNCLAIMED: it records what the shopkeeper knows, not a verified
    account. It becomes CLAIMED when somebody signs in with that phone and takes over
    the history -- which is why the phone is stored canonically even though nobody has
    proved they hold it yet.

    The 409 is scoped to THIS seller's book. A global unique phone would be wrong: two
    shops both knowing the same person is the normal case, and each keeps its own row.
    """
    phone = to_stored(body.phone)
    if phone is None:
        raise api_error(400, "invalid_phone", "Phone must be a valid Turkish mobile number")

    _require_seller(current_user)

    if not body.display_name:
        raise api_error(400, "invalid_name", "Display name is required")

    if book_customer_ids(db, current_user.user_id, phone=phone):
        raise api_error(409, "customer_exists", "This phone is already in your book")

    customer = models.Customer(
        customer_id=f"c_{uuid.uuid4().hex[:12]}",
        display_name=body.display_name,
        phone=phone,
        claim_status="UNCLAIMED",
        claimed_by_user_id=None,
        # Puts the row in this seller's book straight away. Without it a customer who has
        # been added but not yet charged has no ledger entries and so appears in no book.
        created_by_seller_id=current_user.user_id,
        created_at=datetime.now(UTC),
    )
    db.add(customer)
    db.commit()

    # A brand-new row has no ledger entries, so the balance is 0 by construction.
    return _customer_out(customer, 0)


@router.get("/customers")
def list_customers(current_user: CurrentUser, db: DbSession) -> list[schemas.Customer]:
    """
    This seller's customers, each with a derived balance.

    Balances come from ONE grouped query rather than a lookup per row: this is the shop's
    main screen, and a per-customer query would turn one screen into N round trips.

    A customer who was added but never charged has no ledger rows, so `.get(id, 0)` is
    what shows them at zero rather than dropping them off the screen.
    """
    _require_seller(current_user)

    customer_ids = book_customer_ids(db, current_user.user_id)
    if not customer_ids:
        return []

    # Bring every balance in this book up to date before reading any of them. Indexation
    # is written when a balance is READ (there is no scheduler), and the shop's main
    # screen is where a stale figure would be noticed first.
    ensure_indexed_book(db, current_user.user_id)

    balances = balances_by_customer(db, current_user.user_id)
    customers = db.execute(
        select(models.Customer).where(models.Customer.customer_id.in_(customer_ids))
    ).scalars().all()

    return [
        _customer_out(customer, balances.get(customer.customer_id, 0))
        for customer in sorted(customers, key=lambda c: c.display_name)
    ]


@router.get("/customers/breakdown")
def book_breakdown(
    current_user: CurrentUser,
    db: DbSession,
    customer_id: str | None = Query(None),
) -> schemas.LedgerBreakdown:
    """
    What this shop's receivables are MADE OF: principal, inflation, payments.

    The mirror of the buyer's breakdown, and the same three numbers -- but read the other
    way round. Where the customer sees what inflation ADDED to their debt, the shopkeeper
    sees what it SAVED them: money lent a year ago and repaid at face value would have come
    back worth less, and the indexation total is exactly the difference. That is the whole
    argument for the feature, and this is the endpoint that can state it in lira.

    `customer_id` narrows to one account; without it the answer covers the whole book.

    Declared before `/customers/{customer_id}` so the literal path wins the match --
    otherwise "breakdown" would be read as a customer id and this endpoint would be
    unreachable, exactly as noted for `lookup` below.
    """
    _require_seller(current_user)

    if customer_id is not None:
        ensure_indexed(db, current_user.user_id, customer_id)
    else:
        ensure_indexed_book(db, current_user.user_id)

    return breakdown_for(
        db,
        seller_id=current_user.user_id,
        customer_ids=[customer_id] if customer_id else None,
    )


@router.get("/customers/lookup")
def lookup_customer(
    current_user: CurrentUser,
    db: DbSession,
    phone: str = Query(...),
) -> schemas.Customer:
    """
    Find a customer by phone, so the shopkeeper does not retype a known person.

    Deliberately NOT limited to this seller's book: a person another shop already knows
    should be found, so the same human keeps one identity. What the balance reports is
    still seller-scoped, so a customer this seller has never charged answers 0 -- the
    caller learns the person exists, never what they owe elsewhere.

    Whether a hit means "mine" or "someone else's" is the CLIENT's decision
    (CustomerLookup); the server just returns the record.
    """
    # Declared before `/customers/{id}` in this file so the literal path wins the match --
    # otherwise "lookup" would be captured as an id and this endpoint would be unreachable.
    normalised = to_stored(phone)
    if normalised is None:
        raise api_error(400, "invalid_phone", "Phone must be a valid Turkish mobile number")

    _require_seller(current_user)

    customer = db.execute(
        select(models.Customer).where(models.Customer.phone == normalised).limit(1)
    ).scalar_one_or_none()
    if customer is None:
        raise api_error(404, "customer_not_found", "No customer with this phone")

    return _customer_out(
        customer, balance_of(db, current_user.user_id, customer.customer_id)
    )


@router.get("/customers/{customer_id}")
def get_customer(
    customer_id: str, current_user: CurrentUser, db: DbSession
) -> schemas.Customer:
    """One customer, with the balance they carry in THIS seller's book."""
    _require_seller(current_user)

    customer = db.get(models.Customer, customer_id)
    if customer is None:
        raise api_error(404, "customer_not_found", "No such customer")

    ensure_indexed(db, current_user.user_id, customer_id)

    return _customer_out(customer, balance_of(db, current_user.user_id, customer_id))
