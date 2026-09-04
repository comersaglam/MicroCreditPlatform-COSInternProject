"""
The admin panel's read side -- the whole platform, from above.

Every other router in this app is scoped to one person: a seller sees their book, a buyer
sees their debts, and the scope comes from the token so no request can widen it. This one
is the exception, and the exception is the point -- the panel exists to show that there is
a platform behind the two apps, not just two apps.

THREE RULES HOLD THIS FILE TOGETHER.

1. It READS. No endpoint here writes to the ledger, and that includes indirectly.
   routers/customers.py calls ensure_indexed_book before reading a book, because reading
   your own balance is what makes this month's inflation real. Copying that pattern here
   would mean opening the panel writes indexation rows for 107 customers -- rows that
   cannot be taken back (deferred.md §L.7). The panel reports whatever the apps have
   already caused. Warming is a separate, deliberate command: app/warm_indexation.py.

2. The balance is NOT defined here. Every total goes through ledger._SIGNED_AMOUNT /
   _BALANCE or breakdown._total_of. That rule is written out ten times across this
   codebase and the eleventh copy would be the first one no test compares against the
   others. Where a platform-wide grouping was missing, it was added to ledger.py beside
   its siblings -- not inlined into a route.

3. One query per list, never one per row. With 8 sellers, 107 customers and ~5800 entries,
   a per-row balance would turn one screen into a hundred round trips. ledger.py's batched
   helpers already exist for exactly this and are reused verbatim.

The response models live in this file rather than in schemas.py on purpose: schemas.py
says at the top that each of its shapes matches one in shared-contracts/openapi.yaml, and
these do not. That contract is what three clients agree on; nothing here is spoken by a
phone or a till, only by web-admin.
"""

from datetime import UTC, datetime

from fastapi import APIRouter
from pydantic import BaseModel
from sqlalchemy import text

from sqlalchemy import func, select

from .. import ledger, models, seed, seed_demo
from ..admin_auth import AdminAuth, verify_password
# _total_of is breakdown.py's own building block -- the same CASE-inside-SUM the balance
# decomposition uses. Imported rather than rewritten so the monthly series cannot classify
# an entry type differently from the breakdown sitting beside it on the same screen.
from ..breakdown import _total_of, breakdown_for
from ..config import settings
from ..deps import DbSession
from ..reset import _TABLES
from ..schemas import IsoUtc, LedgerBreakdown
from ..security import api_error, create_admin_token

router = APIRouter(tags=["admin"])


# --- wire shapes ---


class AdminLogin(BaseModel):
    password: str


class AdminSession(BaseModel):
    token: str
    expires_at: IsoUtc


class AdminSellerRow(BaseModel):
    user_id: str
    display_name: str
    shop_name: str | None
    phone: str
    customer_count: int
    entry_count: int
    receivable_minor: int
    created_at: IsoUtc


class AdminBuyerRow(BaseModel):
    user_id: str
    display_name: str
    phone: str
    is_seller: bool
    shop_count: int
    debt_minor: int
    created_at: IsoUtc


class AdminEntry(BaseModel):
    """A ledger line, flattened for a table. Not schemas.Transaction: no basket here."""

    transaction_id: str
    seller_id: str
    customer_id: str
    counterparty: str
    amount_minor: int
    type: str
    description: str | None
    created_at: IsoUtc


class AdminCustomerRow(BaseModel):
    customer_id: str
    display_name: str
    phone: str
    claim_status: str
    balance_minor: int


class AdminShopDebt(BaseModel):
    seller_id: str
    shop_name: str | None
    display_name: str
    balance_minor: int


class AdminSellerDetail(BaseModel):
    seller: AdminSellerRow
    breakdown: LedgerBreakdown
    customers: list[AdminCustomerRow]
    recent_entries: list[AdminEntry]


class AdminBuyerDetail(BaseModel):
    buyer: AdminBuyerRow
    breakdown: LedgerBreakdown
    debts_by_shop: list[AdminShopDebt]
    recent_entries: list[AdminEntry]


class MonthPoint(BaseModel):
    month: str  # "2026-03"
    debt_minor: int
    payment_minor: int
    indexation_minor: int
    entry_count: int


class NamedAmount(BaseModel):
    label: str
    amount_minor: int


class SellerStats(BaseModel):
    seller_count: int
    customer_count: int
    total_receivable_minor: int
    breakdown: LedgerBreakdown
    monthly: list[MonthPoint]
    top_sellers: list[NamedAmount]
    riskiest_customers: list[NamedAmount]
    collection_rate: float
    data_through: IsoUtc | None


class BuyerStats(BaseModel):
    buyer_count: int
    claimed_customer_count: int
    unclaimed_customer_count: int
    total_debt_minor: int
    breakdown: LedgerBreakdown
    monthly: list[MonthPoint]
    top_debtors: list[NamedAmount]
    by_category: list[NamedAmount]
    debt_bands: list[NamedAmount]
    data_through: IsoUtc | None


class InventoryItem(BaseModel):
    """One row of the panel's own honesty table. See `_INVENTORY`."""

    name: str
    status: str  # REAL | MOCK | MISSING
    note: str


class AdminMe(BaseModel):
    subject: str
    expires_in_seconds: int
    counts: dict[str, int]
    migration_head: str | None
    core_seeded: bool
    demo_seeded: bool
    data_through: IsoUtc | None
    inventory: list[InventoryItem]


# --- the honesty table ---

# deferred.md §L, rendered as data so the panel can show it.
#
# The reason this is worth twenty lines: the person giving the demo should not have to
# remember which figure is live. Turn 46 learned that on the phone -- the insights screen
# carries a line saying its numbers are examples -- and this is the same idea with the
# whole system in view.
_INVENTORY = [
    InventoryItem(
        name="Bakiyeler ve istatistikler",
        status="REAL",
        note="Postgres'ten, ledger.py'ın tek bakiye tanımıyla hesaplanıyor.",
    ),
    InventoryItem(
        name="Kur serisi (fx_rates)",
        status="REAL",
        note="Tablo ve hesap gerçek. Dolar iki gerçek okumaya sabit; euro/altın/TÜFE"
        " uydurma (§L.4).",
    ),
    InventoryItem(
        name="Endeksleme (INDEXATION)",
        status="REAL",
        note="Gerçek ledger satırları, ama tembel: bir bakiye okunmadan yazılmaz (§L.17).",
    ),
    InventoryItem(
        name="Veritabanını sıfırla",
        status="REAL",
        note="reset + seed_demo. §F.4'ün 'asla uç olmasın' kararı bilinçli olarak geri"
        " alındı (§L.19).",
    ),
    InventoryItem(
        name="Trafik sekmesi",
        status="MOCK",
        note="Paneldeki grafikler sabit örnek veri. audit_log okunmuyor (§L.21).",
    ),
    InventoryItem(
        name="Canlı istek kaydı (audit middleware)",
        status="MISSING",
        note="Hiçbir şey audit_log'a canlı yazmıyor; satırlar seed'den (§L.1).",
    ),
    InventoryItem(
        name="Ban / askıya alma",
        status="MOCK",
        note="users.status kolonu yok, migration yazılmadı. Butonlar yalnız ekranda"
        " (§L.20).",
    ),
    InventoryItem(
        name="Ödeme düzeltme",
        status="MOCK",
        note="Ledger append-only; düzeltme ters kayıt ister. Uç yazılmadı (§L.2).",
    ),
    InventoryItem(
        name="Admin oturumu",
        status="MOCK",
        note="Tek paylaşılan şifre, kullanıcı kaydı yok — 'kim yaptı' sorusunun cevabı"
        " yok (§L.6).",
    ),
]


# --- endpoints ---


@router.post("/admin/login", response_model=AdminSession)
def admin_login(body: AdminLogin) -> AdminSession:
    """
    Exchange the shared password for an admin bearer.

    No user is looked up and no row is touched, so a wrong password is indistinguishable
    from any other wrong password -- there is nothing here to enumerate.
    """
    if not verify_password(body.password):
        raise api_error(401, "invalid_credentials", "Wrong admin password")

    token, expires_at = create_admin_token()
    return AdminSession(token=token, expires_at=expires_at)


@router.get("/admin/sellers", response_model=list[AdminSellerRow])
def list_sellers(admin: AdminAuth, db: DbSession) -> list[AdminSellerRow]:
    """
    Every shop, with what it is owed. Three queries, whatever the number of shops.

    The counts come back as two grouped queries rather than a subquery per row. Customer
    counts are taken from the LEDGER, which slightly under-counts: a customer written down
    but never charged belongs to a book (ledger.book_customer_ids exists for exactly that
    case) yet has no entries. Accepted here because this is a list column, not a balance --
    the shop's own screen uses book_customer_ids and gets the exact answer.
    """
    sellers = db.execute(
        select(models.User).where(models.User.is_seller)
    ).scalars().all()

    receivables = ledger.receivables_by_seller(db)

    counts = {
        seller_id: (customers, entries)
        for seller_id, customers, entries in db.execute(
            select(
                models.Transaction.seller_id,
                func.count(func.distinct(models.Transaction.customer_id)),
                func.count(),
            ).group_by(models.Transaction.seller_id)
        ).all()
    }

    rows = [
        AdminSellerRow(
            user_id=seller.user_id,
            display_name=seller.display_name,
            shop_name=seller.shop_name,
            phone=seller.phone,
            customer_count=counts.get(seller.user_id, (0, 0))[0],
            entry_count=counts.get(seller.user_id, (0, 0))[1],
            receivable_minor=receivables.get(seller.user_id, 0),
            created_at=seller.created_at,
        )
        for seller in sellers
    ]
    # Biggest book first: the panel's first screen should open on the shops that matter.
    return sorted(rows, key=lambda row: row.receivable_minor, reverse=True)


@router.get("/admin/sellers/{user_id}", response_model=AdminSellerDetail)
def seller_detail(user_id: str, admin: AdminAuth, db: DbSession) -> AdminSellerDetail:
    """
    One shop: its book, its balance decomposed, and its last entries.

    The decomposition is breakdown_for(seller_id=...) -- the same function, called the
    same way, that answers the shopkeeper's own /customers/breakdown. Nothing about the
    figure is recomputed for the panel, so the panel cannot disagree with the till.
    """
    seller = db.get(models.User, user_id)
    if seller is None or not seller.is_seller:
        raise api_error(404, "seller_not_found", "No such seller")

    balances = ledger.balances_by_customer(db, user_id)
    customers = db.execute(
        select(models.Customer).where(
            models.Customer.customer_id.in_(balances.keys() or [""])
        )
    ).scalars().all()

    customer_rows = sorted(
        (
            AdminCustomerRow(
                customer_id=customer.customer_id,
                display_name=customer.display_name,
                phone=customer.phone,
                claim_status=customer.claim_status,
                balance_minor=balances.get(customer.customer_id, 0),
            )
            for customer in customers
        ),
        key=lambda row: row.balance_minor,
        reverse=True,
    )

    entry_count = db.execute(
        select(func.count()).where(models.Transaction.seller_id == user_id)
    ).scalar_one()

    return AdminSellerDetail(
        seller=AdminSellerRow(
            user_id=seller.user_id,
            display_name=seller.display_name,
            shop_name=seller.shop_name,
            phone=seller.phone,
            customer_count=len(balances),
            entry_count=entry_count,
            receivable_minor=sum(balances.values()),
            created_at=seller.created_at,
        ),
        breakdown=breakdown_for(db, seller_id=user_id),
        customers=customer_rows[:20],
        recent_entries=_recent_entries(
            db, models.Transaction.seller_id == user_id
        ),
    )


@router.get("/admin/buyers", response_model=list[AdminBuyerRow])
def list_buyers(admin: AdminAuth, db: DbSession) -> list[AdminBuyerRow]:
    """
    Every buyer, with what they owe across all their shops.

    A buyer's debt is not a seller_id grouping -- it travels their customer records, one
    per shop (ledger.debts_by_buyer). The shop count is the same journey, counted rather
    than summed.
    """
    buyers = db.execute(
        select(models.User).where(models.User.is_buyer)
    ).scalars().all()

    debts = ledger.debts_by_buyer(db)

    shop_counts = {
        user_id: count
        for user_id, count in db.execute(
            select(
                models.Customer.claimed_by_user_id,
                func.count(func.distinct(models.Transaction.seller_id)),
            )
            .join(
                models.Transaction,
                models.Transaction.customer_id == models.Customer.customer_id,
            )
            .where(models.Customer.claimed_by_user_id.is_not(None))
            .group_by(models.Customer.claimed_by_user_id)
        ).all()
    }

    rows = [
        AdminBuyerRow(
            user_id=buyer.user_id,
            display_name=buyer.display_name,
            phone=buyer.phone,
            is_seller=buyer.is_seller,
            shop_count=shop_counts.get(buyer.user_id, 0),
            debt_minor=debts.get(buyer.user_id, 0),
            created_at=buyer.created_at,
        )
        for buyer in buyers
    ]
    return sorted(rows, key=lambda row: row.debt_minor, reverse=True)


@router.get("/admin/buyers/{user_id}", response_model=AdminBuyerDetail)
def buyer_detail(user_id: str, admin: AdminAuth, db: DbSession) -> AdminBuyerDetail:
    """
    One buyer: what they owe, to whom, decomposed.

    breakdown_for(customer_ids=...) is the third of that function's four documented modes
    -- literally the buyer's own /me/debts/breakdown, read from the other side of the
    counter.
    """
    buyer = db.get(models.User, user_id)
    if buyer is None or not buyer.is_buyer:
        raise api_error(404, "buyer_not_found", "No such buyer")

    customer_ids = list(
        db.execute(
            select(models.Customer.customer_id).where(
                models.Customer.claimed_by_user_id == user_id
            )
        ).scalars().all()
    )

    per_shop = ledger.debts_by_seller(db, customer_ids)
    shops = db.execute(
        select(models.User).where(models.User.user_id.in_(per_shop.keys() or [""]))
    ).scalars().all()

    return AdminBuyerDetail(
        buyer=AdminBuyerRow(
            user_id=buyer.user_id,
            display_name=buyer.display_name,
            phone=buyer.phone,
            is_seller=buyer.is_seller,
            shop_count=len(per_shop),
            debt_minor=sum(per_shop.values()),
            created_at=buyer.created_at,
        ),
        breakdown=breakdown_for(db, customer_ids=customer_ids),
        debts_by_shop=sorted(
            (
                AdminShopDebt(
                    seller_id=shop.user_id,
                    shop_name=shop.shop_name,
                    display_name=shop.display_name,
                    balance_minor=per_shop.get(shop.user_id, 0),
                )
                for shop in shops
            ),
            key=lambda shop: shop.balance_minor,
            reverse=True,
        ),
        recent_entries=_recent_entries(
            db, models.Transaction.customer_id.in_(customer_ids or [""])
        ),
    )


def _recent_entries(db: DbSession, condition) -> list[AdminEntry]:
    """
    The last 20 ledger lines matching a condition, with the customer's name attached.

    One join rather than a name lookup per row -- twenty round trips for a table nobody
    scrolls would be the same N+1 the balance queries were batched to avoid.
    """
    rows = db.execute(
        select(models.Transaction, models.Customer.display_name)
        .join(
            models.Customer,
            models.Customer.customer_id == models.Transaction.customer_id,
        )
        .where(condition)
        .order_by(models.Transaction.created_at.desc())
        .limit(20)
    ).all()

    return [
        AdminEntry(
            transaction_id=entry.transaction_id,
            seller_id=entry.seller_id,
            customer_id=entry.customer_id,
            counterparty=name,
            amount_minor=entry.amount_minor,
            type=entry.type,
            description=entry.description,
            created_at=entry.created_at,
        )
        for entry, name in rows
    ]


@router.get("/admin/stats/sellers", response_model=SellerStats)
def seller_stats(admin: AdminAuth, db: DbSession) -> SellerStats:
    """
    The platform from the shopkeepers' side: what is owed, how it moves, who is at risk.
    """
    receivables = ledger.receivables_by_seller(db)
    names = _seller_labels(db, receivables.keys())

    return SellerStats(
        seller_count=db.execute(
            select(func.count()).select_from(models.User).where(models.User.is_seller)
        ).scalar_one(),
        customer_count=db.execute(
            select(func.count()).select_from(models.Customer)
        ).scalar_one(),
        total_receivable_minor=sum(receivables.values()),
        # Both selectors left None: breakdown_for's fourth mode is the whole ledger, so
        # the platform's decomposition costs nothing new.
        breakdown=breakdown_for(db),
        monthly=_monthly_series(db),
        top_sellers=_top(
            [(names.get(sid, sid), amount) for sid, amount in receivables.items()], 5
        ),
        riskiest_customers=_riskiest_customers(db),
        collection_rate=_collection_rate(db),
        data_through=_data_through(db),
    )


@router.get("/admin/stats/buyers", response_model=BuyerStats)
def buyer_stats(admin: AdminAuth, db: DbSession) -> BuyerStats:
    """
    The same ledger from the buyers' side.

    ⚠️ total_debt_minor here and total_receivable_minor on the sellers tab are THE SAME
    NUMBER minus what unclaimed customers owe -- one ledger, read from either end. Both
    come from _BALANCE; if they ever disagree, a second definition of the balance has been
    written somewhere.
    """
    debts = ledger.debts_by_buyer(db)
    names = _buyer_labels(db, debts.keys())

    claimed, unclaimed = (
        db.execute(
            select(
                func.count().filter(models.Customer.claim_status == "CLAIMED"),
                func.count().filter(models.Customer.claim_status == "UNCLAIMED"),
            )
        ).one()
    )

    return BuyerStats(
        buyer_count=db.execute(
            select(func.count()).select_from(models.User).where(models.User.is_buyer)
        ).scalar_one(),
        claimed_customer_count=claimed,
        unclaimed_customer_count=unclaimed,
        total_debt_minor=sum(debts.values()),
        breakdown=breakdown_for(db),
        monthly=_monthly_series(db),
        top_debtors=_top(
            [(names.get(uid, uid), amount) for uid, amount in debts.items()], 10
        ),
        by_category=_by_category(db),
        debt_bands=_debt_bands(debts),
        data_through=_data_through(db),
    )


def _monthly_series(db: DbSession) -> list[MonthPoint]:
    """
    Twelve months of the ledger, grouped in SQL.

    ⚠️ func.extract, NOT date_trunc or strftime. The tests run on SQLite and production on
    Postgres, and each dialect is missing the other's function -- extract is the one both
    understand.

    The window ends at the LAST ENTRY rather than at today. seed_demo stops on a fixed
    date while the clock keeps going, so a window measured back from now() would open with
    an empty stretch and, a month after the demo, would be empty throughout. Anchoring to
    the data means these charts stay full however long after the seed they are read.
    """
    last = _data_through(db)
    if last is None:
        return []

    rows = db.execute(
        select(
            func.extract("year", models.Transaction.created_at).label("y"),
            func.extract("month", models.Transaction.created_at).label("m"),
            _total_of("DEBT"),
            _total_of("PAYMENT"),
            _total_of("INDEXATION"),
            func.count(),
        )
        .group_by("y", "m")
        .order_by("y", "m")
    ).all()

    points = [
        MonthPoint(
            month=f"{int(year):04d}-{int(month):02d}",
            debt_minor=debt,
            payment_minor=payment,
            indexation_minor=indexation,
            entry_count=count,
        )
        for year, month, debt, payment, indexation, count in rows
    ]
    return points[-12:]


def _riskiest_customers(db: DbSession) -> list[NamedAmount]:
    """
    Who owes the most, across every book.

    "Risk" here is simply size of balance -- the seed gives each account a settling habit
    (seed_demo._entries) so the ranking does find the people who let a tab run, but this
    is not a credit model and the panel should not call it one.
    """
    rows = db.execute(
        select(models.Customer.display_name, ledger._BALANCE)
        .join(
            models.Transaction,
            models.Transaction.customer_id == models.Customer.customer_id,
        )
        .group_by(models.Customer.customer_id, models.Customer.display_name)
        .order_by(ledger._BALANCE.desc())
        .limit(5)
    ).all()
    return [NamedAmount(label=name, amount_minor=balance) for name, balance in rows]


def _collection_rate(db: DbSession) -> float:
    """What share of everything ever charged has actually been paid. 0.0 when nothing has."""
    charged, paid = db.execute(
        select(_total_of("DEBT"), _total_of("PAYMENT"))
    ).one()
    return round(paid / charged, 4) if charged else 0.0


def _by_category(db: DbSession) -> list[NamedAmount]:
    """
    What the credit was spent on, by the entry's own description.

    seed_demo draws descriptions from ten shopping categories, so this is a real grouping
    of real rows -- not a mock. Payments are excluded: "Nakit ödeme" is not a category of
    goods.
    """
    rows = db.execute(
        select(models.Transaction.description, func.sum(models.Transaction.amount_minor))
        .where(models.Transaction.type == "DEBT")
        .group_by(models.Transaction.description)
        .order_by(func.sum(models.Transaction.amount_minor).desc())
        .limit(10)
    ).all()
    return [
        NamedAmount(label=label or "(açıklamasız)", amount_minor=total)
        for label, total in rows
    ]


def _debt_bands(debts: dict[str, int]) -> list[NamedAmount]:
    """
    How debt is distributed across people -- the shape a single average hides.

    amount_minor carries a COUNT of people here, not money. The field is reused rather
    than a third model added for one chart; the panel labels the axis.
    """
    bands = [
        ("0", lambda amount: amount <= 0),
        ("0-100 TL", lambda amount: 0 < amount <= 10_000),
        ("100-500 TL", lambda amount: 10_000 < amount <= 50_000),
        ("500-2.000 TL", lambda amount: 50_000 < amount <= 200_000),
        ("2.000 TL+", lambda amount: amount > 200_000),
    ]
    return [
        NamedAmount(
            label=label,
            amount_minor=sum(1 for amount in debts.values() if matches(amount)),
        )
        for label, matches in bands
    ]


def _top(pairs: list[tuple[str, int]], count: int) -> list[NamedAmount]:
    ranked = sorted(pairs, key=lambda pair: pair[1], reverse=True)[:count]
    return [NamedAmount(label=label, amount_minor=amount) for label, amount in ranked]


def _seller_labels(db: DbSession, user_ids) -> dict[str, str]:
    """Shop names for a set of sellers -- buyer-facing language names the SHOP."""
    rows = db.execute(
        select(models.User.user_id, models.User.shop_name, models.User.display_name)
        .where(models.User.user_id.in_(list(user_ids) or [""]))
    ).all()
    return {user_id: shop or name for user_id, shop, name in rows}


def _buyer_labels(db: DbSession, user_ids) -> dict[str, str]:
    rows = db.execute(
        select(models.User.user_id, models.User.display_name).where(
            models.User.user_id.in_(list(user_ids) or [""])
        )
    ).all()
    return dict(rows)


@router.get("/admin/me", response_model=AdminMe)
def admin_me(admin: AdminAuth, db: DbSession) -> AdminMe:
    """
    Who is signed in, what the database holds, and what in this panel is real.

    The counts iterate reset._TABLES rather than a list of their own. That file's rule is
    that a new table is a deliberate edit THERE; borrowing the list means a table added
    later shows up in the reset and in this panel at the same moment, instead of the panel
    quietly under-reporting.
    """
    counts = {
        table: db.execute(
            select(func.count()).select_from(text(table))
        ).scalar_one()
        for table in _TABLES
    }

    return AdminMe(
        subject=admin,
        expires_in_seconds=settings.token_ttl_seconds,
        counts=counts,
        migration_head=_migration_head(db),
        core_seeded=not seed.is_empty(db),
        demo_seeded=seed_demo.already_seeded(db),
        data_through=_data_through(db),
        inventory=_INVENTORY,
    )


def _migration_head(db: DbSession) -> str | None:
    """
    Which migration the database is on, or None when the question does not apply.

    Wrapped, because the test database has no alembic_version table at all: conftest
    builds the schema with Base.metadata.create_all, which Alembic never sees. Returning
    None there documents the difference; letting the query raise would make every admin
    test fail for a reason that has nothing to do with the endpoint.
    """
    try:
        return db.execute(
            text("SELECT version_num FROM alembic_version")
        ).scalar_one_or_none()
    except Exception:
        return None


def _data_through(db: DbSession) -> datetime | None:
    """
    The most recent entry in the ledger.

    Shown because the demo data stops on a fixed date (seed_demo._TODAY) while the clock
    does not. Every window in this panel is measured back from THIS instant rather than
    from now(), so the charts stay full however long after the seed they are opened -- and
    this field is what lets the panel say so out loud instead of leaving a gap nobody can
    explain.
    """
    return db.execute(select(func.max(models.Transaction.created_at))).scalar_one_or_none()
