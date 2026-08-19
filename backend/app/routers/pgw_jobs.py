"""
The terminal's inbox: work the server left for it to hand to the payment gateway.

This exists because the arrow only points one way. A phone -- the seller's or the
buyer's -- can start a sale, but only the POS can talk to the gateway, and the server
cannot call the POS. So the server writes down what needs doing and the terminal comes
and asks. Two endpoints are enough: fetch what is waiting, and say it was delivered.

The ack is the important half. Fetching is safe to repeat; delivering is not, because on
the other side of it a receipt gets printed. A job therefore stays PENDING until the
terminal explicitly closes it, which makes the guarantee at-least-once rather than
at-most-once -- the right way round when the alternative is a slip that never prints.
"""

from datetime import UTC, datetime

from fastapi import APIRouter, Query, status
from sqlalchemy import select

from .. import models, schemas
from ..deps import CurrentUser, DbSession
from ..ledger import is_in_book
from ..pgw import KIND_COLLECT, STATUS_DELIVERED, STATUS_PENDING, queue_job
from ..security import api_error

router = APIRouter(tags=["pgw-jobs"])

# What a client may ask for directly. RECEIPT is server-only -- it is queued alongside the
# ledger entry it accompanies, never on request.
_CREATABLE_KINDS = {KIND_COLLECT}


def _job_out(job: models.PgwJob) -> schemas.PgwJob:
    return schemas.PgwJob(
        job_id=job.job_id,
        seller_id=job.seller_id,
        kind=job.kind,
        transaction_id=job.transaction_id,
        customer_id=job.customer_id,
        amount_minor=job.amount_minor,
        order_body=job.order_body,
        status=job.status,
        created_at=job.created_at,
        updated_at=job.updated_at,
    )


@router.post("/pgw-jobs", status_code=status.HTTP_201_CREATED)
def create_job(
    body: schemas.PgwJobCreate, current_user: CurrentUser, db: DbSession
) -> schemas.PgwJob:
    """
    A seller sends work to their OWN terminal (path 4: collecting payment from the phone).

    No approval gate, and that is deliberate rather than an omission. The gate exists so
    nobody books an entry AGAINST the other party unilaterally; here the seller is asking
    to be paid at their own till, and the customer consents by handing over a card. What
    would be unsafe is the reverse direction, which this endpoint does not offer: the
    terminal is addressed by the token, so nobody can queue work onto another shop's till.

    Nothing is written to the ledger. The entry appears when the gateway actually takes
    the money -- queueing the intent is not evidence that anyone paid.
    """
    if not current_user.is_seller:
        raise api_error(403, "not_a_seller", "This account is not a seller")

    if body.kind not in _CREATABLE_KINDS:
        # RECEIPT is not offered here: a slip accompanies an entry that is already in the
        # ledger, and this endpoint writes none. Letting a client ask for one would print
        # a receipt for a debt nobody recorded.
        raise api_error(400, "invalid_kind", "kind must be COLLECT")

    if not is_in_book(db, current_user.user_id, body.customer_id):
        raise api_error(403, "not_in_book", "This customer is not in your book")

    job = queue_job(
        db,
        seller_id=current_user.user_id,
        kind=body.kind,
        customer_id=body.customer_id,
        amount_minor=body.amount_minor,
    )
    db.commit()

    return _job_out(job)


@router.get("/pgw-jobs")
def pending_jobs(
    current_user: CurrentUser,
    db: DbSession,
    limit: int = Query(default=50, ge=1, le=200),
) -> list[schemas.PgwJob]:
    """
    What is waiting for THIS terminal, oldest first.

    Oldest first, unlike the approvals inbox: that one is a list somebody reads, so the
    newest matters most, while this is a queue somebody works through, and receipts should
    reach the gateway in the order the sales happened.

    Scoped by the token. A job names the shop that must deliver it, and no request field
    is trusted to say which shop is asking.
    """
    if not current_user.is_seller:
        # A buyer-only account has no terminal. Answering 403 rather than an empty list
        # keeps this consistent with the book endpoints, and the client already treats
        # `not_a_seller` as "nothing to do", not as an error worth showing.
        raise api_error(403, "not_a_seller", "This account is not a seller")

    rows = db.execute(
        select(models.PgwJob)
        .where(
            models.PgwJob.seller_id == current_user.user_id,
            models.PgwJob.status == STATUS_PENDING,
        )
        .order_by(models.PgwJob.created_at)
        .limit(limit)
    ).scalars().all()

    return [_job_out(job) for job in rows]


@router.post("/pgw-jobs/{job_id}/ack")
def ack_job(
    job_id: str, current_user: CurrentUser, db: DbSession
) -> schemas.PgwJob:
    """
    Mark a job delivered to the gateway.

    Idempotent on purpose: acking an already-delivered job answers 200 with the row, not
    a conflict. The terminal fires the intent first and acks second, so a lost response
    leaves it holding a job it HAS delivered -- and the only safe thing it can do then is
    retry the ack. Making that retry an error would leave the job PENDING forever and the
    receipt printing on every poll.
    """
    job = db.get(models.PgwJob, job_id)
    if job is None:
        raise api_error(404, "job_not_found", "No such job")

    if job.seller_id != current_user.user_id:
        raise api_error(403, "forbidden", "This job is not addressed to your terminal")

    if job.status != STATUS_DELIVERED:
        job.status = STATUS_DELIVERED
        job.updated_at = datetime.now(UTC)
        db.commit()

    return _job_out(job)
