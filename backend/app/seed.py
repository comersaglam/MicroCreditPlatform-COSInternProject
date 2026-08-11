"""
Demo data, shared by both clients.

WHICH SEED THIS IS (plan §0.1): app-mobile's SeedCallback, not app-pos's. The two clients
seeded different rows under the SAME ids -- t4/t5 are u_owner->c2 in app-pos but
u_market->m1 in app-mobile -- and there is only one database now, so one of them had to
win. app-mobile's is the superset: it has the second shop (u_market), the cross-book
records (m1/o1) and the pending approvals, without which /me/debts and /approvals return
empty lists and cannot be tested at all. c2 (Ayşe Demir) is carried over from app-pos as
the one row it had in addition -- an UNCLAIMED customer, which the claim flow needs.

TIMESTAMPS: app-mobile still writes "dd.MM.yyyy HH:mm" local time (its Aşama 0 migration
is still pending). Those literals are Istanbul (UTC+3), so they are converted here:
"20.07.2026 09:15" -> "2026-07-20T06:15:00Z". Same instant, now unambiguous, and matching
what app-pos already writes.

Seeding is idempotent: it runs only when `users` is empty. Alembic runs on every container
start, so an unconditional seed would double every balance on the second `compose up`.
"""

from datetime import UTC, datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import models


def _at(iso: str) -> datetime:
    """Parse an ISO-8601 UTC literal into an aware datetime."""
    return datetime.fromisoformat(iso).replace(tzinfo=UTC)


def is_empty(db: Session) -> bool:
    """True when no user row exists -- the marker that this database was never seeded."""
    return db.execute(select(func.count()).select_from(models.User)).scalar_one() == 0


def seed(db: Session) -> None:
    """Insert the demo rows. Caller is responsible for checking `is_empty` first."""

    # Two shopkeepers (both also buyers -- one account, two roles). A person's name and
    # their shop's name are deliberately DIFFERENT: buyer-facing screens show the shop,
    # seller-facing screens show the person, and identical names would hide a screen
    # pulling the wrong one.
    _user(db, "u_owner", "+905554443322", "Ahmet Demirtaş", True,
          "Ahmet Bakkal", "+902121112233", "2026-07-01T06:00:00")
    _user(db, "u_market", "+905553334455", "Ayşe Korkmaz", True,
          "Ayşe Market", "+902123334455", "2026-07-02T06:00:00")
    # … and two plain buyers.
    _user(db, "u1", "+905551112233", "Ahmet Yılmaz", False,
          None, None, "2026-07-05T09:30:00")
    _user(db, "u3", "+905554445566", "Mehmet Kaya", False,
          None, None, "2026-07-08T12:45:00")

    # Flushed before the customers below because customers.claimed_by_user_id is a FK to
    # users. SQLAlchemy batches pending inserts per table and has no way to know these
    # rows must land first, so the ordering is stated here rather than hoped for.
    db.flush()

    # u1 is one person with a record in each shop's book (c1 and m1). The last argument
    # is which shop wrote them down -- it puts the row in that book even before the first
    # ledger entry exists.
    _customer(db, "c1", "Ahmet Yılmaz", "+905551112233", "CLAIMED", "u1", "u_owner")
    _customer(db, "m1", "Ahmet Y.", "+905551112233", "CLAIMED", "u1", "u_market")
    # The shopkeeper's own record as a buyer at the other shop -- a customer row is a
    # PERSON, so it carries their name, not their shop's.
    _customer(db, "o1", "Ahmet Demirtaş", "+905554443322", "CLAIMED", "u_owner", "u_market")
    _customer(db, "c3", "Mehmet Kaya", "+905554445566", "CLAIMED", "u3", "u_owner")
    # Carried over from app-pos's seed: the UNCLAIMED customer the claim flow needs.
    _customer(db, "c2", "Ayşe Demir", "+905552223344", "UNCLAIMED", None, "u_owner")
    _customer(db, "c4", "Fatma Şahin", "+905556667788", "UNCLAIMED", None, "u_owner")
    _customer(db, "c5", "Hasan Öztürk", "+905558889900", "UNCLAIMED", None, "u_owner")

    # Same reason: transactions.customer_id is a FK to the rows just added.
    db.flush()

    # u1 @ Ahmet Bakkal (c1): 50 + 30 - 40 = 40,00
    _tx(db, "t1", "u_owner", "c1", 5000, "DEBT", "Ekmek, süt", "2026-07-20T06:15:00")
    _tx(db, "t2", "u_owner", "c1", 3000, "DEBT", "Peynir", "2026-07-21T07:40:00")
    _tx(db, "t3", "u_owner", "c1", 4000, "PAYMENT", "Nakit ödeme", "2026-07-22T15:00:00")
    # u1 @ Ayşe Market (m1): 120 + 45 - 65 = 100,00
    _tx(db, "t4", "u_market", "m1", 12000, "DEBT", "Market alışverişi", "2026-07-18T08:20:00")
    _tx(db, "t5", "u_market", "m1", 4500, "DEBT", "Deterjan", "2026-07-22T13:05:00")
    _tx(db, "t6", "u_market", "m1", 6500, "PAYMENT", "Kısmi ödeme", "2026-07-24T10:00:00")
    # u_owner @ Ayşe Market (o1): 90 - 30 = 60,00
    _tx(db, "t7", "u_market", "o1", 9000, "DEBT", "Kırtasiye", "2026-07-16T07:00:00")
    _tx(db, "t8", "u_market", "o1", 3000, "PAYMENT", "Nakit ödeme", "2026-07-23T12:30:00")
    # u_owner's OWN book: c3 = 0, c4 = 25,50, c5 = 210,00
    _tx(db, "t9", "u_owner", "c3", 8000, "DEBT", "Kahvaltılık", "2026-07-15T05:30:00")
    _tx(db, "t10", "u_owner", "c3", 8000, "PAYMENT", "Kart ile ödeme", "2026-07-19T09:00:00")
    _tx(db, "t11", "u_owner", "c4", 2550, "DEBT", "Çay, şeker", "2026-07-23T05:45:00")
    _tx(db, "t12", "u_owner", "c5", 31000, "DEBT", "Toplu alışveriş", "2026-07-10T14:30:00")
    _tx(db, "t13", "u_owner", "c5", 10000, "PAYMENT", "Kısmi ödeme", "2026-07-20T11:10:00")
    # …and c2 (from app-pos's book): 120 + 45 = 165,00
    _tx(db, "t14", "u_owner", "c2", 12000, "DEBT", "Market alışverişi", "2026-07-18T08:20:00")
    _tx(db, "t15", "u_owner", "c2", 4500, "DEBT", "Deterjan", "2026-07-22T13:05:00")

    # One pending approval per demo account, so the Onaylar tab is never empty.
    _approval(db, "p1", "u_owner", "Ahmet Bakkal", "u1", "c1", 5000, "DEBT",
              "Ekmek, süt", "2026-07-25T07:05:00")
    _approval(db, "p2", "u_market", "Ayşe Market", "u_owner", "o1", 7500, "DEBT",
              "Temizlik malzemesi", "2026-07-25T08:20:00")

    db.commit()


def _user(db, user_id, phone, name, is_seller, shop_name, shop_phone, created_at):
    db.add(models.User(
        user_id=user_id, phone=phone, display_name=name,
        is_buyer=True, is_seller=is_seller, email=None,
        shop_name=shop_name, shop_phone=shop_phone, created_at=_at(created_at),
    ))


def _customer(db, customer_id, name, phone, claim, claimed_by, created_by_seller):
    db.add(models.Customer(
        customer_id=customer_id, display_name=name, phone=phone,
        claim_status=claim, claimed_by_user_id=claimed_by,
        created_by_seller_id=created_by_seller,
        created_at=_at("2026-07-01T06:00:00"),
    ))


def _tx(db, tx_id, seller, customer, amount, tx_type, desc, created_at):
    db.add(models.Transaction(
        transaction_id=tx_id, seller_id=seller, customer_id=customer,
        amount_minor=amount, type=tx_type, description=desc,
        basket_id=None, settled_via_pgw=False, receipt_no=None,
        created_at=_at(created_at),
    ))


def _approval(db, approval_id, seller_id, shop_name, target_user_id,
              customer_id, amount, tx_type, desc, requested_at):
    db.add(models.Approval(
        approval_id=approval_id,
        # Today's flow is seller-initiated; the direction fields record that explicitly.
        initiator_user_id=seller_id, initiator_role="SELLER",
        target_user_id=target_user_id, seller_id=seller_id,
        shop_name=shop_name, customer_id=customer_id,
        amount_minor=amount, type=tx_type, description=desc,
        channel="APP_PUSH", status="PENDING", requested_at=_at(requested_at),
    ))
