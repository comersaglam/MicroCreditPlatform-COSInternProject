"""
What a lira was worth on a given day.

One read-only endpoint. The rates are reference data, not anyone's records: the same
series answers for every user, nothing here is scoped to the caller, and no client can
write to it.

Nothing on a device needs this to compute a balance -- indexation arrives as ledger rows
already denominated in lira, precisely so the phones never have to carry a rate table. It
is here for the screens that SHOW value over time: what a debt was worth in dollars the day
it was taken on, against what it is worth now.
"""

from datetime import UTC, date, datetime

from fastapi import APIRouter, Query

from .. import schemas
from ..deps import CurrentUser, DbSession
from ..fx import rate_at
from ..security import api_error

router = APIRouter(tags=["fx"])


@router.get("/fx-rates")
def fx_rate(
    current_user: CurrentUser,
    db: DbSession,
    as_of: date | None = Query(None),
) -> schemas.FxSnapshot:
    """
    The rates in force on a day; today's when `as_of` is omitted.

    Falls back to the most recent earlier reading, so a weekend or a holiday answers with
    Friday's rates rather than nothing. The response carries the date of the row that was
    actually used, not the date asked for -- reporting the requested day would claim a
    precision the series does not have.

    404 when the series does not reach the date at all. An unknown rate has to be
    distinguishable from a rate of zero, and a client that got zeros back would divide by
    them.
    """
    when = as_of or datetime.now(UTC).date()

    row = rate_at(db, when)
    if row is None:
        raise api_error(404, "rate_not_found", "No exchange rate on or before this date")

    return schemas.FxSnapshot(
        as_of=row.as_of,
        usd_minor=row.usd_minor,
        eur_minor=row.eur_minor,
        gold_minor=row.gold_minor,
    )
