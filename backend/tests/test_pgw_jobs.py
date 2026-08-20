"""
The terminal's inbox.

Two rules carry the weight here. First, a job is queued only when the sale started on a
PHONE: a terminal that raised the request is already at the gateway and hands the intent
over itself, so queueing for it would print the same receipt twice. Second, the ack is
idempotent -- the terminal fires the intent first and acks second, so a lost response
leaves it retrying an ack for work it really did deliver.
"""

import json

from app import models


def _seller_request(**overrides) -> dict:
    """A seller-initiated approval, raised from a phone unless a test says otherwise."""
    body = {
        "seller_id": "u_owner",
        "customer_id": "c1",       # CLAIMED by u1
        "amount_minor": 5000,
        "type": "DEBT",
        "description": "Ekmek, süt",
        "initiator_role": "SELLER",
        "target_user_id": "u1",
        "origin": "PHONE",
    }
    body.update(overrides)
    return body


def _approve_as_buyer(client, buyer_auth, approval_id: str):
    return client.post(f"/approvals/{approval_id}/approve", headers=buyer_auth)


# --- jobs raised by an approval (paths 3 and 5) ---


def test_approving_a_phone_raised_debt_queues_a_receipt(
    client, owner_auth, buyer_auth
):
    """Path 3: the seller wrote veresiye from their phone, so nobody was at the till."""
    approval_id = client.post(
        "/approvals", headers=owner_auth, json=_seller_request()
    ).json()["approval_id"]

    _approve_as_buyer(client, buyer_auth, approval_id)

    jobs = client.get("/pgw-jobs", headers=owner_auth).json()
    assert len(jobs) == 1
    assert jobs[0]["kind"] == "RECEIPT"
    assert jobs[0]["amount_minor"] == 5000
    assert jobs[0]["customer_id"] == "c1"
    # The slip needs an orderBody; the gateway keys its receipt on it.
    assert '"type":17' in jobs[0]["order_body"]


def test_the_receipt_body_matches_the_gateway_shape(client, owner_auth, buyer_auth):
    """
    Asserted field by field because a real terminal refused the earlier shape, and the
    substring check above would have passed for every version it refused: the document
    type was wrong, `items` was missing, and no customer was named.
    """
    approval_id = client.post(
        "/approvals", headers=owner_auth, json=_seller_request()
    ).json()["approval_id"]

    _approve_as_buyer(client, buyer_auth, approval_id)

    jobs = client.get("/pgw-jobs", headers=owner_auth).json()
    body = json.loads(jobs[0]["order_body"])

    assert body["documentType"] == 0        # 9002 was rejected for this class
    assert body["items"] == []              # key present, filled in once baskets are wired
    assert body["paymentItems"] == [{"amount": 5000, "type": 17}]
    assert body["customerInfo"] == {"name": "Ahmet Yılmaz"}
    assert body["createInvoice"] is False
    assert body["isVoid"] is False
    assert body["basketID"]


def test_a_nameless_customer_gets_no_customerInfo(
    client, owner_auth, buyer_auth, db_session
):
    """
    An account may genuinely have no display name (registration leaves it empty). Sending
    customerInfo with an empty name would fail the gateway's check while looking like an
    answer, so the block is left out entirely.
    """
    db_session.get(models.Customer, "c1").display_name = ""
    db_session.commit()

    approval_id = client.post(
        "/approvals", headers=owner_auth, json=_seller_request()
    ).json()["approval_id"]

    _approve_as_buyer(client, buyer_auth, approval_id)

    jobs = client.get("/pgw-jobs", headers=owner_auth).json()
    assert "customerInfo" not in json.loads(jobs[0]["order_body"])


def test_approving_a_pos_raised_sale_queues_nothing(client, owner_auth, buyer_auth):
    """The terminal is standing at the gateway and fires its own intent."""
    approval_id = client.post(
        "/approvals", headers=owner_auth, json=_seller_request(origin="POS")
    ).json()["approval_id"]

    _approve_as_buyer(client, buyer_auth, approval_id)

    assert client.get("/pgw-jobs", headers=owner_auth).json() == []


def test_approving_a_buyer_payment_queues_a_collect(client, owner_auth, buyer_auth):
    """
    Path 5: the buyer offered to pay, the shop agreed, and the money still has to be
    taken by card AT the till -- so the terminal opens the gateway rather than printing.
    """
    approval_id = client.post(
        "/approvals",
        headers=buyer_auth,
        json=_seller_request(
            initiator_role="BUYER", type="PAYMENT", target_user_id="u_owner"
        ),
    ).json()["approval_id"]

    client.post(f"/approvals/{approval_id}/approve", headers=owner_auth)

    jobs = client.get("/pgw-jobs", headers=owner_auth).json()
    assert len(jobs) == 1
    assert jobs[0]["kind"] == "COLLECT"
    # No slip to print: the gateway builds its own basket for a card payment.
    assert jobs[0]["order_body"] is None


def test_a_rejected_approval_queues_nothing(client, owner_auth, buyer_auth):
    approval_id = client.post(
        "/approvals", headers=owner_auth, json=_seller_request()
    ).json()["approval_id"]

    client.post(f"/approvals/{approval_id}/reject", headers=buyer_auth)

    assert client.get("/pgw-jobs", headers=owner_auth).json() == []


def test_the_job_and_its_entry_share_one_commit(client, owner_auth, buyer_auth, db_session):
    """A receipt for an entry that rolled back would be a slip for a debt nobody owes."""
    approval_id = client.post(
        "/approvals", headers=owner_auth, json=_seller_request()
    ).json()["approval_id"]

    transaction_id = _approve_as_buyer(client, buyer_auth, approval_id).json()[
        "transaction_id"
    ]

    job = db_session.query(models.PgwJob).one()
    assert job.transaction_id == transaction_id


# --- jobs a seller raises directly (path 4) ---


def test_a_seller_can_queue_a_collect_for_their_own_till(client, owner_auth):
    response = client.post(
        "/pgw-jobs",
        headers=owner_auth,
        json={"kind": "COLLECT", "customer_id": "c1", "amount_minor": 2500},
    )
    assert response.status_code == 201
    assert response.json()["seller_id"] == "u_owner"
    assert response.json()["status"] == "PENDING"


def test_queueing_a_collect_writes_nothing_to_the_ledger(client, owner_auth):
    """Asking the gateway to open is not evidence that anybody paid."""
    before = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]

    client.post(
        "/pgw-jobs",
        headers=owner_auth,
        json={"kind": "COLLECT", "customer_id": "c1", "amount_minor": 2500},
    )

    after = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]
    assert after == before


def test_a_receipt_cannot_be_requested_by_a_client(client, owner_auth):
    """A slip accompanies an entry, and this endpoint writes none."""
    response = client.post(
        "/pgw-jobs",
        headers=owner_auth,
        json={"kind": "RECEIPT", "customer_id": "c1", "amount_minor": 2500},
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_kind"


def test_a_seller_cannot_queue_against_a_customer_outside_their_book(
    client, owner_auth
):
    # m1 is u_market's record for the same person; u_owner has never charged them.
    response = client.post(
        "/pgw-jobs",
        headers=owner_auth,
        json={"kind": "COLLECT", "customer_id": "m1", "amount_minor": 2500},
    )
    assert response.status_code == 403
    assert response.json()["error"]["code"] == "not_in_book"


def test_a_buyer_only_account_has_no_till(client, buyer_auth):
    response = client.post(
        "/pgw-jobs",
        headers=buyer_auth,
        json={"kind": "COLLECT", "customer_id": "c1", "amount_minor": 2500},
    )
    assert response.status_code == 403
    assert response.json()["error"]["code"] == "not_a_seller"


# --- fetching and acking ---


def test_a_terminal_only_sees_its_own_work(client, owner_auth, market_auth):
    client.post(
        "/pgw-jobs",
        headers=owner_auth,
        json={"kind": "COLLECT", "customer_id": "c1", "amount_minor": 2500},
    )

    assert client.get("/pgw-jobs", headers=market_auth).json() == []
    assert len(client.get("/pgw-jobs", headers=owner_auth).json()) == 1


def test_an_acked_job_stops_being_returned(client, owner_auth):
    job_id = client.post(
        "/pgw-jobs",
        headers=owner_auth,
        json={"kind": "COLLECT", "customer_id": "c1", "amount_minor": 2500},
    ).json()["job_id"]

    assert client.post(f"/pgw-jobs/{job_id}/ack", headers=owner_auth).status_code == 200
    assert client.get("/pgw-jobs", headers=owner_auth).json() == []


def test_acking_twice_is_not_an_error(client, owner_auth):
    """
    The terminal fires the intent, THEN acks. A lost response leaves it holding work it
    has really delivered, and retrying is the only safe move -- so a second ack must not
    fail, or the job would stay pending and the receipt print on every poll.
    """
    job_id = client.post(
        "/pgw-jobs",
        headers=owner_auth,
        json={"kind": "COLLECT", "customer_id": "c1", "amount_minor": 2500},
    ).json()["job_id"]

    client.post(f"/pgw-jobs/{job_id}/ack", headers=owner_auth)
    second = client.post(f"/pgw-jobs/{job_id}/ack", headers=owner_auth)

    assert second.status_code == 200
    assert second.json()["status"] == "DELIVERED"


def test_a_terminal_cannot_ack_another_shops_job(client, owner_auth, market_auth):
    job_id = client.post(
        "/pgw-jobs",
        headers=owner_auth,
        json={"kind": "COLLECT", "customer_id": "c1", "amount_minor": 2500},
    ).json()["job_id"]

    response = client.post(f"/pgw-jobs/{job_id}/ack", headers=market_auth)
    assert response.status_code == 403
    # And it is still waiting for the shop it belongs to.
    assert len(client.get("/pgw-jobs", headers=owner_auth).json()) == 1


def test_acking_an_unknown_job_is_a_404(client, owner_auth):
    response = client.post("/pgw-jobs/pj_nope/ack", headers=owner_auth)
    assert response.status_code == 404


def test_jobs_come_back_oldest_first(client, owner_auth):
    """A queue is worked through in order; receipts should follow the sales."""
    first = client.post(
        "/pgw-jobs",
        headers=owner_auth,
        json={"kind": "COLLECT", "customer_id": "c1", "amount_minor": 100},
    ).json()["job_id"]
    second = client.post(
        "/pgw-jobs",
        headers=owner_auth,
        json={"kind": "COLLECT", "customer_id": "c1", "amount_minor": 200},
    ).json()["job_id"]

    returned = [job["job_id"] for job in client.get("/pgw-jobs", headers=owner_auth).json()]
    assert returned == [first, second]
