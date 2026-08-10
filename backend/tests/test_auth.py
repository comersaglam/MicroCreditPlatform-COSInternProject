"""
Auth endpoint behaviour the client already depends on.

Several of these encode decisions made long before the backend existed, so the tests
double as a record of why the endpoint answers the way it does.
"""

import time

import jwt

from app.config import settings


# --- OTP request ---


def test_request_otp_accepts_any_valid_number(client):
    response = client.post("/auth/otp/request", json={"phone": "05554443322"})
    assert response.status_code == 202
    assert response.json()["sent"] is True


def test_request_otp_does_not_reveal_whether_an_account_exists(client):
    # Same status either way. A different answer for a registered number would make this
    # endpoint an oracle for "which phone numbers have accounts here".
    known = client.post("/auth/otp/request", json={"phone": "+905554443322"})
    unknown = client.post("/auth/otp/request", json={"phone": "+905550000000"})
    assert known.status_code == unknown.status_code == 202
    assert known.json()["sent"] == unknown.json()["sent"] is True


def test_request_otp_channel_reflects_whether_the_user_has_the_app(client):
    # The client branches on this to word the screen ("koda bak" vs "SMS geldi").
    known = client.post("/auth/otp/request", json={"phone": "+905554443322"})
    unknown = client.post("/auth/otp/request", json={"phone": "+905550000000"})
    assert known.json()["channel"] == "APP_PUSH"
    assert unknown.json()["channel"] == "SMS_OTP"


def test_request_otp_rejects_a_malformed_number(client):
    response = client.post("/auth/otp/request", json={"phone": "12345"})
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_phone"


# --- OTP verify ---


def test_verify_returns_a_session_for_a_known_phone(client):
    response = client.post(
        "/auth/otp/verify", json={"phone": "05554443322", "code": "123456"}
    )
    assert response.status_code == 200

    body = response.json()
    assert body["user"]["user_id"] == "u_owner"
    assert body["token"]
    # Nullable in the contract, always present here: TokenAuthenticator cannot refresh
    # without it and would drop the shopkeeper to the login gate mid-sale (plan §0.8).
    assert body["refresh_token"]


def test_verify_does_not_auto_register(client):
    # Firm decision (api-endpoints.md A.1): an unknown phone is a 404 and the client
    # registers through POST /users as a separate step.
    response = client.post(
        "/auth/otp/verify", json={"phone": "+905550000000", "code": "123456"}
    )
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "user_not_found"


def test_verify_rejects_a_wrong_code(client):
    response = client.post(
        "/auth/otp/verify", json={"phone": "05554443322", "code": "000000"}
    )
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "invalid_code"


def test_verify_normalises_the_phone_before_looking_it_up(client):
    # Every spelling of the shopkeeper's number must reach the same account, or one
    # person ends up with several ledgers.
    for spelling in ["05554443322", "0555 444 3322", "+905554443322", "905554443322"]:
        response = client.post(
            "/auth/otp/verify", json={"phone": spelling, "code": "123456"}
        )
        assert response.status_code == 200, spelling
        assert response.json()["user"]["user_id"] == "u_owner"


def test_verify_exposes_the_nested_seller_info(client):
    # Storage keeps shop_name/shop_phone flat; the wire shape is nested, because the
    # domain's type is what carries "is_seller <=> seller_info != null".
    response = client.post(
        "/auth/otp/verify", json={"phone": "+905554443322", "code": "123456"}
    )
    user = response.json()["user"]
    assert user["is_seller"] is True
    assert user["seller_info"] == {
        "shop_name": "Ahmet Bakkal",
        "shop_phone": "+902121112233",
    }
    # A person's name is not their shop's name.
    assert user["display_name"] == "Ahmet Demirtaş"


def test_a_plain_buyer_has_no_seller_info(client):
    response = client.post(
        "/auth/otp/verify", json={"phone": "+905551112233", "code": "123456"}
    )
    user = response.json()["user"]
    assert user["is_seller"] is False
    assert user["seller_info"] is None


# --- refresh ---


def test_refresh_returns_a_new_session(client):
    signed_in = client.post(
        "/auth/otp/verify", json={"phone": "+905554443322", "code": "123456"}
    ).json()

    response = client.post(
        "/auth/refresh", json={"refresh_token": signed_in["refresh_token"]}
    )
    assert response.status_code == 200
    assert response.json()["user"]["user_id"] == "u_owner"


def test_refresh_issues_a_token_that_differs_from_the_old_one(client):
    # TokenAuthenticator compares the failed request's bearer with the stored one to tell
    # whether another thread already refreshed. Two identical strings would send it down
    # the wrong branch, retrying with what it believes is a stale token.
    signed_in = client.post(
        "/auth/otp/verify", json={"phone": "+905554443322", "code": "123456"}
    ).json()
    renewed = client.post(
        "/auth/refresh", json={"refresh_token": signed_in["refresh_token"]}
    ).json()

    assert renewed["token"] != signed_in["token"]


def test_refresh_rejects_an_access_token(client):
    # The two have very different lifetimes; accepting one where the other belongs would
    # silently hand out a month-long bearer.
    signed_in = client.post(
        "/auth/otp/verify", json={"phone": "+905554443322", "code": "123456"}
    ).json()

    response = client.post("/auth/refresh", json={"refresh_token": signed_in["token"]})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "invalid_token"


def test_refresh_rejects_garbage(client):
    response = client.post("/auth/refresh", json={"refresh_token": "not-a-token"})
    assert response.status_code == 401


def test_refresh_rejects_an_expired_token(client):
    # Minted by hand: waiting out a real TTL would make the suite take hours.
    expired = jwt.encode(
        {"sub": "u_owner", "exp": int(time.time()) - 60, "typ": "refresh"},
        settings.jwt_secret,
        algorithm="HS256",
    )
    response = client.post("/auth/refresh", json={"refresh_token": expired})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "token_expired"


# --- logout ---


def test_logout_returns_204(client, owner_auth):
    response = client.post("/auth/logout", headers=owner_auth)
    assert response.status_code == 204


def test_logout_requires_a_token(client):
    # Otherwise anyone could end a session they do not hold.
    response = client.post("/auth/logout")
    assert response.status_code == 401
