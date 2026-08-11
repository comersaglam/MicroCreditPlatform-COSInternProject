"""
Buyer-scoped reads -- the seller's book seen from the other side.

The seed makes u1 one person with a record in each shop (c1 at Ahmet Bakkal, m1 at Ayşe
Market), which is what these endpoints have to get right: the same human, two books, two
numbers.
"""


def test_debts_are_grouped_by_shop(client, buyer_auth):
    rows = client.get("/me/debts", headers=buyer_auth).json()

    by_seller = {r["seller_id"]: r["balance_minor"] for r in rows}
    assert by_seller == {"u_owner": 4000, "u_market": 10000}


def test_debts_carry_the_shop_name_not_the_persons(client, buyer_auth):
    rows = client.get("/me/debts", headers=buyer_auth).json()

    names = {r["seller_id"]: r["shop_name"] for r in rows}
    # The seller's own name is Ahmet Demirtaş; the buyer's card must show the shop.
    assert names == {"u_owner": "Ahmet Bakkal", "u_market": "Ayşe Market"}


def test_debts_omit_shops_the_buyer_is_square_with(client, owner_auth):
    # u_owner owes Ayşe Market 60,00 (o1) and nothing anywhere else. A zero row is not a
    # debt, and listing it would show shops with nothing outstanding.
    rows = client.get("/me/debts", headers=owner_auth).json()
    assert [r["seller_id"] for r in rows] == ["u_market"]
    assert rows[0]["balance_minor"] == 6000


def test_debts_are_empty_for_someone_who_owes_nothing(client):
    client.post("/users", json={"phone": "05550001122", "is_seller": False})
    token = client.post(
        "/auth/otp/verify", json={"phone": "05550001122", "code": "123456"}
    ).json()["token"]

    rows = client.get("/me/debts", headers={"Authorization": f"Bearer {token}"}).json()
    assert rows == []


def test_debts_require_a_token(client):
    assert client.get("/me/debts").status_code == 401


def test_history_is_scoped_to_one_shop(client, buyer_auth):
    at_owner = client.get(
        "/me/transactions", headers=buyer_auth, params={"seller_id": "u_owner"}
    ).json()
    at_market = client.get(
        "/me/transactions", headers=buyer_auth, params={"seller_id": "u_market"}
    ).json()

    # One person, two books: the entries must not mix.
    assert [t["transaction_id"] for t in at_owner] == ["t3", "t2", "t1"]
    assert [t["transaction_id"] for t in at_market] == ["t6", "t5", "t4"]


def test_history_is_empty_for_a_shop_with_no_shared_past(client, buyer_auth):
    # Not an error: a buyer asking about a shop they have never dealt with sees nothing.
    rows = client.get(
        "/me/transactions", headers=buyer_auth, params={"seller_id": "u3"}
    ).json()
    assert rows == []


def test_buyer_balance_matches_what_the_seller_sees(client, buyer_auth, owner_auth):
    # The same entry read from both directions must produce the same number, or the two
    # apps would disagree about the same debt.
    buyer_view = client.get(
        "/me/balances", headers=buyer_auth, params={"seller_id": "u_owner"}
    ).json()
    seller_view = client.get(
        "/balances", headers=owner_auth, params={"customer_id": "c1"}
    ).json()

    assert buyer_view["balance_minor"] == seller_view["balance_minor"] == 4000


def test_buyer_balance_is_zero_with_an_unknown_shop(client, buyer_auth):
    response = client.get(
        "/me/balances", headers=buyer_auth, params={"seller_id": "u3"}
    )
    assert response.status_code == 200
    assert response.json()["balance_minor"] == 0


def test_a_new_entry_shows_up_on_the_buyers_side(client, owner_auth, buyer_auth):
    # The write path and the buyer read path must describe one ledger.
    client.post(
        "/transactions",
        headers={**owner_auth, "Idempotency-Key": "tx-buyer-0001"},
        json={
            "transaction_id": "tx-buyer-0001",
            "customer_id": "c1",
            "amount_minor": 1000,
            "type": "DEBT",
            "description": "Yeni borç",
        },
    )

    debts = {
        r["seller_id"]: r["balance_minor"]
        for r in client.get("/me/debts", headers=buyer_auth).json()
    }
    assert debts["u_owner"] == 5000
