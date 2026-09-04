"""
The admin panel's API: the gate first, then what it reports.

The gate matters more than it looks. This app now has TWO authentication systems -- phone
plus OTP for people, a shared password for the panel -- and the only thing keeping them
apart is the `typ` claim. test_a_user_access_token_is_not_an_admin_token below is the
whole proof that they are apart; without it, every signed-in shopkeeper would be holding a
key to the platform and nothing would say so.
"""

from datetime import timedelta

import pytest
from sqlalchemy import func, select

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


# --- lists ---


def test_the_seller_list_matches_the_seeded_shops(client, admin_auth):
    rows = client.get("/admin/sellers", headers=admin_auth).json()

    assert {row["user_id"] for row in rows} == {"u_owner", "u_market"}
    # Biggest book first.
    assert rows[0]["receivable_minor"] >= rows[-1]["receivable_minor"]


def test_a_shops_receivable_equals_its_ledger(client, admin_auth, db_session):
    """
    The endpoint's figure against ledger.py's own -- an equality, not a pinned constant.

    This is the test that catches a second definition of the balance. If someone writes a
    fresh SUM(CASE ...) inside the router and gets a sign or a type wrong, the two sides
    of this assert stop agreeing.
    """
    from app.ledger import balances_by_customer

    rows = client.get("/admin/sellers", headers=admin_auth).json()
    owner = next(row for row in rows if row["user_id"] == "u_owner")

    assert owner["receivable_minor"] == sum(
        balances_by_customer(db_session, "u_owner").values()
    )


def test_the_seller_list_reports_the_pinned_seed_balances(client, admin_auth):
    """
    890,50 TL -- 490,00 + 165,00 + 25,50 + 210,00, the figures test_seed_balances.py pins
    and the phone displays. The panel showing the same number as the till is the whole
    claim it exists to make.
    """
    rows = client.get("/admin/sellers", headers=admin_auth).json()
    owner = next(row for row in rows if row["user_id"] == "u_owner")

    assert owner["receivable_minor"] == 89050


def test_the_buyer_list_travels_customer_records(client, admin_auth, db_session):
    """
    u1 is c1 at one shop and m1 at another. A buyer's debt has to go through those rows --
    there is no user_id on a ledger line -- so this asserts the join, not just a total.
    """
    from app.ledger import debts_by_seller

    rows = client.get("/admin/buyers", headers=admin_auth).json()
    u1 = next(row for row in rows if row["user_id"] == "u1")

    assert u1["shop_count"] == 2
    assert u1["debt_minor"] == sum(debts_by_seller(db_session, ["c1", "m1"]).values())


def test_an_unclaimed_customer_belongs_to_no_buyer(client, admin_auth):
    """
    c4 and c5 are written down by the shopkeeper for people with no app. They owe money,
    it shows in their shop's receivable, and it must NOT be attributed to any user.
    """
    sellers = client.get("/admin/sellers", headers=admin_auth).json()
    buyers = client.get("/admin/buyers", headers=admin_auth).json()

    total_owed = sum(row["receivable_minor"] for row in sellers)
    attributed = sum(row["debt_minor"] for row in buyers)

    # The difference is exactly the three unclaimed customers in u_owner's book:
    # c2 165,00 + c4 25,50 + c5 210,00.
    assert total_owed - attributed == 16500 + 2550 + 21000


# --- details ---


def test_seller_detail_decomposes_the_same_number(client, admin_auth):
    """
    breakdown.py's invariant, checked through the panel: principal + indexation - paid
    equals outstanding, and outstanding equals the list's receivable.
    """
    body = client.get("/admin/sellers/u_owner", headers=admin_auth).json()
    parts = body["breakdown"]

    assert (
        parts["principal_minor"] + parts["indexation_minor"] - parts["total_paid_minor"]
        == parts["outstanding_minor"]
    )
    assert parts["outstanding_minor"] == body["seller"]["receivable_minor"]


def test_seller_detail_lists_the_book_with_balances(client, admin_auth):
    body = client.get("/admin/sellers/u_owner", headers=admin_auth).json()

    by_id = {row["customer_id"]: row for row in body["customers"]}
    assert by_id["c1"]["balance_minor"] == 49000
    assert by_id["c2"]["balance_minor"] == 16500
    assert by_id["c4"]["claim_status"] == "UNCLAIMED"


def test_seller_detail_entries_carry_the_customers_name(client, admin_auth):
    entries = client.get("/admin/sellers/u_owner", headers=admin_auth).json()[
        "recent_entries"
    ]

    assert entries
    assert all(entry["counterparty"] for entry in entries)
    # Newest first, so the table opens on what just happened.
    assert entries == sorted(entries, key=lambda e: e["created_at"], reverse=True)


def test_indexation_cannot_crowd_out_the_trading_history(
    client, admin_auth, db_session
):
    """
    The entry table must show what the shop actually did, not only what the index did.

    Found on screen: all twenty rows came back as identical "Eylül 2026 enflasyon farkı"
    lines, and the shop's year of trading was pushed off the bottom. Indexation is stamped
    on the first of each month and the warming pass wrote rows dated AFTER the last real
    purchase, so ordering by date alone buried every sale and payment. The query was
    correct and the table was useless -- the failure [[plausibility-check-the-output]]
    describes.

    ⚠️ The condition has to be BUILT here. A first version of this test called
    ensure_indexed_book and asserted on whatever came out; it stayed green with the defect
    put back, because the core seed has too few entries for indexation to bury them. It
    measured nothing. So this writes 25 indexation rows dated after the seed's newest
    entry -- the shape the live database actually had -- and with `ORDER BY created_at
    LIMIT 20` restored it fails, as it should.
    """
    from datetime import UTC, datetime

    from app import models

    newest = db_session.execute(
        select(func.max(models.Transaction.created_at))
    ).scalar_one()
    if newest.tzinfo is None:
        newest = newest.replace(tzinfo=UTC)

    for index in range(25):
        db_session.add(
            models.Transaction(
                transaction_id=f"idx_{index}",
                seller_id="u_owner",
                customer_id="c1",
                amount_minor=100 + index,
                type="INDEXATION",
                description=f"Endeks {index}",
                created_at=newest + timedelta(days=index + 1),
            )
        )
    db_session.commit()

    entries = client.get("/admin/sellers/u_owner", headers=admin_auth).json()[
        "recent_entries"
    ]
    types = {entry["type"] for entry in entries}

    assert "INDEXATION" in types, "indexation must stay visible -- it is the argument"
    assert types & {"DEBT", "PAYMENT"}, "and it must not be the only thing visible"
    # Still newest-first once the per-type windows are merged.
    assert entries == sorted(entries, key=lambda e: e["created_at"], reverse=True)


def test_buyer_detail_splits_the_debt_by_shop(client, admin_auth):
    body = client.get("/admin/buyers/u1", headers=admin_auth).json()

    by_shop = {row["seller_id"]: row for row in body["debts_by_shop"]}
    assert by_shop["u_owner"]["balance_minor"] == 49000
    assert by_shop["u_market"]["balance_minor"] == 10000
    # Buyer-facing screens name the SHOP, not the person behind it.
    assert by_shop["u_owner"]["shop_name"] == "Ahmet Bakkal"


def test_a_missing_seller_is_a_404(client, admin_auth):
    response = client.get("/admin/sellers/nobody", headers=admin_auth)

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "seller_not_found"


def test_a_buyer_is_not_reachable_through_the_seller_route(client, admin_auth):
    """u1 is a real user but not a shop; the seller route must not serve them."""
    assert client.get("/admin/sellers/u1", headers=admin_auth).status_code == 404


def test_the_lists_need_the_admin_token(client, owner_auth):
    for path in ("/admin/sellers", "/admin/buyers", "/admin/sellers/u_owner"):
        assert client.get(path).status_code == 401, path
        assert client.get(path, headers=owner_auth).status_code == 401, path


def test_reading_the_panel_writes_no_indexation(client, admin_auth, db_session, fx_series):
    """
    The panel is an observer.

    routers/customers.py writes this month's indexation when a shopkeeper reads their own
    book, and that pattern looks like something to copy here. It must not be: opening the
    panel would append rows to an append-only ledger for every customer on the platform
    (deferred.md §L.7). fx_series is requested so a rate table EXISTS -- without it the
    write could not happen anyway and this test would pass for the wrong reason.
    """
    from app import models
    from sqlalchemy import func, select

    def indexation_rows():
        return db_session.execute(
            select(func.count())
            .select_from(models.Transaction)
            .where(models.Transaction.type == "INDEXATION")
        ).scalar_one()

    before = indexation_rows()
    client.get("/admin/sellers", headers=admin_auth)
    client.get("/admin/sellers/u_owner", headers=admin_auth)
    client.get("/admin/buyers", headers=admin_auth)
    client.get("/admin/buyers/u1", headers=admin_auth)

    assert indexation_rows() == before


# --- stats ---


def test_both_stats_tabs_report_the_same_ledger(client, admin_auth):
    """
    The identity worth saying out loud in the demo, and the test that protects it.

    A seller's receivable and a buyer's debt are the same rows read from opposite ends.
    They differ by exactly what unclaimed customers owe -- people with no app, so no user
    to attribute the debt to. If a second definition of the balance is ever written, this
    is the assert that stops agreeing.
    """
    sellers = client.get("/admin/stats/sellers", headers=admin_auth).json()
    buyers = client.get("/admin/stats/buyers", headers=admin_auth).json()

    unclaimed_owed = 16500 + 2550 + 21000  # c2, c4, c5
    assert (
        sellers["total_receivable_minor"] - buyers["total_debt_minor"] == unclaimed_owed
    )
    # And both decompose from the same call, so their breakdowns are identical.
    assert sellers["breakdown"] == buyers["breakdown"]


def test_the_platform_breakdown_reconciles(client, admin_auth):
    parts = client.get("/admin/stats/sellers", headers=admin_auth).json()["breakdown"]

    assert (
        parts["principal_minor"] + parts["indexation_minor"] - parts["total_paid_minor"]
        == parts["outstanding_minor"]
    )


def test_the_monthly_series_groups_in_either_dialect(client, admin_auth):
    """
    Runs on SQLite here and Postgres in production, which is why the grouping uses
    func.extract -- date_trunc and strftime each exist in only one of them. A months
    label that came back malformed would show up as a broken axis, not an error.
    """
    months = client.get("/admin/stats/sellers", headers=admin_auth).json()["monthly"]

    assert months
    assert len(months) <= 12
    for point in months:
        year, month = point["month"].split("-")
        assert len(year) == 4 and 1 <= int(month) <= 12
    # Chronological, so a line chart can plot it without sorting.
    assert months == sorted(months, key=lambda point: point["month"])


def test_the_trailing_month_is_flagged_partial(client, admin_auth):
    """
    The last bucket holds however many days the data reached into that month, not thirty.

    Found on screen, not in a test: both lines fell vertically to zero at the right edge of
    every chart, which reads as collections collapsing. The real numbers were 660 entries
    in August and 106 in the September the data stops in. The flag lets the panel leave
    that month out and say so instead of drawing a cliff.
    """
    months = client.get("/admin/stats/sellers", headers=admin_auth).json()["monthly"]

    assert months[-1]["partial"] is True
    # Exactly one: only the month the data ends inside is incomplete.
    assert [point["partial"] for point in months].count(True) == 1


def test_the_series_sums_back_to_the_breakdown(client, admin_auth):
    """
    Per-month totals against the whole-ledger decomposition. Both come from _total_of, and
    the point of asserting it is that the monthly path adds a GROUP BY -- the place a
    filter could silently drop rows.
    """
    body = client.get("/admin/stats/sellers", headers=admin_auth).json()

    assert sum(point["debt_minor"] for point in body["monthly"]) == body["breakdown"][
        "principal_minor"
    ]
    assert sum(point["payment_minor"] for point in body["monthly"]) == body[
        "breakdown"
    ]["total_paid_minor"]


def test_seller_stats_rank_the_shops_and_the_risk(client, admin_auth):
    body = client.get("/admin/stats/sellers", headers=admin_auth).json()

    # Shops are named by their SHOP name, the way buyer-facing screens name them.
    assert body["top_sellers"][0]["label"] == "Ahmet Bakkal"
    assert body["riskiest_customers"][0]["amount_minor"] == 49000  # c1
    assert 0.0 <= body["collection_rate"] <= 1.0


def test_buyer_stats_count_claimed_and_unclaimed(client, admin_auth):
    body = client.get("/admin/stats/buyers", headers=admin_auth).json()

    assert body["claimed_customer_count"] == 4
    assert body["unclaimed_customer_count"] == 3


def test_buyer_stats_group_spending_by_category(client, admin_auth):
    """Descriptions are real ledger text, so this chart is a grouping and not a mock."""
    categories = client.get("/admin/stats/buyers", headers=admin_auth).json()[
        "by_category"
    ]

    assert categories
    assert all(item["amount_minor"] > 0 for item in categories)
    # Payments are not a category of goods.
    assert not any("ödeme" in item["label"].lower() for item in categories)


def test_the_debt_bands_count_people_not_money(client, admin_auth):
    body = client.get("/admin/stats/buyers", headers=admin_auth).json()

    bands = body["debt_bands"]
    assert [band["label"] for band in bands][0] == "0"
    # Short labels: these are axis ticks, and the long form overlapped its neighbour.
    assert all(len(band["label"]) <= 8 for band in bands)
    # Every buyer carrying a balance falls in exactly one band.
    assert sum(band["amount_minor"] for band in bands) == 3  # u1, u3, u_owner


def test_the_stats_need_the_admin_token(client, owner_auth):
    for path in ("/admin/stats/sellers", "/admin/stats/buyers"):
        assert client.get(path).status_code == 401, path
        assert client.get(path, headers=owner_auth).status_code == 401, path


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
    response = client.get("/health", headers={"Origin": "http://localhost:5174"})

    assert response.headers["access-control-allow-origin"] == "http://localhost:5174"


def test_reset_is_locked_even_though_it_exists(client, owner_auth):
    """
    §F.4 refused this endpoint outright; Turn 48 wrote it anyway, behind the admin key
    (deferred.md §L.19). The least this file can do is prove the key is required.

    ⚠️ The happy path is NOT tested here and cannot be: reset() issues TRUNCATE ... RESTART
    IDENTITY CASCADE, which SQLite does not have, and it cannot be softened to a DELETE
    because the append-only trigger on `transactions` refuses those. Verified by hand
    against real Postgres instead -- the same posture conftest.py already documents for the
    trigger itself.
    """
    assert client.post("/admin/reset").status_code == 401
    assert client.post("/admin/reset", headers=owner_auth).status_code == 401


def test_me_carries_the_mock_inventory(client, admin_auth):
    """
    The panel renders deferred.md §L as a table. If this list ever empties, the panel
    starts presenting mocks silently -- which is the one thing §L exists to prevent.
    """
    inventory = client.get("/admin/me", headers=admin_auth).json()["inventory"]

    assert {item["status"] for item in inventory} <= {"REAL", "MOCK", "MISSING"}
    assert any(item["status"] == "MOCK" for item in inventory)
    assert all(item["note"] for item in inventory)
