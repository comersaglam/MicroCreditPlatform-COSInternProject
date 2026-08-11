"""Registration and profile behaviour."""


def test_register_creates_a_new_user(client):
    response = client.post(
        "/users",
        json={"phone": "05550001122", "display_name": "Yeni Kullanıcı", "is_seller": False},
    )
    assert response.status_code == 201

    body = response.json()
    assert body["phone"] == "+905550001122"   # stored canonically, not as typed
    assert body["is_buyer"] is True           # everyone is a buyer
    assert body["is_seller"] is False
    assert body["seller_info"] is None


def test_register_is_idempotent_by_phone(client):
    # The client lands here after verify returns 404, and a retry (a dropped response, a
    # double tap) must not become an error the user has to interpret.
    first = client.post("/users", json={"phone": "05550001122", "is_seller": False})
    second = client.post("/users", json={"phone": "05550001122", "is_seller": False})

    assert first.status_code == 201
    assert second.status_code == 200
    assert first.json()["user_id"] == second.json()["user_id"]


def test_register_does_not_overwrite_an_existing_profile(client):
    # This endpoint needs no authentication, so letting it edit an existing row would let
    # anyone rewrite a stranger's name by "registering" their number.
    response = client.post(
        "/users",
        json={"phone": "05554443322", "display_name": "Sahte İsim", "is_seller": False},
    )
    assert response.status_code == 200
    assert response.json()["display_name"] == "Ahmet Demirtaş"
    assert response.json()["is_seller"] is True


def test_register_rejects_a_malformed_phone(client):
    response = client.post("/users", json={"phone": "12345", "is_seller": False})
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_phone"


def test_register_then_sign_in(client):
    # The two-step flow the 404 from verify exists to enable.
    client.post("/users", json={"phone": "05550001122", "is_seller": False})
    signed_in = client.post(
        "/auth/otp/verify", json={"phone": "05550001122", "code": "123456"}
    )
    assert signed_in.status_code == 200


def test_me_returns_the_token_holder(client, owner_auth):
    response = client.get("/users/me", headers=owner_auth)
    assert response.status_code == 200
    assert response.json()["user_id"] == "u_owner"


def test_me_requires_a_token(client):
    assert client.get("/users/me").status_code == 401


def test_patch_updates_name_and_email(client, owner_auth):
    response = client.patch(
        "/users/me",
        headers=owner_auth,
        json={"display_name": "Ahmet D.", "email": "ahmet@example.com"},
    )
    assert response.status_code == 200
    assert response.json()["display_name"] == "Ahmet D."
    assert response.json()["email"] == "ahmet@example.com"


def test_patch_leaves_omitted_fields_alone(client, owner_auth):
    client.patch("/users/me", headers=owner_auth, json={"email": "a@example.com"})
    response = client.patch("/users/me", headers=owner_auth, json={"display_name": "Yeni"})

    # The email survived a request that did not mention it.
    assert response.json()["email"] == "a@example.com"
    assert response.json()["display_name"] == "Yeni"


def test_patch_can_clear_the_email(client, owner_auth):
    # "Omitted" and "sent as null" must stay distinguishable, or clearing a field is
    # impossible to express.
    client.patch("/users/me", headers=owner_auth, json={"email": "a@example.com"})
    response = client.patch("/users/me", headers=owner_auth, json={"email": None})
    assert response.json()["email"] is None


def test_patch_rejects_an_empty_name(client, owner_auth):
    response = client.patch("/users/me", headers=owner_auth, json={"display_name": ""})
    assert response.status_code == 400


def test_patch_cannot_change_the_phone(client, owner_auth):
    # Phone is the identity the account is keyed by; moving it belongs behind its own OTP
    # flow, not a profile edit. The field is not in UserPatch, so it is simply ignored.
    client.patch("/users/me", headers=owner_auth, json={"phone": "05550009988"})
    assert client.get("/users/me", headers=owner_auth).json()["phone"] == "+905554443322"


def test_become_seller_sets_flag_and_shop_together(client, buyer_auth):
    response = client.post(
        "/users/me/become-seller",
        headers=buyer_auth,
        json={"shop_name": "Yılmaz Bakkal", "shop_phone": "+902129998877"},
    )
    assert response.status_code == 200

    body = response.json()
    # Both, or neither: the domain type says is_seller <=> seller_info != null, and a
    # flag without a shop name would serialise into a shape the client cannot hold.
    assert body["is_seller"] is True
    assert body["seller_info"] == {
        "shop_name": "Yılmaz Bakkal",
        "shop_phone": "+902129998877",
    }


def test_become_seller_again_renames_the_shop(client, owner_auth):
    response = client.post(
        "/users/me/become-seller", headers=owner_auth, json={"shop_name": "Yeni Ad"}
    )
    assert response.json()["seller_info"]["shop_name"] == "Yeni Ad"


def test_become_seller_rejects_an_empty_shop_name(client, buyer_auth):
    response = client.post(
        "/users/me/become-seller", headers=buyer_auth, json={"shop_name": ""}
    )
    assert response.status_code == 400


def test_become_seller_opens_the_customer_endpoints(client, buyer_auth):
    # A buyer-only account has no book; becoming a seller is what creates one.
    assert client.get("/customers", headers=buyer_auth).status_code == 403
    client.post("/users/me/become-seller", headers=buyer_auth, json={"shop_name": "Dükkan"})
    assert client.get("/customers", headers=buyer_auth).status_code == 200
