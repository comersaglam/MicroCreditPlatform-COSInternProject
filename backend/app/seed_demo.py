"""
Demo data: a year of a plausible neighbourhood, on top of the ordinary seed.

WHY THIS IS NOT `seed.py`
`seed.py` is what the tests assert against -- ~190 of them pin its balances to the kuruş,
because those figures are the free parity check between what the server derives and what
the device shows. Growing it would mean rewriting those assertions, and the moment they
are rewritten to match whatever the code now produces, they stop being a check.

So this is additive and separate. It CALLS seed() first, which means every demo account
still works exactly as documented in docs/test-hesaplari.md: u_owner is still
+905554443322, c1 still owes 40,00, and the device scenarios written against them still
run. Everything below is extra shops and extra customers around that fixed core.

WHAT IT IS FOR
Screens that plot. One shop with fifteen entries makes a chart with one bar in it; the
admin panel's six tabs and the insights screens need a year of movement before they show
anything worth looking at. The volumes here are chosen for that: enough to have a shape,
small enough to seed in a second.

WHAT IT DELIBERATELY DOES NOT WRITE
Indexation rows. Those are the server's to mint, lazily, the first time a balance is read
(see indexation.py) -- so leaving them out means the demo also exercises the indexer
itself rather than a hand-built imitation of it. Open a customer in the app and the months
appear.

RUN IT
    docker compose exec api python -m app.seed_demo

Idempotent by the same rule as seed(): it checks whether the extra data is already there
and does nothing if so, because Alembic runs on every container start and a second pass
would double every balance.
"""

import random
from datetime import UTC, date, datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import models
from .db import SessionLocal
from .ledger import _BALANCE
from .seed import is_empty, seed

# A fixed seed, so the demo is the same story every time it is told. A shopkeeper who
# noticed a chart change shape between two runs would reasonably stop trusting the chart.
_RANDOM = random.Random(20260831)

# The window the demo covers. Rates run 18 months so that a debt opened at the very start
# still has an index reading behind it; entries run 12, so every one of them sits inside
# the rate series with room to spare.
_TODAY = date(2026, 8, 31)
_RATES_FROM = date(2025, 3, 1)
_ENTRIES_FROM = date(2025, 9, 1)

_SHOPS = [
    ("Yıldız Bakkal", "Kemal Yıldız"),
    ("Deniz Market", "Deniz Arslan"),
    ("Anadolu Şarküteri", "Servet Anadol"),
    ("Gül Manav", "Gülsüm Ekinci"),
    ("Köşe Fırın", "Necmi Köse"),
    ("Umut Kuruyemiş", "Umut Bilge"),
]

_FIRST_NAMES = [
    "Ali", "Ayşe", "Burak", "Ceren", "Deniz", "Ebru", "Fatih", "Gizem", "Hakan",
    "Irmak", "İlker", "Jale", "Kerem", "Leyla", "Murat", "Nalan", "Osman", "Pınar",
    "Rüya", "Selim", "Tuğçe", "Ufuk", "Vedat", "Yasemin", "Zeynep", "Berk", "Cansu",
    "Doruk", "Esra", "Ferhat", "Gökhan", "Hande",
]
_SURNAMES = [
    "Yılmaz", "Kaya", "Demir", "Şahin", "Çelik", "Yıldırım", "Aydın", "Öztürk",
    "Arslan", "Doğan", "Kılıç", "Aslan", "Çetin", "Kara", "Koç", "Kurt",
]

# What a shop sells, and roughly what it costs. The label matters more than the price:
# these strings end up on the basket screen and in the chatbot's answers.
_ITEMS = [
    ("Ekmek, süt", 4_000, 9_000),
    ("Market alışverişi", 12_000, 45_000),
    ("Peynir, zeytin", 8_000, 22_000),
    ("Deterjan, temizlik", 6_500, 19_000),
    ("Meyve sebze", 5_000, 16_000),
    ("Kahvaltılık", 7_000, 20_000),
    ("Çay, şeker", 3_500, 11_000),
    ("Kuruyemiş", 9_000, 28_000),
    ("Et, tavuk", 18_000, 60_000),
    ("Toplu alışveriş", 25_000, 90_000),
]

# Months when a neighbourhood spends more: Ramadan and the run-up to it, then the new
# school year. Without this the yearly chart is a flat band of noise and says nothing.
_SEASONAL = {1: 1.15, 2: 1.30, 3: 1.35, 4: 1.10, 9: 1.25, 12: 1.20}

_PAID_DESCRIPTIONS = ["Nakit ödeme", "Kısmi ödeme", "Kart ile ödeme", "Hesap kapatma"]

# Endpoints the fake traffic is spread over, with rough weights. Reads dominate, as they
# do in the real app: the phones poll.
_TRAFFIC = [
    ("GET /customers", 30),
    ("GET /transactions", 22),
    ("GET /me/debts", 18),
    ("GET /approvals", 14),
    ("POST /transactions", 6),
    ("POST /approvals", 4),
    ("GET /pgw-jobs", 3),
    ("POST /auth/otp/verify", 2),
    ("POST /customers", 1),
]


def _phone(index: int) -> str:
    """A distinct, well-formed mobile number. Starts high to avoid the seed's own range."""
    return f"+9053{index:08d}"


def _at(day: date, hour: int = 9, minute: int = 0) -> datetime:
    return datetime(day.year, day.month, day.day, hour, minute, tzinfo=UTC)


def already_seeded(db: Session) -> bool:
    """True once the demo shops exist -- the marker that this ran before."""
    return (
        db.execute(
            select(func.count()).select_from(models.User).where(
                models.User.user_id.like("dseller_%")
            )
        ).scalar_one()
        > 0
    )


def seed_demo(db: Session) -> None:
    """Add the demo population. Safe to call on a database that already has seed()."""
    if is_empty(db):
        seed(db)

    if already_seeded(db):
        return

    _fx_series(db)
    sellers = _sellers(db)
    buyers = _buyers(db)
    db.flush()

    customers = _customers(db, sellers, buyers)
    db.flush()

    _entries(db, customers)
    _traffic(db, sellers + buyers)
    db.commit()


def _fx_series(db: Session) -> None:
    """
    Daily rates across the whole window.

    A plausible year rather than the real one: the lira slides against the dollar, gold
    outruns both, and the index rises about 2.5% a month with a little variation. What the
    demo needs is a series with a believable SHAPE -- see deferred.md §L.4 on why the
    numbers themselves are invented.

    Returns early if rates already exist. `as_of` is the primary key, so a second pass
    over a populated table raises rather than skipping -- and it raised for real, on a
    database whose reset had left fx_rates behind.
    """
    if db.execute(select(func.count()).select_from(models.FxRate)).scalar_one() > 0:
        return

    # Lira per unit, as a float, because the series is grown by multiplication and only
    # rounded to kuruş on the way into the row.
    #
    # ⚠️ These were `3_180_00 / 100` and read as "31,80" at a glance -- the underscore
    # grouping looks like a kuruş literal. It is not: 3_180_00 is 318000, so the series ran
    # at 3.180 lira to the dollar, a hundred times high, and had done since Turn 43. Nothing
    # caught it because nothing READ it; the first screen to show a rate (Turn 45's basket
    # detail) rendered "0,0 USD" for a 50,00 TL entry and that is how it surfaced. Same
    # shape as §J.7: a bug can sit in data no one consumes.
    usd, eur, gold, cpi = 31.80, 34.50, 2_450_00, 100_000

    day = _RATES_FROM
    while day <= _TODAY:
        # A month's inflation, spread across its days, with a little daily noise.
        drift = 1 + (_RANDOM.uniform(0.018, 0.032) / 30)
        cpi = round(cpi * drift)
        usd *= 1 + (_RANDOM.uniform(0.010, 0.026) / 30)
        eur *= 1 + (_RANDOM.uniform(0.008, 0.028) / 30)
        gold *= 1 + (_RANDOM.uniform(0.015, 0.040) / 30)

        db.add(
            models.FxRate(
                as_of=day,
                usd_minor=round(usd * 100),
                eur_minor=round(eur * 100),
                gold_minor=round(gold),
                cpi_index=cpi,
            )
        )
        day += timedelta(days=1)


def _sellers(db: Session) -> list[str]:
    ids = []
    for index, (shop, owner) in enumerate(_SHOPS):
        user_id = f"dseller_{index}"
        db.add(
            models.User(
                user_id=user_id,
                phone=_phone(1_000 + index),
                display_name=owner,
                is_buyer=True,
                is_seller=True,
                email=None,
                shop_name=shop,
                shop_phone=f"+9021{index:08d}",
                created_at=_at(_ENTRIES_FROM),
            )
        )
        ids.append(user_id)
    return ids


def _buyers(db: Session) -> list[str]:
    ids = []
    for index in range(32):
        user_id = f"dbuyer_{index}"
        name = f"{_FIRST_NAMES[index % len(_FIRST_NAMES)]} {_RANDOM.choice(_SURNAMES)}"
        db.add(
            models.User(
                user_id=user_id,
                phone=_phone(2_000 + index),
                display_name=name,
                is_buyer=True,
                is_seller=False,
                email=None,
                shop_name=None,
                shop_phone=None,
                created_at=_at(_ENTRIES_FROM + timedelta(days=index)),
            )
        )
        ids.append(user_id)
    return ids


def _customers(
    db: Session, sellers: list[str], buyers: list[str]
) -> list[tuple[str, str]]:
    """
    Customer records: one per (shop, person) pair, which is how the model works.

    Most people shop at two or three places, so the same person genuinely holds several
    records -- and that is what makes /me/debts worth looking at. About a fifth are left
    UNCLAIMED: customers the shopkeeper wrote down who never installed anything, which is
    the majority case in real life and the one the claim flow exists for.

    Returns (seller_id, customer_id) pairs for the ledger to write against.
    """
    pairs = []
    for index, buyer in enumerate(buyers):
        person = db.get(models.User, buyer)
        # Two to four shops each. A neighbourhood where everyone used exactly one shop
        # would make /me/debts -- a screen whose whole job is grouping BY shop -- look
        # like a list with one row in it.
        for shop in _RANDOM.sample(sellers, _RANDOM.randint(2, 4)):
            customer_id = f"dcust_{shop.split('_')[1]}_{index}"
            claimed = _RANDOM.random() > 0.2

            db.add(
                models.Customer(
                    customer_id=customer_id,
                    display_name=person.display_name,
                    phone=person.phone,
                    claim_status="CLAIMED" if claimed else "UNCLAIMED",
                    claimed_by_user_id=buyer if claimed else None,
                    created_by_seller_id=shop,
                    created_at=person.created_at,
                )
            )
            pairs.append((shop, customer_id))
    return pairs


def _entries(db: Session, pairs: list[tuple[str, str]]) -> None:
    """
    A year of buying on credit and paying it back, per account.

    The pattern each account follows is drawn once and then kept, so the data has
    characters in it rather than uniform noise: some people settle every month, some let a
    tab run for half a year. Those differences are what a risk ranking or a collection
    forecast would have to find, and a demo where everyone behaves identically cannot show
    that anything was found.
    """
    counter = 0
    for seller, customer in pairs:
        # How this person behaves: how often they buy, and how reliably they settle.
        visits_per_month = _RANDOM.choice([1, 2, 2, 3, 4])
        settles = _RANDOM.random()

        day = _ENTRIES_FROM + timedelta(days=_RANDOM.randint(0, 25))
        while day <= _TODAY:
            season = _SEASONAL.get(day.month, 1.0)

            # A busy month means MORE trips as well as bigger ones. Scaling only the
            # amounts left the yearly chart nearly flat -- the seasonality was there in
            # the arithmetic but invisible in the shape, which is the only place it
            # matters.
            visits = visits_per_month + (1 if season > 1.2 else 0)

            for _ in range(visits):
                if day > _TODAY:
                    break
                label, low, high = _RANDOM.choice(_ITEMS)
                amount = round(_RANDOM.randint(low, high) * season / 100) * 100

                counter += 1
                db.add(
                    models.Transaction(
                        transaction_id=f"dtx_{counter}",
                        seller_id=seller,
                        customer_id=customer,
                        amount_minor=amount,
                        type="DEBT",
                        description=label,
                        basket_id=None,
                        settled_via_pgw=False,
                        receipt_no=None,
                        created_at=_at(day, _RANDOM.randint(8, 20), _RANDOM.choice([0, 15, 30, 45])),
                    )
                )
                day += timedelta(days=_RANDOM.randint(3, 12))

            # A payment at the end of most months, sized by how reliable this person is.
            if _RANDOM.random() < settles:
                paid = _balance_so_far(db, seller, customer, day)
                if paid > 0:
                    share = _RANDOM.uniform(0.4, 1.0) if settles < 0.8 else 1.0
                    amount = round(paid * share / 100) * 100
                    if amount > 0:
                        counter += 1
                        db.add(
                            models.Transaction(
                                transaction_id=f"dtx_{counter}",
                                seller_id=seller,
                                customer_id=customer,
                                amount_minor=amount,
                                type="PAYMENT",
                                description=_RANDOM.choice(_PAID_DESCRIPTIONS),
                                basket_id=None,
                                settled_via_pgw=_RANDOM.random() > 0.5,
                                receipt_no=None,
                                created_at=_at(min(day, _TODAY), 17, 30),
                            )
                        )
        db.flush()


def _balance_so_far(db: Session, seller: str, customer: str, before: date) -> int:
    """
    What this account owes as of `before` -- so a payment never exceeds the debt.

    Uses the ledger's own _BALANCE expression rather than a second sum written here. A
    demo whose payments were computed by different arithmetic than the app's could produce
    a negative balance, and negative balances are exactly what the indexer refuses to
    touch -- the fault would surface as missing indexation rows, far from its cause.
    """
    db.flush()
    total = db.execute(
        select(_BALANCE).where(
            models.Transaction.seller_id == seller,
            models.Transaction.customer_id == customer,
            models.Transaction.created_at < _at(before),
        )
    ).scalar_one()
    return int(total)


def _traffic(db: Session, actors: list[str]) -> None:
    """
    A year of request records for the admin panel's traffic tab.

    NOTHING WRITES THESE IN PRODUCTION -- there is no audit middleware, deliberately
    (deferred.md §L.1). The shape is what the panel needs: busier on weekdays, a lunchtime
    and an evening peak, mostly reads, and a thin scatter of 4xx.
    """
    weights = [weight for _, weight in _TRAFFIC]
    endpoints = [action for action, _ in _TRAFFIC]

    counter = 0
    day = _ENTRIES_FROM
    while day <= _TODAY:
        # Saturdays are the busiest day in a corner shop; Sundays are quiet.
        weekday = day.weekday()
        volume = 4 if weekday == 6 else (14 if weekday == 5 else 9)

        for _ in range(_RANDOM.randint(volume - 3, volume + 4)):
            action = _RANDOM.choices(endpoints, weights=weights)[0]
            # Two humps: the midday errand and the walk home from work.
            hour = _RANDOM.choice([9, 11, 12, 12, 13, 17, 18, 18, 19, 20])
            status = _RANDOM.choices([200, 201, 401, 403, 404], [78, 12, 5, 3, 2])[0]

            counter += 1
            db.add(
                models.AuditLog(
                    id=f"daudit_{counter}",
                    actor_user_id=(
                        None if status == 401 else _RANDOM.choice(actors)
                    ),
                    action=action,
                    entity_type=action.split()[1].strip("/").split("/")[0] or None,
                    entity_id=None,
                    ip=f"10.0.{_RANDOM.randint(0, 4)}.{_RANDOM.randint(2, 250)}",
                    user_agent=_RANDOM.choice(
                        ["app-pos/1.0 (Android 13)", "app-mobile/1.0 (Android 14)"]
                    ),
                    status_code=status,
                    created_at=_at(day, hour, _RANDOM.randint(0, 59)),
                )
            )
        day += timedelta(days=1)


def main() -> None:
    with SessionLocal() as db:
        seed_demo(db)

        shops = db.execute(
            select(func.count()).select_from(models.User).where(models.User.is_seller)
        ).scalar_one()
        entries = db.execute(
            select(func.count()).select_from(models.Transaction)
        ).scalar_one()
        rates = db.execute(select(func.count()).select_from(models.FxRate)).scalar_one()
        traffic = db.execute(
            select(func.count()).select_from(models.AuditLog)
        ).scalar_one()

        print(
            f"demo seed hazır: {shops} dükkân, {entries} hareket, "
            f"{rates} kur satırı, {traffic} trafik kaydı"
        )


if __name__ == "__main__":
    main()
