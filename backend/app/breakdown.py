"""
What a balance is made of -- the same three components, read from either side.

A balance is one number, and one number cannot answer what both sides of the counter
actually want to know. The customer asks why the figure exceeds what they bought. The
shopkeeper asks whether extending credit cost them anything. Those turn out to be the same
decomposition read in opposite directions, which is why one function serves both endpoints
rather than two that could drift apart.

THE INVARIANT: principal + indexation - paid == outstanding, exactly, always. All four
figures come from the rows the balance itself sums, so the parts cannot fail to add up to
the whole. A breakdown that did not reconcile would be worse than showing none: it would
teach the reader that the ledger is approximate.
"""

from datetime import UTC, date, datetime

from sqlalchemy import BigInteger, case, func, select
from sqlalchemy.orm import Session
from sqlalchemy.sql.elements import ColumnElement

from . import models, schemas
from .fx import project_forward, rate_at


def _total_of(tx_type: str) -> ColumnElement[int]:
    """
    The sum of one entry type's amounts, as a column.

    Built the same way ledger.py builds _SIGNED_AMOUNT -- a CASE inside the SUM -- so all
    three totals come back from a single pass over the rows the balance already scans.
    COALESCE because a slice containing none of a type must report 0, not NULL.
    """
    return func.coalesce(
        func.sum(
            case((models.Transaction.type == tx_type, models.Transaction.amount_minor), else_=0)
        ),
        0,
    ).cast(BigInteger)


def breakdown_for(
    db: Session,
    *,
    seller_id: str | None = None,
    customer_ids: list[str] | None = None,
    today: date | None = None,
) -> schemas.LedgerBreakdown:
    """
    Decompose the balance over whichever slice of the ledger the caller names.

    The two selectors compose, which is what lets one function cover four questions:
      seller_id alone            -> everything this shop is owed
      seller_id + one customer   -> that customer's account at this shop
      customer_ids alone         -> everything this buyer owes, across every shop
      customer_ids + seller_id   -> this buyer's account at one shop

    Callers are responsible for having brought indexation up to date first (see
    indexation.ensure_indexed). This function reads; it never writes.
    """
    today = today or datetime.now(UTC).date()

    conditions = []
    if seller_id is not None:
        conditions.append(models.Transaction.seller_id == seller_id)
    if customer_ids is not None:
        if not customer_ids:
            # An empty selection is a legitimate answer, not a missing one: a buyer with no
            # customer records owes nothing anywhere.
            return schemas.LedgerBreakdown(
                principal_minor=0,
                indexation_minor=0,
                total_paid_minor=0,
                outstanding_minor=0,
            )
        conditions.append(models.Transaction.customer_id.in_(customer_ids))

    principal, indexation, paid = db.execute(
        select(
            _total_of("DEBT"), _total_of("INDEXATION"), _total_of("PAYMENT")
        ).where(*conditions)
    ).one()

    outstanding = principal + indexation - paid

    # The day the first entry landed: the point the "what was this worth then" comparison
    # is anchored to. Null for an empty slice, and so is everything derived from it.
    opened_at = db.execute(
        select(func.min(models.Transaction.created_at)).where(*conditions)
    ).scalar_one_or_none()

    return schemas.LedgerBreakdown(
        principal_minor=principal,
        indexation_minor=indexation,
        total_paid_minor=paid,
        outstanding_minor=outstanding,
        fx_at_open=_snapshot(db, opened_at.date()) if opened_at else None,
        fx_today=_snapshot(db, today),
        # Only worth projecting while something is still owed. A settled account's future
        # value is zero, and saying "in three months this will be 0,00" is noise.
        projected_3m_minor=(
            project_forward(db, outstanding, today, months=3) if outstanding > 0 else None
        ),
    )


def _snapshot(db: Session, when: date) -> schemas.FxSnapshot | None:
    """The rates in force on a day, or None when the series does not reach it."""
    row = rate_at(db, when)
    if row is None:
        return None

    return schemas.FxSnapshot(
        # The row's OWN date, not the date asked for: rate_at falls back to the most recent
        # earlier reading, and reporting the requested day would claim a precision the data
        # does not have.
        as_of=row.as_of,
        usd_minor=row.usd_minor,
        eur_minor=row.eur_minor,
        gold_minor=row.gold_minor,
    )
