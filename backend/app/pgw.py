"""
Building work for the payment gateway, and the orderBody that travels with it.

The gateway is somebody else's application: we do not implement it, we hand it intents in
the shape it already expects. That shape is fixed by THEM, which is why the JSON here is
assembled literally rather than derived from our own models -- a field renamed on our side
must not silently change what the gateway receives.

Reference invocation (the real terminal, from the integration notes):

    am start -n com.tokeninc.sardis.paymentgateway/.MainActivity \
      --es orderBody '{"basketID":"...","documentType":9002,...}'
"""

import json
import uuid
from datetime import UTC, datetime

from sqlalchemy.orm import Session

from . import models

# What a job is FOR. RECEIPT accompanies an entry that is already in the ledger and asks
# the gateway to print a slip; COLLECT asks it to take money at the terminal.
KIND_RECEIPT = "RECEIPT"
KIND_COLLECT = "COLLECT"

# A job is waiting until a terminal says it handed it over.
STATUS_PENDING = "PENDING"
STATUS_DELIVERED = "DELIVERED"

# The gateway's own payment-item type for a credit (veresiye) slip. Their number, not
# ours -- named here so the constant appears once and the intent that carries it can be
# read without a lookup.
PAYMENT_ITEM_TYPE_CREDIT = 17

# The document type the integration example uses for this receipt class.
DOCUMENT_TYPE_RECEIPT = 9002


def receipt_order_body(amount_minor: int, basket_id: str | None = None) -> str:
    """
    The orderBody for a credit receipt, in the gateway's shape.

    `paymentItems[].type` is 17: that is what marks the slip as a veresiye rather than a
    card payment, and it is the one field that makes this a receipt request at all.

    A basket id is minted when the caller has none, because the gateway keys the request
    on it -- two receipts sharing an id would be one receipt to them.
    """
    return json.dumps(
        {
            "basketID": basket_id or str(uuid.uuid4()),
            "documentType": DOCUMENT_TYPE_RECEIPT,
            "paymentItems": [
                {"amount": amount_minor, "type": PAYMENT_ITEM_TYPE_CREDIT}
            ],
        },
        # Compact and key-ordered so the same job always serialises identically -- a test
        # can compare the string, and a log line stays diffable.
        separators=(",", ":"),
    )


def queue_job(
    db: Session,
    seller_id: str,
    kind: str,
    customer_id: str,
    amount_minor: int,
    transaction_id: str | None = None,
    order_body: str | None = None,
) -> models.PgwJob:
    """
    Leave work for the seller's terminal to collect.

    Added to the caller's session WITHOUT committing: a job must land in the same commit
    as whatever made it necessary. A receipt job written while its ledger entry rolled
    back would print a slip for a debt that does not exist.
    """
    now = datetime.now(UTC)
    job = models.PgwJob(
        job_id=f"pj_{uuid.uuid4().hex[:12]}",
        seller_id=seller_id,
        kind=kind,
        transaction_id=transaction_id,
        customer_id=customer_id,
        amount_minor=amount_minor,
        order_body=order_body,
        status=STATUS_PENDING,
        created_at=now,
        updated_at=now,
    )
    db.add(job)
    return job
