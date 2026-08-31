"""
The append-only ledger: one write endpoint, and reads derived from it.

This is the endpoint the whole offline-first design rests on. The device writes locally
first, queues the entry, and drains the queue whenever it can -- so the server sees
retries constantly, and every one of them must land on the SAME row. What makes deleting
a queue entry safe on the client is precisely that a replay here replays rather than
duplicates.
"""

from datetime import UTC, datetime

from fastapi import APIRouter, Header, Query, Response, status
from sqlalchemy import select

from .. import models, schemas
from ..baskets import write_basket
from ..deps import CurrentUser, DbSession
from ..ledger import balance_of, is_in_book
from ..security import api_error
from ..serializers import transaction_out, transactions_out

router = APIRouter(tags=["transactions"])

_VALID_TYPES = {"DEBT", "PAYMENT"}



def _describes_same_entry(
    existing: models.Transaction, body: schemas.TransactionCreate, seller_id: str
) -> bool:
    """
    Whether a replay carries the same entry as the stored one.

    Compares only the fields the client sends. created_at and the PGW settlement columns
    are deliberately excluded: the server owns them, so a replay could never match them
    and every retry would 409.
    """
    return (
        existing.seller_id == seller_id
        and existing.customer_id == body.customer_id
        and existing.amount_minor == body.amount_minor
        and existing.type == body.type
        and existing.description == body.description
    )


@router.post("/transactions", status_code=status.HTTP_201_CREATED)
def create_transaction(
    body: schemas.TransactionCreate,
    current_user: CurrentUser,
    db: DbSession,
    response: Response,
    idempotency_key: str = Header(..., alias="Idempotency-Key"),
) -> schemas.Transaction:
    """
    Append a ledger entry. Idempotent on `Idempotency-Key`, which equals transaction_id.

    Three outcomes, and the difference between them is the contract the client's outbox
    depends on:
      * new key                  -> 201, the entry is written
      * same key, same body      -> 200, the ORIGINAL entry is returned unchanged
      * same key, different body -> 409, never a silent overwrite

    The 409 matters because the ledger is append-only: if a second, different body could
    overwrite the first, a stale retry would rewrite history that the shopkeeper and the
    customer already agreed on.
    """
    if idempotency_key != body.transaction_id:
        # The header is not a second, independent id. Letting them differ would allow two
        # different entries to share a key, or one entry to be written under two.
        raise api_error(
            400, "idempotency_key_mismatch", "Idempotency-Key must equal transaction_id"
        )

    if body.type not in _VALID_TYPES:
        # An unrecognised type would land on neither side of the balance sum, so the
        # entry would exist while contributing nothing -- money silently disappearing.
        raise api_error(400, "invalid_type", "type must be DEBT or PAYMENT")

    existing = db.get(models.Transaction, body.transaction_id)
    if existing is not None:
        if not _describes_same_entry(existing, body, current_user.user_id):
            raise api_error(
                409,
                "idempotency_key_conflict",
                "This transaction_id was already used for a different entry",
            )
        # A replay. Return the original untouched; the client treats 200 and 201 alike.
        response.status_code = status.HTTP_200_OK
        return transaction_out(existing, db)

    customer = db.get(models.Customer, body.customer_id)
    if customer is None:
        raise api_error(404, "customer_not_found", "No such customer")

    # The customer must be in THIS seller's book. seller_id coming from the token already
    # stops writing into somebody else's ledger, but on its own it allows the mirror
    # image: booking an entry against a stranger -- a customer_id belonging to another
    # shop's book -- into your own. The row would then surface in that person's /me/debts
    # as a debt to a shop they have never visited.
    if not is_in_book(db, current_user.user_id, body.customer_id):
        raise api_error(403, "not_in_book", "This customer is not in your book")

    basket_id = write_basket(db, body.basket) if body.basket else None

    transaction = models.Transaction(
        transaction_id=body.transaction_id,
        # From the token, never the body. Trusting a body field here would let any signed
        # -in user append entries to somebody else's book.
        seller_id=current_user.user_id,
        customer_id=body.customer_id,
        amount_minor=body.amount_minor,
        type=body.type,
        description=body.description,
        basket_id=basket_id,
        settled_via_pgw=False,
        receipt_no=None,
        created_at=datetime.now(UTC),
    )
    db.add(transaction)

    # One commit for the entry and its basket together: a basket without its entry is an
    # orphan, and an entry pointing at a basket that was never written is a broken FK.
    db.commit()

    return transaction_out(transaction, db)


@router.get("/transactions")
def transaction_history(
    current_user: CurrentUser,
    db: DbSession,
    customer_id: str = Query(...),
) -> list[schemas.Transaction]:
    """
    One customer's history with THIS seller, newest first.

    Scoped by the token, so the same customer's entries at another shop are invisible
    here -- the history screen and the balance must agree about which book they describe.
    """
    rows = db.execute(
        select(models.Transaction)
        .where(
            models.Transaction.seller_id == current_user.user_id,
            models.Transaction.customer_id == customer_id,
        )
        .order_by(models.Transaction.created_at.desc())
    ).scalars().all()

    # Serialised as a batch so the baskets come back in one read rather than one per entry.
    return transactions_out(rows, db)


@router.get("/balances")
def customer_balance(
    current_user: CurrentUser,
    db: DbSession,
    customer_id: str = Query(...),
) -> schemas.Balance:
    """
    The (seller, customer) balance, summed from the ledger.

    Answers 0 rather than 404 for a customer with no entries: "owes nothing" is the
    correct reading of an empty book, and it keeps this endpoint agreeing with the sum
    the device computes locally over the same (empty) set of rows.
    """
    return schemas.Balance(
        seller_id=current_user.user_id,
        customer_id=customer_id,
        balance_minor=balance_of(db, current_user.user_id, customer_id),
        as_of=datetime.now(UTC),
    )
