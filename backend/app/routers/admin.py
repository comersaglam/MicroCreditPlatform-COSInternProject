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

from .. import models, seed, seed_demo
from ..admin_auth import AdminAuth, verify_password
from ..config import settings
from ..deps import DbSession
from ..reset import _TABLES
from ..schemas import IsoUtc
from ..security import ADMIN_SUBJECT, api_error, create_admin_token
from sqlalchemy import func, select

router = APIRouter(tags=["admin"])


# --- wire shapes ---


class AdminLogin(BaseModel):
    password: str


class AdminSession(BaseModel):
    token: str
    expires_at: IsoUtc


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
