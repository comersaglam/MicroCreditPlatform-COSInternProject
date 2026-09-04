"""
The admin panel's API: the gate first, then what it reports.

The gate matters more than it looks. This app now has TWO authentication systems -- phone
plus OTP for people, a shared password for the panel -- and the only thing keeping them
apart is the `typ` claim. test_a_user_access_token_is_not_an_admin_token below is the
whole proof that they are apart; without it, every signed-in shopkeeper would be holding a
key to the platform and nothing would say so.
"""

import pytest

from app.config import settings


@pytest.fixture
def admin_auth(client):
    """An admin bearer, taken through the real login endpoint like every other fixture."""
    response = client.post("/admin/login", json={"password": settings.admin_password})
    assert response.status_code == 200
    return {"Authorization": f"Bearer {response.json()['token']}"}


# --- the gate ---


def test_the_right_password_mints_a_token(client):
    response = client.post("/admin/login", json={"password": settings.admin_password})

    assert response.status_code == 200
    body = response.json()
    assert body["token"]
    assert body["expires_at"].endswith("Z")


def test_the_wrong_password_is_refused(client):
    response = client.post("/admin/login", json={"password": "not-the-password"})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "invalid_credentials"


def test_admin_endpoints_reject_a_missing_token(client):
    assert client.get("/admin/me").status_code == 401


def test_a_user_access_token_is_not_an_admin_token(client, owner_auth):
    """
    The one test that proves the two auth systems are actually separate.

    u_owner is a perfectly valid signed-in shopkeeper holding a correctly signed token.
    The ONLY thing that stops it opening the panel is decode_token's `typ` check. Verified
    to fail first: with require_admin asking for "access" instead of "admin", this returned
    200 -- a seller reading the whole platform, exactly the hole it is here to close.
    """
    response = client.get("/admin/me", headers=owner_auth)

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "invalid_token"


def test_an_admin_token_is_not_a_user_token(client, admin_auth):
    """And the same door in the other direction: the admin key names no user row."""
    assert client.get("/users/me", headers=admin_auth).status_code == 401


# --- /admin/me ---


def test_me_reports_the_seeded_state(client, admin_auth):
    body = client.get("/admin/me", headers=admin_auth).json()

    assert body["subject"] == "admin"
    assert body["core_seeded"] is True
    # The demo population is a separate, opt-in seed; the test database only has the core.
    assert body["demo_seeded"] is False


def test_me_counts_every_table_reset_knows_about(client, admin_auth):
    """
    The counts borrow reset._TABLES, so this asserts the borrowing rather than a list of
    its own -- a table added to reset shows up here without this test being edited.
    """
    from app.reset import _TABLES

    counts = client.get("/admin/me", headers=admin_auth).json()["counts"]

    assert set(counts) == set(_TABLES)
    assert counts["users"] == 4  # the core seed's two shopkeepers and two buyers
    assert counts["transactions"] > 0


def test_me_survives_a_database_alembic_never_touched(client, admin_auth):
    """
    SQLite here has no alembic_version table: conftest builds the schema with create_all.
    None is the honest answer, and the endpoint must not raise trying to find it.
    """
    assert client.get("/admin/me", headers=admin_auth).json()["migration_head"] is None


def test_the_panels_origin_is_allowed(client):
    """
    CORS, asserted rather than assumed.

    In development the Vite proxy means the browser never issues a cross-origin request,
    so a broken CORS config would stay invisible until someone opened the panel against
    :4010 directly -- on stage, as a blank screen with an error in a console nobody has
    open. Cheaper to check here.
    """
    response = client.get("/health", headers={"Origin": "http://localhost:5173"})

    assert response.headers["access-control-allow-origin"] == "http://localhost:5173"


def test_me_carries_the_mock_inventory(client, admin_auth):
    """
    The panel renders deferred.md §L as a table. If this list ever empties, the panel
    starts presenting mocks silently -- which is the one thing §L exists to prevent.
    """
    inventory = client.get("/admin/me", headers=admin_auth).json()["inventory"]

    assert {item["status"] for item in inventory} <= {"REAL", "MOCK", "MISSING"}
    assert any(item["status"] == "MOCK" for item in inventory)
    assert all(item["note"] for item in inventory)
