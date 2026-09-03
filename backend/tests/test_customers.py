"""
The seller's book: scoping, derived balances, and the shared-customer rule.

The load-bearing idea here is that a customer ROW is shared between shops while the
BALANCE is per (seller, customer) pair. Most of these tests exist to pin one half of that
down without the other quietly drifting.
"""


def test_list_is_scoped_to_the_signed_in_seller(client, owner_auth):
    response = client.get("/customers", headers=owner_auth)
    assert response.status_code == 200

    ids = {c["customer_id"] for c in response.json()}
    assert ids == {"c1", "c2", "c3", "c4", "c5"}
    # m1 and o1 are the other shop's book and must not leak in.
    assert "m1" not in ids and "o1" not in ids


def test_list_balances_are_derived(client, owner_auth):
    balances = {c["customer_id"]: c["balance_minor"] for c in client.get(
        "/customers", headers=owner_auth
    ).json()}

    assert balances == {"c1": 49000, "c2": 16500, "c3": 0, "c4": 2550, "c5": 21000}


def test_list_requires_a_seller_account(client, buyer_auth):
    assert client.get("/customers", headers=buyer_auth).status_code == 403


def test_list_requires_a_token(client):
    assert client.get("/customers").status_code == 401


def test_the_same_person_carries_a_different_balance_per_shop(client, owner_auth):
    # u1 is c1 at Ahmet Bakkal (40,00) and m1 at Ayşe Market (100,00). One human, two
    # books, two numbers -- this is why balance is never stored on the customer row.
    owner_view = client.get("/customers/c1", headers=owner_auth).json()
    assert owner_view["balance_minor"] == 49000

    market = client.post(
        "/auth/otp/verify", json={"phone": "+905553334455", "code": "123456"}
    ).json()
    market_auth = {"Authorization": f"Bearer {market['token']}"}
    market_view = client.get("/customers/m1", headers=market_auth).json()
    assert market_view["balance_minor"] == 10000


def test_get_by_id_reports_zero_for_a_customer_this_seller_never_charged(
    client, owner_auth
):
    # m1 belongs to the other shop's book. The record is visible, but what they owe
    # elsewhere is not -- the balance is scoped to the asking seller.
    response = client.get("/customers/m1", headers=owner_auth)
    assert response.status_code == 200
    assert response.json()["balance_minor"] == 0


def test_get_by_id_404s_for_an_unknown_customer(client, owner_auth):
    response = client.get("/customers/nope", headers=owner_auth)
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "customer_not_found"


def test_create_customer_starts_unclaimed_with_zero_balance(client, owner_auth):
    response = client.post(
        "/customers",
        headers=owner_auth,
        json={"display_name": "Yeni Müşteri", "phone": "0555 000 1122"},
    )
    assert response.status_code == 201

    body = response.json()
    # A record of what the shopkeeper knows, not a verified account.
    assert body["claim_status"] == "UNCLAIMED"
    assert body["claimed_by_user_id"] is None
    assert body["balance_minor"] == 0
    assert body["phone"] == "+905550001122"


def test_a_created_customer_appears_in_the_book_before_being_charged(
    client, owner_auth
):
    # The reason created_by_seller_id exists. Membership is otherwise derived from the
    # ledger, and a customer added but not yet charged has no ledger rows -- so without
    # that column POST /customers would succeed and the customer would never show up.
    created = client.post(
        "/customers",
        headers=owner_auth,
        json={"display_name": "Yeni Müşteri", "phone": "05550001122"},
    ).json()

    listed = client.get("/customers", headers=owner_auth).json()
    assert created["customer_id"] in {c["customer_id"] for c in listed}


def test_create_rejects_a_duplicate_in_the_same_book(client, owner_auth):
    response = client.post(
        "/customers",
        headers=owner_auth,
        json={"display_name": "Ahmet Tekrar", "phone": "+905551112233"},
    )
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "customer_exists"


def test_two_shops_may_both_know_the_same_person(client, owner_auth):
    # c5 is in Ahmet Bakkal's book. Ayşe Market has never charged them, so writing them
    # down is the normal case -- a global unique phone would wrongly reject this.
    market = client.post(
        "/auth/otp/verify", json={"phone": "+905553334455", "code": "123456"}
    ).json()
    response = client.post(
        "/customers",
        headers={"Authorization": f"Bearer {market['token']}"},
        json={"display_name": "Hasan Öztürk", "phone": "+905558889900"},
    )
    assert response.status_code == 201


def test_create_normalises_the_phone_before_the_duplicate_check(client, owner_auth):
    # Typed differently, same person: the check must run on the canonical form or the
    # same human ends up twice in one book.
    response = client.post(
        "/customers",
        headers=owner_auth,
        json={"display_name": "Ahmet Tekrar", "phone": "0555 111 22 33"},
    )
    assert response.status_code == 409


def test_create_rejects_a_malformed_phone(client, owner_auth):
    response = client.post(
        "/customers", headers=owner_auth, json={"display_name": "X", "phone": "12345"}
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_phone"


def test_create_rejects_an_empty_name(client, owner_auth):
    response = client.post(
        "/customers", headers=owner_auth, json={"display_name": "", "phone": "05550001122"}
    )
    assert response.status_code == 400


def test_create_requires_a_seller_account(client, buyer_auth):
    response = client.post(
        "/customers", headers=buyer_auth, json={"display_name": "X", "phone": "05550001122"}
    )
    assert response.status_code == 403


def test_lookup_finds_a_customer_by_phone(client, owner_auth):
    response = client.get(
        "/customers/lookup", headers=owner_auth, params={"phone": "05551112233"}
    )
    assert response.status_code == 200
    assert response.json()["customer_id"] == "c1"
    assert response.json()["balance_minor"] == 49000


def test_lookup_matches_the_literal_path_not_the_id_route(client, owner_auth):
    # /customers/{id} would happily capture "lookup" as an id. Route order is what keeps
    # this endpoint reachable, and a reordering would break it silently.
    response = client.get(
        "/customers/lookup", headers=owner_auth, params={"phone": "05551112233"}
    )
    assert response.status_code == 200
    assert "customer_id" in response.json()


def test_lookup_finds_another_shops_customer_but_reports_zero(client, owner_auth):
    # A person Ayşe Market knows, whom this seller has never charged. Finding them is the
    # point -- it stops the shopkeeper retyping someone the system already knows -- while
    # the balance stays scoped so no shop learns what is owed elsewhere.
    response = client.get(
        "/customers/lookup", headers=owner_auth, params={"phone": "+905553334455"}
    )
    assert response.status_code == 404  # u_market is a user, not a customer row

    # o1 IS a customer row (u_owner as a buyer at the other shop).
    response = client.get(
        "/customers/lookup", headers=owner_auth, params={"phone": "+905554443322"}
    )
    assert response.status_code == 200
    assert response.json()["balance_minor"] == 0


def test_lookup_404s_when_nobody_holds_the_number(client, owner_auth):
    response = client.get(
        "/customers/lookup", headers=owner_auth, params={"phone": "05559998877"}
    )
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "customer_not_found"


def test_lookup_rejects_a_malformed_phone(client, owner_auth):
    response = client.get(
        "/customers/lookup", headers=owner_auth, params={"phone": "12345"}
    )
    assert response.status_code == 400


def test_created_customer_carries_the_book_it_belongs_to(client, owner_auth):
    # On the wire because the client cannot derive it: a customer with no ledger entries
    # belongs to a book by this field alone. Without it the device lists only people it
    # has already charged, so someone just added stays invisible until their first entry.
    body = client.post(
        "/customers",
        headers=owner_auth,
        json={"display_name": "Test Ali", "phone": "05559998877"},
    ).json()

    assert body["created_by_seller_id"] == "u_owner"


def test_listed_customers_carry_created_by_seller_id(client, owner_auth):
    rows = client.get("/customers", headers=owner_auth).json()
    assert all("created_by_seller_id" in row for row in rows)
