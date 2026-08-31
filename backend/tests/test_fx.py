"""
Currency and inflation arithmetic.

Two things are pinned here. The rounding, because indexation compounds and a rule that
drifts by a kuruş a month is a rule that drifts by a lira a year. And the None returns,
because "the index is unknown for this span" must stay distinguishable from "prices held
flat" -- the first is missing data, the second is a claim about the world.
"""

from datetime import date
from fractions import Fraction

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app import fx, models
from app.db import Base


@pytest.fixture
def db():
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)()

    # One row per month of 2026 at a clean 3%, so ratios are checkable by hand.
    cpi = 100_000
    for month in range(1, 13):
        session.add(
            models.FxRate(
                as_of=date(2026, month, 1),
                usd_minor=3_200 + month * 100,
                eur_minor=3_500,
                gold_minor=400_000,
                cpi_index=cpi,
            )
        )
        cpi = round(cpi * 1.03)
    session.commit()

    yield session
    session.close()


# --- month arithmetic -------------------------------------------------------------

def test_month_start_drops_the_day() -> None:
    assert fx.month_start(date(2026, 8, 31)) == date(2026, 8, 1)


@pytest.mark.parametrize(
    "given,expected",
    [
        (date(2026, 1, 31), date(2026, 2, 1)),   # February has no 31st to land on
        (date(2026, 12, 5), date(2027, 1, 1)),   # year rolls over
        (date(2026, 11, 1), date(2026, 12, 1)),
    ],
)
def test_next_month(given: date, expected: date) -> None:
    # Written out rather than adding 31 days, which would skip February entirely.
    assert fx.next_month(given) == expected


@pytest.mark.parametrize(
    "given,months,expected",
    [
        (date(2026, 8, 31), 12, date(2025, 8, 1)),
        (date(2026, 1, 15), 1, date(2025, 12, 1)),  # year rolls back
        (date(2026, 3, 1), 3, date(2025, 12, 1)),
        (date(2026, 8, 15), 0, date(2026, 8, 1)),
    ],
)
def test_months_before(given: date, months: int, expected: date) -> None:
    assert fx.months_before(given, months) == expected


# --- rate lookup ------------------------------------------------------------------

def test_rate_at_finds_the_exact_day(db) -> None:
    assert fx.rate_at(db, date(2026, 3, 1)).cpi_index == 106_090


def test_rate_at_falls_backward(db) -> None:
    # A rate that had not been published yet cannot have been in force, so the lookup
    # reaches back, never forward.
    assert fx.rate_at(db, date(2026, 3, 20)).as_of == date(2026, 3, 1)


def test_rate_at_before_the_series_is_unknown(db) -> None:
    assert fx.rate_at(db, date(2025, 6, 1)) is None


def test_rate_at_refuses_a_stale_row(db) -> None:
    # A gap this wide means the series is broken rather than merely sparse, and an answer
    # drawn from a year-old row would be worse than no answer.
    assert fx.rate_at(db, date(2027, 6, 1)) is None


# --- ratios -----------------------------------------------------------------------

def test_cpi_ratio_is_exact(db) -> None:
    # A Fraction, not a float: this gets compounded across months and binary rounding
    # error at that scale is money.
    ratio = fx.cpi_ratio(db, date(2026, 1, 1), date(2026, 2, 1))
    assert isinstance(ratio, Fraction)
    assert ratio == Fraction(103_000, 100_000)


def test_cpi_ratio_is_unknown_outside_the_series(db) -> None:
    # Not 1. Returning 1 would silently claim prices held flat over a span we know nothing
    # about, and indexation would then write nothing while believing it had checked.
    assert fx.cpi_ratio(db, date(2024, 1, 1), date(2026, 6, 1)) is None


def test_the_index_base_period_cancels_out(db) -> None:
    # Only ratios are ever taken, so a series anchored at 100 and one anchored at 100000
    # must give the same answer. This is what lets the seed's base move freely.
    db.execute(models.FxRate.__table__.delete())
    for month, value in ((1, 100), (2, 103)):
        db.add(
            models.FxRate(
                as_of=date(2026, month, 1),
                usd_minor=3_200,
                eur_minor=3_500,
                gold_minor=400_000,
                cpi_index=value,
            )
        )
    db.commit()

    assert fx.cpi_ratio(db, date(2026, 1, 1), date(2026, 2, 1)) == Fraction(103, 100)


# --- rounding ---------------------------------------------------------------------

@pytest.mark.parametrize(
    "amount,ratio,expected",
    [
        (10_000, Fraction(103, 100), 10_300),
        (1, Fraction(3, 2), 2),       # 1.5 -> 2
        (1, Fraction(5, 2), 3),       # 2.5 -> 3, where banker's rounding would give 2
        (0, Fraction(103, 100), 0),
    ],
)
def test_apply_ratio_rounds_half_up(amount: int, ratio: Fraction, expected: int) -> None:
    # Half-up rather than Python's default half-to-even: each result is a figure a person
    # is asked to pay, and the same input must always produce the same figure.
    assert fx.apply_ratio(amount, ratio) == expected


def test_repeated_rounding_does_not_drift(db) -> None:
    # Twelve floors in a row would land near 14237 -- roughly 20 kuruş adrift on a hundred
    # lira, and steadily in the payer's favour. Half-up tracks the real curve.
    amount = 10_000
    for _ in range(12):
        amount = fx.apply_ratio(amount, Fraction(103, 100))

    assert amount == 14_257  # 10000 * 1.03**12 = 14257.6


# --- deltas and conversions -------------------------------------------------------

def test_inflation_delta_is_the_difference_not_the_total(db) -> None:
    # This figure becomes an INDEXATION row, which records the adjustment rather than
    # restating the debt.
    delta = fx.inflation_delta(db, 10_000, date(2026, 1, 1), date(2026, 2, 1))
    assert delta == 300


def test_inflation_delta_is_unknown_outside_the_series(db) -> None:
    assert fx.inflation_delta(db, 10_000, date(2024, 1, 1), date(2026, 2, 1)) is None


def test_in_currency_returns_units(db) -> None:
    # Units, not minor units: "3.2 dollars" is a quantity to display, never an amount to
    # store, and the Fraction keeps the two from being confused.
    assert fx.in_currency(10_000, 3_300) == Fraction(10_000, 3_300)


def test_in_currency_refuses_a_zero_price(db) -> None:
    assert fx.in_currency(10_000, 0) is None


# --- batched index and projection -------------------------------------------------

def test_monthly_index_batches_the_lookup(db) -> None:
    months = [date(2026, m, 1) for m in (1, 3, 6)]
    assert fx.monthly_index(db, months) == {
        date(2026, 1, 1): 100_000,
        date(2026, 3, 1): 106_090,
        date(2026, 6, 1): 115_928,
    }


def test_monthly_index_omits_months_it_cannot_answer(db) -> None:
    # Absent rather than defaulted, so the caller can tell the difference between "no
    # inflation" and "no data".
    result = fx.monthly_index(db, [date(2024, 1, 1), date(2026, 3, 1)])
    assert date(2024, 1, 1) not in result
    assert date(2026, 3, 1) in result


def test_monthly_index_falls_back_to_the_nearest_earlier_reading(db) -> None:
    # rate_at's rule, applied in bulk.
    assert fx.monthly_index(db, [date(2026, 3, 15)])[date(2026, 3, 15)] == 106_090


def test_project_forward_extends_the_recent_trend(db) -> None:
    # A forecast, not a fact -- nothing writes this to the ledger. At a steady 3% the next
    # three months should land near 1.03**3.
    projected = fx.project_forward(db, 10_000, date(2026, 12, 1), months=3)
    assert projected is not None
    assert 10_800 < projected < 11_100


def test_project_forward_uses_a_shorter_window_when_history_is_thin(db) -> None:
    # The series starts in January 2026, so a full twelve-month window is not available
    # anywhere in it. Refusing on that basis would blank the projection on every account
    # older than the rate data; nine months of history is worth projecting from.
    projected = fx.project_forward(db, 10_000, date(2026, 9, 1), months=3)
    assert projected is not None
    assert projected > 10_000


def test_project_forward_is_unknown_without_history(db) -> None:
    db.execute(models.FxRate.__table__.delete())
    db.commit()

    assert fx.project_forward(db, 10_000, date(2026, 12, 1), months=3) is None
