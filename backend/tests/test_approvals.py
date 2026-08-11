"""
The approval gate.

The rule under test throughout: the COUNTERPARTY approves, never the initiator. A gate
the requester can pass on their own is decorative, and app-mobile had exactly that bug
once (Tur 24b) -- deriving the approver from the customer record alone sent a
buyer-initiated payment back to the buyer.
"""

from app import models


def _seller_request(**overrides) -> dict:
    body = {
        "seller_id": "u_owner",
        "customer_id": "c1",       # CLAIMED by u1
        "amount_minor": 5000,
        "type": "DEBT",
        "description": "Ekmek, süt",
        "initiator_role": "SELLER",
        "target_user_id": "u1",
    }
    body.update(overrides)
    return body


# --- raising a request ---


def test_a_claimed_counterparty_gets_a_pending_row(client, owner_auth):
    response = client.post("/approvals", headers=owner_auth, json=_seller_request())
    assert response.status_code == 201

    body = response.json()
    assert body["status"] == "PENDING"
    assert body["target_user_id"] == "u1"       # the customer decides, not the shop
    assert body["initiator_user_id"] == "u_owner"
    assert body["channel"] == "APP_PUSH"
    assert body["shop_name"] == "Ahmet Bakkal"


def test_a_pending_request_writes_nothing_to_the_ledger(client, owner_auth):
    before = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]

    client.post("/approvals", headers=owner_auth, json=_seller_request())

    after = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]
    assert after == before


def test_an_unclaimed_counterparty_is_written_immediately(client, owner_auth):
    # c4 (Fatma) has no account, so nobody can tap approve. This is the SMS-OTP branch:
    # 200 with the Transaction rather than 201 with an Approval.
    response = client.post(
        "/approvals",
        headers=owner_auth,
        json=_seller_request(customer_id="c4", target_user_id="", amount_minor=1000),
    )
    assert response.status_code == 200
    assert response.json()["transaction_id"]

    balance = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c4"}
    ).json()["balance_minor"]
    assert balance == 2550 + 1000


def test_initiator_user_id_comes_from_the_token(client, owner_auth):
    response = client.post(
        "/approvals", headers=owner_auth, json=_seller_request(initiator_user_id="u1")
    )
    assert response.json()["initiator_user_id"] == "u_owner"


def test_a_seller_cannot_raise_a_request_for_another_shop(client, owner_auth):
    # Plan §0.3. seller_id is in the body because a buyer may start this too, which makes
    # it the one field worth attacking: without this check anyone could open requests
    # against a stranger's book.
    response = client.post(
        "/approvals", headers=owner_auth, json=_seller_request(seller_id="u_market")
    )
    assert response.status_code == 403


def test_a_buyer_can_initiate_a_payment_to_the_shop(client, buyer_auth):
    # The mirror direction: the buyer declares a payment and the SHOP confirms receipt.
    response = client.post(
        "/approvals",
        headers=buyer_auth,
        json={
            "seller_id": "u_owner",
            "customer_id": "c1",
            "amount_minor": 2000,
            "type": "PAYMENT",
            "description": "Ödeme",
            "initiator_role": "BUYER",
            "target_user_id": "u_owner",
        },
    )
    assert response.status_code == 201
    # Back to the shop, NOT to the buyer who started it.
    assert response.json()["target_user_id"] == "u_owner"


def test_a_buyer_cannot_act_on_someone_elses_record(client, buyer_auth):
    # c4 is not u1's record; otherwise a buyer could declare payments into another
    # person's book.
    response = client.post(
        "/approvals",
        headers=buyer_auth,
        json={
            "seller_id": "u_owner",
            "customer_id": "c4",
            "amount_minor": 2000,
            "type": "PAYMENT",
            "initiator_role": "BUYER",
            "target_user_id": "u_owner",
        },
    )
    assert response.status_code == 403


def test_request_rejects_an_unknown_type(client, owner_auth):
    response = client.post(
        "/approvals", headers=owner_auth, json=_seller_request(type="REFUND")
    )
    assert response.status_code == 400


def test_request_rejects_an_unknown_role(client, owner_auth):
    response = client.post(
        "/approvals", headers=owner_auth, json=_seller_request(initiator_role="ADMIN")
    )
    assert response.status_code == 400


def test_request_404s_for_an_unknown_customer(client, owner_auth):
    response = client.post(
        "/approvals", headers=owner_auth, json=_seller_request(customer_id="nobody")
    )
    assert response.status_code == 404


def test_request_requires_a_token(client):
    assert client.post("/approvals", json=_seller_request()).status_code == 401


# --- the pending list ---


def test_pending_shows_what_is_addressed_to_me(client, buyer_auth):
    # Seeded p1 waits on u1.
    rows = client.get("/approvals", headers=buyer_auth).json()
    assert [r["approval_id"] for r in rows] == ["p1"]


def test_pending_does_not_show_other_peoples_approvals(client, owner_auth):
    # p2 waits on u_owner, p1 on u1. The shopkeeper must not see p1.
    rows = client.get("/approvals", headers=owner_auth).json()
    assert [r["approval_id"] for r in rows] == ["p2"]


def test_pending_requires_a_token(client):
    assert client.get("/approvals").status_code == 401


# --- deciding ---


def test_approve_writes_the_entry(client, buyer_auth, owner_auth):
    before = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]

    response = client.post("/approvals/p1/approve", headers=buyer_auth)
    assert response.status_code == 200

    written = response.json()
    assert written["amount_minor"] == 5000
    assert written["seller_id"] == "u_owner"
    # The record the request was raised against, not re-resolved: u1 also holds m1, and
    # re-guessing could book the entry into the other shop.
    assert written["customer_id"] == "c1"

    after = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]
    assert after == before + 5000


def test_approving_clears_it_from_the_pending_list(client, buyer_auth):
    client.post("/approvals/p1/approve", headers=buyer_auth)
    assert client.get("/approvals", headers=buyer_auth).json() == []


def test_a_decided_row_is_kept_for_the_audit_trail(client, buyer_auth, db_session):
    client.post("/approvals/p1/approve", headers=buyer_auth)

    # Gone from the screen, still on disk: the pending query filters on status rather
    # than the row being deleted.
    row = db_session.get(models.Approval, "p1")
    assert row is not None
    assert row.status == "APPROVED"


def test_reject_writes_nothing(client, buyer_auth, owner_auth):
    before = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]

    response = client.post("/approvals/p1/reject", headers=buyer_auth)
    assert response.status_code == 204

    after = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]
    assert after == before


def test_a_rejected_row_is_kept_too(client, buyer_auth, db_session):
    client.post("/approvals/p1/reject", headers=buyer_auth)

    row = db_session.get(models.Approval, "p1")
    assert row is not None
    assert row.status == "REJECTED"


def test_the_initiator_cannot_approve_their_own_request(client, owner_auth):
    # The whole point of the gate. p1 was raised BY u_owner and waits on u1.
    response = client.post("/approvals/p1/approve", headers=owner_auth)
    assert response.status_code == 403


def test_a_stranger_cannot_approve(client):
    client.post("/users", json={"phone": "05550001122", "is_seller": False})
    token = client.post(
        "/auth/otp/verify", json={"phone": "05550001122", "code": "123456"}
    ).json()["token"]

    response = client.post(
        "/approvals/p1/approve", headers={"Authorization": f"Bearer {token}"}
    )
    assert response.status_code == 403


def test_approving_twice_conflicts(client, buyer_auth):
    # Without this guard a double tap (or a retried request) would append the entry twice.
    client.post("/approvals/p1/approve", headers=buyer_auth)
    second = client.post("/approvals/p1/approve", headers=buyer_auth)

    assert second.status_code == 409
    assert second.json()["error"]["code"] == "already_decided"


def test_a_rejected_request_cannot_then_be_approved(client, buyer_auth):
    client.post("/approvals/p1/reject", headers=buyer_auth)
    assert client.post("/approvals/p1/approve", headers=buyer_auth).status_code == 409


def test_decide_404s_for_an_unknown_approval(client, buyer_auth):
    assert client.post("/approvals/nope/approve", headers=buyer_auth).status_code == 404


def test_approve_requires_a_token(client):
    assert client.post("/approvals/p1/approve").status_code == 401


# --- the round trip ---


def test_a_request_approved_reaches_the_buyers_debt_list(client, owner_auth, buyer_auth):
    # End to end: the shop asks, the buyer agrees, and the debt appears on their screen.
    before = {
        r["seller_id"]: r["balance_minor"]
        for r in client.get("/me/debts", headers=buyer_auth).json()
    }["u_owner"]

    raised = client.post(
        "/approvals", headers=owner_auth, json=_seller_request(amount_minor=3000)
    ).json()
    client.post(f"/approvals/{raised['approval_id']}/approve", headers=buyer_auth)

    after = {
        r["seller_id"]: r["balance_minor"]
        for r in client.get("/me/debts", headers=buyer_auth).json()
    }["u_owner"]
    assert after == before + 3000
