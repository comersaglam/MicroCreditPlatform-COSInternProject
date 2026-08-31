"""
Currency and inflation arithmetic -- the one place a lira is compared across time.

Everything here answers a version of the same question: this amount, on that day, is worth
what today? The ledger records amounts and dates; this module supplies the second half.

TWO RULES, both about money never becoming a float:

  1. Amounts stay `int` kuruş from end to end. The ratios in between are Fractions, not
     floats, so a value is exact right up to the single point where it becomes an amount
     again.

  2. That point rounds half-up, never truncates. Truncation looks harmless on one figure
     and is not: indexation compounds month over month, and a floor applied twelve times
     drifts steadily in the payer's favour until the ledger no longer matches what the
     index actually did.

WHY THE INDEX HAS NO UNITS: only RATIOS of cpi_index are ever taken (`cpi_ratio`), so the
base period it is anchored to cancels out. A series starting at 100 and one starting at
1000 give the same answer, which means the seed can move without any caller changing.
"""

from datetime import date, timedelta
from fractions import Fraction

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import models

# How far back `rate_at` will look for a usable row before giving up. Rates are seeded
# daily, so a gap this wide means the series is broken rather than merely sparse -- and a
# silent answer drawn from a year-old row would be worse than no answer.
_MAX_STALENESS = timedelta(days=45)


def month_start(when: date) -> date:
    """The first of `when`'s month. Indexation lands on month boundaries, not on the day
    a balance happened to be read."""
    return when.replace(day=1)


def next_month(when: date) -> date:
    """The first of the month after `when`'s. Written out rather than adding 31 days,
    which would skip February."""
    return date(when.year + (when.month // 12), (when.month % 12) + 1, 1)


def months_before(when: date, months: int) -> date:
    """The first of the month `months` back from `when`'s."""
    total = (when.year * 12 + when.month - 1) - months
    return date(total // 12, (total % 12) + 1, 1)


def rate_at(db: Session, when: date) -> models.FxRate | None:
    """
    The rate in force on `when`: that day's row, or the most recent one before it.

    Falls BACKWARD, never forward. A rate that had not been published yet cannot have been
    in force, and reaching ahead would let a debt be valued at a number that did not exist
    when it was taken on.

    Returns None past `_MAX_STALENESS`, and for any date before the series begins. Callers
    treat that as "not knowable" and leave the amount alone -- see `cpi_ratio`.
    """
    row = db.execute(
        select(models.FxRate)
        .where(models.FxRate.as_of <= when)
        .order_by(models.FxRate.as_of.desc())
        .limit(1)
    ).scalar_one_or_none()

    if row is None or when - row.as_of > _MAX_STALENESS:
        return None
    return row


def cpi_ratio(db: Session, then: date, now: date) -> Fraction | None:
    """
    How much prices rose between the two dates, as `cpi_now / cpi_then`.

    A Fraction rather than a float: this value gets multiplied by an amount and compounded
    across months, and binary rounding error at that scale is money.

    Returns None when either end of the span has no rate to stand on -- the honest answer
    when the index is unknown, and one that callers must not confuse with "no inflation".
    Returning 1 instead would silently claim prices held flat.

    A ratio below 1 (deflation) is returned as-is. It is the index's job to say what
    happened, not this function's to decide it is implausible.
    """
    start = rate_at(db, then)
    end = rate_at(db, now)

    if start is None or end is None or start.cpi_index <= 0:
        return None

    return Fraction(end.cpi_index, start.cpi_index)


def apply_ratio(amount_minor: int, ratio: Fraction) -> int:
    """
    Scale an amount by a ratio and land back on whole kuruş, rounding half-up.

    Half-up rather than Python's default half-to-even: the latter is right for long
    statistical sums and wrong here, where each result is a figure a person is asked to
    pay and the same input must always produce the same figure.
    """
    scaled = amount_minor * ratio
    # Fraction arithmetic keeps this exact; adding a half and flooring is half-up without
    # ever touching a float.
    return int((scaled + Fraction(1, 2)).__floor__())


def inflation_delta(
    db: Session, amount_minor: int, then: date, now: date
) -> int | None:
    """
    What `amount_minor`, taken on at `then`, has lost to inflation by `now`.

    The DIFFERENCE, not the grown total: this is the figure that becomes an INDEXATION
    ledger row, and that row records the adjustment rather than restating the debt.

    None when the span is not measurable (see `cpi_ratio`). Negative under deflation, and
    callers decide what to do with that -- indexation.py declines to write such a row,
    since amount_minor on a ledger entry is always positive.
    """
    ratio = cpi_ratio(db, then, now)
    if ratio is None:
        return None

    return apply_ratio(amount_minor, ratio) - amount_minor


def in_currency(amount_minor: int, unit_price_minor: int) -> Fraction | None:
    """
    How many units of a currency an amount buys, given what one unit costs.

    Returns a Fraction of UNITS, not minor units: "3.2 dollars" is a quantity to display,
    never an amount to store, and the type keeps the two from being confused.
    """
    if unit_price_minor <= 0:
        return None
    return Fraction(amount_minor, unit_price_minor)


def monthly_index(db: Session, months: list[date]) -> dict[date, int]:
    """
    The index reading at each of the given month starts, in one query.

    Batched because indexation walks every month since a debt was opened, and asking per
    month would turn one balance read into a dozen round trips.

    Month starts with no row of their own fall back to the nearest earlier reading, which
    is `rate_at`'s rule applied in bulk. Months with nothing at all behind them are absent
    from the result rather than defaulted; the caller must be able to tell the difference.
    """
    if not months:
        return {}

    rows = db.execute(
        select(models.FxRate.as_of, models.FxRate.cpi_index)
        .where(models.FxRate.as_of <= max(months))
        .order_by(models.FxRate.as_of)
    ).all()

    if not rows:
        return {}

    out: dict[date, int] = {}
    position = 0
    latest: tuple[date, int] | None = None

    # One pass over both lists: months are asked for in order, so the row cursor only ever
    # moves forward.
    for month in sorted(months):
        while position < len(rows) and rows[position][0] <= month:
            latest = rows[position]
            position += 1

        if latest is not None and month - latest[0] <= _MAX_STALENESS:
            out[month] = latest[1]

    return out


def average_monthly_growth(db: Session, as_of: date, months: int = 12) -> Fraction | None:
    """
    The mean monthly rise in the index over the trailing window, as a per-month ratio.

    Geometric, not arithmetic: growth compounds, so the average that matters is the one
    that reproduces the total when applied `months` times.

    Feeds `project_forward`, which is the only thing here that looks ahead -- and does so
    on the assumption that the recent past continues, which is a projection and is labelled
    as one wherever it reaches a screen.
    """
    total = cpi_ratio(db, months_before(as_of, months), as_of)
    if total is None or total <= 0:
        return None

    # The per-month ratio whose `months`-fold product is `total`. Computed as a rational
    # approximation of the real root: exact roots of a Fraction are usually irrational, and
    # a limited denominator keeps later multiplication cheap without losing anything a
    # projection could meaningfully use.
    per_month = Fraction(float(total) ** (1 / months)).limit_denominator(1_000_000)
    return per_month


def project_forward(
    db: Session, amount_minor: int, as_of: date, months: int
) -> int | None:
    """
    What this amount would grow to in `months`, if prices keep moving as they have.

    A forecast, not a fact. Nothing writes this to the ledger -- it exists so a customer
    can see what waiting costs, and a shopkeeper what patience costs them.
    """
    growth = average_monthly_growth(db, as_of)
    if growth is None:
        return None

    return apply_ratio(amount_minor, growth**months)
