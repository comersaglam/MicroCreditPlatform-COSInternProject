"""
The approval gate: nobody books an entry against the other party unilaterally.

A shopkeeper writing debt needs the customer's consent; a buyer declaring a payment needs
the shop's (they confirm receipt). Both directions run through here, which is why the row
carries `initiator_role` rather than assuming the seller always starts.

Two branches, decided by whether the counterparty has an account:
  * CLAIMED   -> a PENDING row; nothing reaches the ledger until they approve (201).
  * UNCLAIMED -> nobody can approve in-app, so the SMS-OTP path writes immediately (200).
"""

import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Response, status
from sqlalchemy import select

from .. import models, schemas
from ..deps import CurrentUser, DbSession
from ..security import api_error
from ..serializers import transaction_out

router = APIRouter(tags=["approvals"])

_VALID_TYPES = {"DEBT", "PAYMENT"}


def _approval_out(approval: models.Approval) -> schemas.Approval:
    return schemas.Approval(
        approval_id=approval.approval_id,
        initiator_user_id=approval.initiator_user_id,
        initiator_role=approval.initiator_role,
        target_user_id=approval.target_user_id,
        seller_id=approval.seller_id,
        shop_name=approval.shop_name,
        customer_id=approval.customer_id,
        amount_minor=approval.amount_minor,
        type=approval.type,
        description=approval.description,
        channel=approval.channel,
        status=approval.status,
        requested_at=approval.requested_at,
    )


def _write_entry(
    db: DbSession,
    seller_id: str,
    customer_id: str,
    amount_minor: int,
    tx_type: str,
    description: str | None,
) -> models.Transaction:
    """Append the agreed entry. The only path from an approval into the ledger."""
    transaction = models.Transaction(
        transaction_id=str(uuid.uuid4()),
        seller_id=seller_id,
        customer_id=customer_id,
        amount_minor=amount_minor,
        type=tx_type,
        description=description or "",
        basket_id=None,
        settled_via_pgw=False,
        receipt_no=None,
        created_at=datetime.now(UTC),
    )
    db.add(transaction)
    return transaction


@router.post("/approvals", status_code=status.HTTP_201_CREATED)
def send_for_approval(
    body: schemas.ApprovalCreate,
    current_user: CurrentUser,
    db: DbSession,
    response: Response,
):
    """
    Raise a request for the counterparty to approve.

    Answers 201 with a PENDING Approval when the counterparty holds an account, and 200
    with the written Transaction when they do not -- two shapes from one endpoint, which
    the contract specifies and the client branches on.
    """
    if body.type not in _VALID_TYPES:
        raise api_error(400, "invalid_type", "type must be DEBT or PAYMENT")

    if body.initiator_role not in {"BUYER", "SELLER"}:
        raise api_error(400, "invalid_role", "initiator_role must be BUYER or SELLER")

    # Plan §0.3. seller_id is in the body because a BUYER may start this, and then it
    # cannot come from the token. That makes it the one field an attacker could aim at,
    # so a seller-initiated request must prove the book is theirs -- otherwise anyone
    # could open requests against a stranger's ledger.
    if body.initiator_role == "SELLER" and body.seller_id != current_user.user_id:
        raise api_error(403, "forbidden", "Cannot initiate as a seller for another shop")

    customer = db.get(models.Customer, body.customer_id)
    if customer is None:
        raise api_error(404, "customer_not_found", "No such customer")

    seller = db.get(models.User, body.seller_id)
    if seller is None:
        raise api_error(404, "seller_not_found", "No such seller")

    # The COUNTERPARTY approves, never the initiator -- that is the whole point of the
    # gate. Which side that is depends on who started: a seller writing to their book
    # needs the customer's approval; a buyer paying needs the shop's. Deriving it from the
    # customer record alone would send a buyer-initiated payment back to the buyer.
    if body.initiator_role == "SELLER":
        approver_user_id = customer.claimed_by_user_id
    else:
        approver_user_id = body.seller_id
        # A buyer may only act on their OWN record; otherwise they could declare payments
        # into somebody else's book.
        if customer.claimed_by_user_id != current_user.user_id:
            raise api_error(403, "forbidden", "This customer record is not yours")

    if approver_user_id is None:
        # UNCLAIMED counterparty: nobody can tap approve, so this is the SMS-OTP branch.
        # Mocked as accepted, exactly as the client's mock does today, and written now.
        transaction = _write_entry(
            db,
            seller_id=body.seller_id,
            customer_id=body.customer_id,
            amount_minor=body.amount_minor,
            tx_type=body.type,
            description=body.description,
        )
        db.commit()
        response.status_code = status.HTTP_200_OK
        return transaction_out(transaction)

    approval = models.Approval(
        approval_id=str(uuid.uuid4()),
        # From the token, never the body: the row records who actually asked.
        initiator_user_id=current_user.user_id,
        initiator_role=body.initiator_role,
        target_user_id=approver_user_id,
        seller_id=body.seller_id,
        # Denormalised so the card can name the shop without a join. Falls back the same
        # way /me/debts does, since the field is non-nullable and the card needs a label.
        shop_name=seller.shop_name or seller.display_name or seller.phone,
        customer_id=body.customer_id,
        amount_minor=body.amount_minor,
        type=body.type,
        description=body.description,
        channel="APP_PUSH",
        status="PENDING",
        requested_at=datetime.now(UTC),
    )
    db.add(approval)
    db.commit()

    return _approval_out(approval)


@router.get("/approvals")
def pending_approvals(
    current_user: CurrentUser, db: DbSession
) -> list[schemas.Approval]:
    """
    What is waiting on THIS user's decision, newest first.

    Filtered to PENDING. Decided rows are kept for the audit trail, so the filter -- not a
    delete -- is what keeps the screen showing only what still needs an answer.
    """
    rows = db.execute(
        select(models.Approval)
        .where(
            models.Approval.target_user_id == current_user.user_id,
            models.Approval.status == "PENDING",
        )
        .order_by(models.Approval.requested_at.desc())
    ).scalars().all()

    return [_approval_out(approval) for approval in rows]


def _decide(db: DbSession, approval_id: str, user: models.User) -> models.Approval:
    """Load a pending approval this user is entitled to decide, or raise."""
    approval = db.get(models.Approval, approval_id)
    if approval is None:
        raise api_error(404, "approval_not_found", "No such approval")

    # Only the target decides. Without this the initiator could approve their own
    # request, which would make the gate decorative.
    if approval.target_user_id != user.user_id:
        raise api_error(403, "forbidden", "This approval is not addressed to you")

    if approval.status != "PENDING":
        # Already decided. A second approve would append the entry twice, so this is the
        # idempotency guard for a double tap or a retried request.
        raise api_error(409, "already_decided", f"Already {approval.status.lower()}")

    return approval


@router.post("/approvals/{approval_id}/approve")
def approve(
    approval_id: str, current_user: CurrentUser, db: DbSession
) -> schemas.Transaction:
    """
    Approve -> the entry is appended.

    This is the ONLY point on the approval path that writes to the ledger, and the write
    plus the status change share one commit: an entry without the status flipped could be
    approved again, and a flipped status without the entry would lose the debt entirely.
    """
    approval = _decide(db, approval_id, current_user)

    transaction = _write_entry(
        db,
        seller_id=approval.seller_id,
        # The record the request was raised against, not re-resolved here -- a buyer may
        # hold several records, and re-guessing could book the entry into another shop.
        customer_id=approval.customer_id,
        amount_minor=approval.amount_minor,
        tx_type=approval.type,
        description=approval.description,
    )
    approval.status = "APPROVED"
    db.commit()

    return transaction_out(transaction)


@router.post("/approvals/{approval_id}/reject", status_code=status.HTTP_204_NO_CONTENT)
def reject(approval_id: str, current_user: CurrentUser, db: DbSession) -> Response:
    """
    Reject -> nothing is written.

    The row is NOT deleted; its status changes. The user sees the same thing either way
    (the pending query filters it out), but the trail of what was asked and refused
    survives -- which is the point of an append-only design.
    """
    approval = _decide(db, approval_id, current_user)
    approval.status = "REJECTED"
    db.commit()

    return Response(status_code=status.HTTP_204_NO_CONTENT)
