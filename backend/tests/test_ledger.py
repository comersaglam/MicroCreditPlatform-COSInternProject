"""
The ledger write path.

Most of these tests encode a promise the client's outbox already relies on. SyncEngine
deletes a queue entry as soon as it sees a 2xx, and it deletes it on a 4xx too -- so a
server that duplicated a replay, or that silently overwrote an entry, would corrupt a
book that nobody is watching at the time.
"""

from app import models


def _entry(**overrides) -> dict:
    body = {
        "transaction_id": "tx-test-0001",
        "customer_id": "c1",
        "amount_minor": 2500,
        "type": "DEBT",
        "description": "Test veresiye",
    }
    body.update(overrides)
    return body


def _post(client, auth, body: dict, key: str | None = None):
    return client.post(
        "/transactions",
        headers={**auth, "Idempotency-Key": key or body["transaction_id"]},
        json=body,
    )


# --- writing ---


def test_create_appends_an_entry(client, owner_auth):
    response = _post(client, owner_auth, _entry())
    assert response.status_code == 201

    body = response.json()
    assert body["transaction_id"] == "tx-test-0001"
    assert body["amount_minor"] == 2500
    assert body["type"] == "DEBT"
    # Server-owned fields the client never sends.
    assert body["settled_via_pgw"] is False
    assert body["created_at"]


def test_seller_id_comes_from_the_token(client, owner_auth):
    # TransactionCreate has no seller_id field at all; even so, a body that smuggles one
    # must not decide whose book is written to.
    response = _post(client, owner_auth, _entry(seller_id="u_market"))
    assert response.status_code == 201
    assert response.json()["seller_id"] == "u_owner"


def test_a_new_entry_moves_the_balance(client, owner_auth):
    before = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]

    _post(client, owner_auth, _entry(amount_minor=1000, type="DEBT"))
    after = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]

    assert after == before + 1000


def test_a_payment_subtracts(client, owner_auth):
    # The amount is always positive; the sign lives in the type.
    before = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]

    _post(client, owner_auth, _entry(amount_minor=1000, type="PAYMENT"))
    after = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]

    assert after == before - 1000


def test_create_requires_a_token(client):
    response = client.post(
        "/transactions", headers={"Idempotency-Key": "tx-test-0001"}, json=_entry()
    )
    assert response.status_code == 401


def test_create_requires_the_idempotency_header(client, owner_auth):
    # Without it a retry would be indistinguishable from a second sale.
    response = client.post("/transactions", headers=owner_auth, json=_entry())
    assert response.status_code == 422


def test_create_rejects_a_key_that_differs_from_the_transaction_id(client, owner_auth):
    response = _post(client, owner_auth, _entry(), key="some-other-key")
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "idempotency_key_mismatch"


def test_create_rejects_an_unknown_type(client, owner_auth):
    # An unrecognised type contributes to neither side of the sum, so the entry would
    # exist while the money it represents silently vanished.
    response = _post(client, owner_auth, _entry(type="REFUND"))
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_type"


def test_create_rejects_a_negative_amount(client, owner_auth):
    response = _post(client, owner_auth, _entry(amount_minor=-100))
    assert response.status_code == 422


def test_create_404s_for_an_unknown_customer(client, owner_auth):
    response = _post(client, owner_auth, _entry(customer_id="nobody"))
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "customer_not_found"


# --- idempotency: the contract the outbox depends on ---


def test_replaying_the_same_entry_returns_the_original(client, owner_auth):
    first = _post(client, owner_auth, _entry())
    second = _post(client, owner_auth, _entry())

    assert first.status_code == 201
    assert second.status_code == 200          # a replay, not a new write
    assert first.json() == second.json()      # byte for byte, including created_at


def test_timestamps_use_the_exact_format_the_client_parses(client, owner_auth):
    """
    The client parses with SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"), matched
    LITERALLY: microseconds or a "+00:00" offset fail. The failure is silent -- TimeFormat
    falls back to the raw string, so a shopkeeper sees an ISO timestamp where a date
    belongs -- which is why this is pinned rather than left to the default serialiser.
    """
    import re

    pattern = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")

    written = _post(client, owner_auth, _entry()).json()
    assert pattern.match(written["created_at"]), written["created_at"]

    # The replay path had this wrong first: a datetime round-tripped through the database
    # came back without the Z, so a resend answered with an unparseable timestamp.
    replayed = _post(client, owner_auth, _entry()).json()
    assert pattern.match(replayed["created_at"]), replayed["created_at"]

    seeded = client.get(
        "/transactions", headers=owner_auth, params={"customer_id": "c1"}
    ).json()[-1]
    assert pattern.match(seeded["created_at"]), seeded["created_at"]

    balance = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()
    assert pattern.match(balance["as_of"]), balance["as_of"]

    session = client.post(
        "/auth/otp/verify", json={"phone": "+905554443322", "code": "123456"}
    ).json()
    assert pattern.match(session["expires_at"]), session["expires_at"]
    assert pattern.match(session["user"]["created_at"]), session["user"]["created_at"]


def test_a_replay_does_not_move_the_balance_twice(client, owner_auth):
    # The whole point. The device queues an entry, the response is lost, the queue drains
    # again -- and the shopkeeper must not end up having charged the customer twice.
    before = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]

    for _ in range(4):
        _post(client, owner_auth, _entry(amount_minor=1500))

    after = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()["balance_minor"]
    assert after == before + 1500


def test_a_replay_does_not_add_a_history_row(client, owner_auth):
    before = len(client.get(
        "/transactions", headers=owner_auth, params={"customer_id": "c1"}
    ).json())

    _post(client, owner_auth, _entry())
    _post(client, owner_auth, _entry())

    after = client.get(
        "/transactions", headers=owner_auth, params={"customer_id": "c1"}
    ).json()
    assert len(after) == before + 1


def test_the_same_key_with_a_different_body_conflicts(client, owner_auth):
    _post(client, owner_auth, _entry(amount_minor=2500))
    response = _post(client, owner_auth, _entry(amount_minor=9900))

    # Never a silent overwrite: the ledger is append-only, and a stale retry must not
    # rewrite history the shopkeeper and customer already agreed on.
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "idempotency_key_conflict"


def test_a_conflict_leaves_the_original_untouched(client, owner_auth):
    _post(client, owner_auth, _entry(amount_minor=2500))
    _post(client, owner_auth, _entry(amount_minor=9900))

    stored = client.get(
        "/transactions", headers=owner_auth, params={"customer_id": "c1"}
    ).json()
    written = [t for t in stored if t["transaction_id"] == "tx-test-0001"]
    assert len(written) == 1
    assert written[0]["amount_minor"] == 2500


def test_another_seller_cannot_reuse_the_key(client, owner_auth):
    # Same id, different book. Answering 200 with the original would leak one shop's
    # entry to another; answering 201 would overwrite it.
    _post(client, owner_auth, _entry())

    market = client.post(
        "/auth/otp/verify", json={"phone": "+905553334455", "code": "123456"}
    ).json()
    response = _post(
        client,
        {"Authorization": f"Bearer {market['token']}"},
        _entry(customer_id="m1"),
    )
    assert response.status_code == 409


# --- basket handoff ---


def test_an_entry_can_carry_a_basket(client, owner_auth):
    body = _entry(
        basket={
            "basket_id": "b-test-0001",
            "create_invoice": False,
            "document_type": 0,
            "is_void": False,
            "items": [
                {"name": "Ekmek", "price": 500, "quantity": 2000, "tax_percent": 1000},
                {"name": "Süt", "price": 3000, "quantity": 1000, "tax_percent": 1000},
            ],
        }
    )
    response = _post(client, owner_auth, body)
    assert response.status_code == 201
    assert response.json()["basket_id"] == "b-test-0001"


def test_replaying_a_basket_entry_does_not_duplicate_its_items(
    client, owner_auth, db_session
):
    body = _entry(
        basket={
            "basket_id": "b-test-0001",
            "items": [
                {"name": "Ekmek", "price": 500, "quantity": 2000, "tax_percent": 1000}
            ],
        }
    )
    _post(client, owner_auth, body)
    _post(client, owner_auth, body)

    items = db_session.query(models.BasketItem).filter_by(basket_id="b-test-0001").all()
    assert len(items) == 1


def test_a_money_only_entry_has_no_basket(client, owner_auth):
    response = _post(client, owner_auth, _entry())
    assert response.json()["basket_id"] is None


# --- reads ---


def test_history_is_newest_first(client, owner_auth):
    rows = client.get(
        "/transactions", headers=owner_auth, params={"customer_id": "c1"}
    ).json()

    assert [t["transaction_id"] for t in rows] == ["t3", "t2", "t1"]


def test_history_is_scoped_to_the_signed_in_seller(client, owner_auth):
    # m1's entries belong to Ayşe Market. Asking as u_owner must not surface them.
    rows = client.get(
        "/transactions", headers=owner_auth, params={"customer_id": "m1"}
    ).json()
    assert rows == []


def test_history_requires_a_token(client):
    assert client.get("/transactions", params={"customer_id": "c1"}).status_code == 401


def test_balance_matches_the_seeded_book(client, owner_auth):
    response = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    )
    assert response.status_code == 200

    body = response.json()
    assert body["balance_minor"] == 4000
    assert body["seller_id"] == "u_owner"
    assert body["as_of"]


def test_balance_is_zero_for_a_customer_with_no_entries(client, owner_auth):
    # "Owes nothing" is the right reading of an empty book, and it keeps this endpoint
    # agreeing with the sum the device computes over the same empty set.
    response = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "m1"}
    )
    assert response.status_code == 200
    assert response.json()["balance_minor"] == 0
