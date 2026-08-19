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

from fastapi import APIRouter, Query, Response, status
from sqlalchemy import select

from .. import models, schemas
from ..deps import CurrentUser, DbSession
from ..pgw import KIND_COLLECT, KIND_RECEIPT, queue_job, receipt_order_body
from ..security import api_error
from ..serializers import transaction_out

router = APIRouter(tags=["approvals"])

_VALID_TYPES = {"DEBT", "PAYMENT"}
_VALID_STATUSES = {"PENDING", "APPROVED", "REJECTED"}

# Where the request was raised. Only PHONE leaves gateway work behind: a POS is already
# in front of the gateway and hands its own intent over.
ORIGIN_POS = "POS"
ORIGIN_PHONE = "PHONE"
_VALID_ORIGINS = {ORIGIN_POS, ORIGIN_PHONE}

# Which side of an account is being addressed. One account holds both, so "what is waiting
# on me" is two different inboxes depending on which hat the user is wearing.
ROLE_SELLER = "SELLER"
ROLE_BUYER = "BUYER"
_VALID_ROLES = {ROLE_SELLER, ROLE_BUYER}

# Not a status a row can hold -- it means "do not filter at all", which is why it is kept
# apart from the set above rather than added to it.
_STATUS_ALL = "ALL"


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
        origin=approval.origin,
        status=approval.status,
        requested_at=approval.requested_at,
        updated_at=approval.updated_at,
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

    if body.initiator_role not in _VALID_ROLES:
        raise api_error(400, "invalid_role", "initiator_role must be BUYER or SELLER")

    if body.origin not in _VALID_ORIGINS:
        raise api_error(400, "invalid_origin", "origin must be POS or PHONE")

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

    raised_at = datetime.now(UTC)
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
        # Kept from the request: by the time somebody decides this, the device that raised
        # it is long gone, and whether to queue gateway work depends on which it was.
        origin=body.origin,
        status="PENDING",
        requested_at=raised_at,
        # Same instant as requested_at: a row that has only just been raised has not
        # changed since. They diverge the moment somebody decides it.
        updated_at=raised_at,
    )
    db.add(approval)
    db.commit()

    return _approval_out(approval)


@router.get("/approvals")
def pending_approvals(
    current_user: CurrentUser,
    db: DbSession,
    status_filter: str | None = Query(default=None, alias="status"),
    as_role: str | None = Query(default=None, alias="role"),
    limit: int = Query(default=100, ge=1, le=500),
) -> list[schemas.Approval]:
    """
    What is waiting on THIS user's decision, newest first.

    Defaults to PENDING, which is what the inbox wants and what this endpoint has always
    returned -- decided rows are kept for the audit trail, so a filter rather than a delete
    is what keeps the screen showing only what still needs an answer. `status=ALL` opens
    that history up, since it was previously unreachable through the API at all.

    `role` narrows the inbox to the side of the user being asked, which matters because one
    account holds two. Answering as SELLER means the request is about YOUR shop's book;
    answering as BUYER means somebody else's shop wants to book something on you. A POS
    asks for SELLER only: the terminal is a shop tool, and its owner's personal debts at
    another shop have no business appearing on the counter -- worse, the till has no screen
    that could show the RESULT of approving one, so answering there looks like nothing
    happened. Omitted, both come back, which is what a phone wants.

    `limit` exists because clients POLL this: an unbounded list is fine when a screen asks
    once, and is not when it asks every fifteen seconds forever.
    """
    query = select(models.Approval).where(
        models.Approval.target_user_id == current_user.user_id
    )

    if as_role is not None:
        if as_role not in _VALID_ROLES:
            raise api_error(400, "invalid_role", "role must be SELLER or BUYER")
        # Which side you are on is decided by whether the book in question is YOURS -- not
        # by initiator_role, which records who ASKED. Both of today's lines are raised by a
        # SELLER, so filtering on that would return the same rows for either value.
        is_my_book = models.Approval.seller_id == current_user.user_id
        query = query.where(is_my_book if as_role == ROLE_SELLER else ~is_my_book)

    if status_filter is None:
        query = query.where(models.Approval.status == "PENDING")
    elif status_filter != _STATUS_ALL:
        if status_filter not in _VALID_STATUSES:
            raise api_error(
                400,
                "invalid_status",
                "status must be PENDING, APPROVED, REJECTED or ALL",
            )
        query = query.where(models.Approval.status == status_filter)

    rows = db.execute(
        query.order_by(models.Approval.requested_at.desc()).limit(limit)
    ).scalars().all()

    return [_approval_out(approval) for approval in rows]


@router.get("/approvals/{approval_id}")
def approval_by_id(
    approval_id: str, current_user: CurrentUser, db: DbSession
) -> schemas.Approval:
    """
    One approval, for the side WAITING on it.

    The inbox endpoint cannot answer this. It returns what is addressed to you, and the
    party that raised a request is by definition not the one who decides it -- so a till
    holding a sale open has no way to learn the customer's answer from that list.

    Readable by either end, and by nobody else: the initiator needs it to close the sale,
    the target already sees it in their inbox, and a third party has no business knowing
    what somebody owes.
    """
    approval = db.get(models.Approval, approval_id)
    if approval is None:
        raise api_error(404, "approval_not_found", "No such approval")

    if current_user.user_id not in {approval.initiator_user_id, approval.target_user_id}:
        raise api_error(403, "forbidden", "This approval is not yours")

    return _approval_out(approval)


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
    approval.updated_at = datetime.now(UTC)

    _queue_terminal_work(db, approval, transaction)

    db.commit()

    return transaction_out(transaction)


def _queue_terminal_work(
    db: DbSession, approval: models.Approval, transaction: models.Transaction
) -> None:
    """
    Leave the gateway half of this approval for the seller's terminal.

    Only approvals raised on a PHONE reach here with work to do. A sale that started at
    the terminal is already standing in front of the gateway and hands the intent over
    itself; queueing a job for it would fire the same intent twice.

    Which job depends on what was agreed, and the two are not interchangeable:
      * DEBT approved (path 3)    -> RECEIPT, a slip for money already booked.
      * PAYMENT approved (path 5) -> COLLECT, the gateway opens to TAKE money by card.

    Added to the caller's session, committed by them: the job and the ledger entry that
    justifies it must land together or not at all.
    """
    if approval.origin != ORIGIN_PHONE:
        return

    if approval.type == "DEBT":
        queue_job(
            db,
            seller_id=approval.seller_id,
            kind=KIND_RECEIPT,
            customer_id=approval.customer_id,
            amount_minor=approval.amount_minor,
            transaction_id=transaction.transaction_id,
            order_body=receipt_order_body(approval.amount_minor),
        )
    else:
        # No orderBody: the terminal opens the gateway for a card payment rather than
        # printing a slip, and the gateway builds its own basket for that.
        queue_job(
            db,
            seller_id=approval.seller_id,
            kind=KIND_COLLECT,
            customer_id=approval.customer_id,
            amount_minor=approval.amount_minor,
            transaction_id=transaction.transaction_id,
        )


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
    approval.updated_at = datetime.now(UTC)
    db.commit()

    return Response(status_code=status.HTTP_204_NO_CONTENT)
