"""
Storing the gateway's basket, and reading it back onto the wire.

A basket arrives with a sale and never changes afterwards: the shop handed over these
items at this price, and the ledger entry it produced is append-only. So there is no
update here, only an idempotent insert and a bulk read.

Both halves live together because they are the same shape seen twice. The write is used
by two routers (a direct sale, and one that waits for an approval), and the read by the
one serializer every transaction response goes through -- keeping them apart is how the
two drift into disagreeing about what a basket is.
"""

import uuid
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import models, schemas


def write_basket(db: Session, basket: schemas.OrderBody) -> str:
    """
    Persist a handed-off basket and return its id. Part of the caller's transaction.

    Idempotent: a replay whose basket already exists reuses it rather than inserting the
    items a second time. That is what makes it safe to call from a retried request.

    Deliberately does NOT commit. A basket committed on its own could outlive a sale that
    rolled back, leaving items attached to nothing.
    """
    if db.get(models.Basket, basket.basket_id) is None:
        db.add(
            models.Basket(
                basket_id=basket.basket_id,
                create_invoice=basket.create_invoice,
                document_type=basket.document_type,
                is_void=basket.is_void,
                created_at=datetime.now(UTC),
            )
        )
        for item in basket.items:
            db.add(
                models.BasketItem(
                    id=f"bi_{uuid.uuid4().hex[:12]}",
                    basket_id=basket.basket_id,
                    name=item.name,
                    price_minor=item.price,
                    quantity=item.quantity,
                    tax_percent=item.tax_percent,
                    section_no=item.section_no,
                    status=item.status,
                    type=item.type,
                    item_limit=item.item_limit,
                )
            )
        # Makes the basket visible to a foreign key added later in the same transaction.
        db.flush()

    return basket.basket_id


def load_baskets(db: Session, basket_ids: list[str]) -> dict[str, schemas.OrderBody]:
    """
    Read baskets by id, keyed for lookup while serialising a list of entries.

    Takes every id at once rather than offering a one-basket call, because the caller is
    almost always serialising a history: a per-row read would put one query per entry
    behind an endpoint that already runs one request per customer (docs/deferred.md F.5).
    Two queries answer any number of rows here.

    Ids with no basket are simply absent from the result; the caller reads that as "this
    entry had no basket", which is the common case.
    """
    if not basket_ids:
        return {}

    # A set: a history where several entries share one basket must not fetch it twice.
    wanted = set(basket_ids)

    headers = db.execute(
        select(models.Basket).where(models.Basket.basket_id.in_(wanted))
    ).scalars().all()

    if not headers:
        return {}

    rows = db.execute(
        select(models.BasketItem)
        .where(models.BasketItem.basket_id.in_(wanted))
        # Stable order so the same basket serialises identically every time. The table has
        # no sequence of its own, and the id is random, so name is what is left.
        .order_by(models.BasketItem.basket_id, models.BasketItem.name)
    ).scalars().all()

    items: dict[str, list[schemas.OrderItem]] = {}
    for row in rows:
        items.setdefault(row.basket_id, []).append(
            schemas.OrderItem(
                name=row.name,
                price=row.price_minor,
                quantity=row.quantity,
                tax_percent=row.tax_percent,
                section_no=row.section_no,
                status=row.status,
                type=row.type,
                item_limit=row.item_limit,
            )
        )

    return {
        header.basket_id: schemas.OrderBody(
            basket_id=header.basket_id,
            create_invoice=header.create_invoice,
            document_type=header.document_type,
            is_void=header.is_void,
            items=items.get(header.basket_id, []),
        )
        for header in headers
    }
