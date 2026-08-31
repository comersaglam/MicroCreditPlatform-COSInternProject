"""
The breakdown endpoints: what a balance is made of, from both sides of the counter.

The rule worth pinning above all others is that the parts reconcile. If principal +
indexation - paid ever stops equalling the balance shown next to it, the screen is telling
the reader that the ledger is approximate -- which is exactly the belief this whole design
exists to prevent.

The rest is about not lying when the data is thin: an unknown rate must read as unknown,
never as zero, because a client that got zeros back would divide by them.
"""

from datetime import UTC, datetime

from sqlalchemy import select

from app import models


def _entry(db, tx_id, seller, customer, amount, tx_type, when):
    db.add(
        models.Transaction(
            transaction_id=tx_id,
            seller_id=seller,
            customer_id=customer,
            amount_minor=amount,
            type=tx_type,
            description="test",
            basket_id=None,
            settled_via_pgw=False,
            receipt_no=None,
            created_at=when,
        )
    )
    db.commit()


# --- the invariant ----------------------------------------------------------------

def test_the_parts_add_up_to_the_balance(client, owner_auth, fx_series) -> None:
    body = client.get("/customers/breakdown", headers=owner_auth).json()

    assert (
        body["principal_minor"] + body["indexation_minor"] - body["total_paid_minor"]
        == body["outstanding_minor"]
    )


def test_the_breakdown_matches_the_balance_endpoint(client, owner_auth, fx_series) -> None:
    # Two endpoints, one truth. These are the numbers a shopkeeper sees on two screens of
    # the same app, and they must not need explaining to each other.
    breakdown = client.get(
        "/customers/breakdown?customer_id=c1", headers=owner_auth
    ).json()
    balance = client.get("/balances?customer_id=c1", headers=owner_auth).json()

    assert breakdown["outstanding_minor"] == balance["balance_minor"]


def test_buyer_and_seller_see_the_same_account(client, owner_auth, buyer_auth, fx_series) -> None:
    # u1 is c1 in u_owner's book. The same rows, decomposed from either direction, have to
    # produce the same three components -- one is the other's mirror, not a second opinion.
    seller_side = client.get(
        "/customers/breakdown?customer_id=c1", headers=owner_auth
    ).json()
    buyer_side = client.get(
        "/me/debts/breakdown?seller_id=u_owner", headers=buyer_auth
    ).json()

    assert seller_side["principal_minor"] == buyer_side["principal_minor"]
    assert seller_side["indexation_minor"] == buyer_side["indexation_minor"]
    assert seller_side["outstanding_minor"] == buyer_side["outstanding_minor"]


# --- what the components actually contain -----------------------------------------

def test_principal_is_debt_only(client, owner_auth, db_session, fx_series) -> None:
    # c1's seeded entries: 50 + 30 debt, 40 payment.
    body = client.get("/customers/breakdown?customer_id=c1", headers=owner_auth).json()

    assert body["principal_minor"] == 8_000
    assert body["total_paid_minor"] == 4_000


def test_indexation_is_reported_separately(client, owner_auth, fx_series) -> None:
    # The point of the endpoint: the inflation component is nameable, not buried in the
    # principal. Without it a customer cannot be shown why they owe more than they bought.
    body = client.get("/customers/breakdown?customer_id=c1", headers=owner_auth).json()

    assert body["indexation_minor"] > 0
    assert body["principal_minor"] == 8_000  # unchanged by indexing


def test_no_indexation_without_rates(client, owner_auth) -> None:
    # No fx_series fixture here. The component is zero rather than absent: nothing accrued,
    # which is a fact, unlike the fx fields below which are genuinely unknown.
    body = client.get("/customers/breakdown?customer_id=c1", headers=owner_auth).json()

    assert body["indexation_minor"] == 0
    assert body["outstanding_minor"] == 4_000


# --- scoping ----------------------------------------------------------------------

def test_the_whole_book_sums_its_customers(client, owner_auth, db_session, fx_series) -> None:
    whole = client.get("/customers/breakdown", headers=owner_auth).json()

    customers = client.get("/customers", headers=owner_auth).json()
    per_customer = sum(
        client.get(
            f"/customers/breakdown?customer_id={c['customer_id']}", headers=owner_auth
        ).json()["outstanding_minor"]
        for c in customers
    )

    assert whole["outstanding_minor"] == per_customer


def test_a_buyer_sees_every_shop_without_a_filter(client, buyer_auth, fx_series) -> None:
    # u1 owes both shops. The unfiltered answer covers both; each filtered answer is
    # smaller, and together they account for the whole.
    everywhere = client.get("/me/debts/breakdown", headers=buyer_auth).json()
    at_owner = client.get(
        "/me/debts/breakdown?seller_id=u_owner", headers=buyer_auth
    ).json()
    at_market = client.get(
        "/me/debts/breakdown?seller_id=u_market", headers=buyer_auth
    ).json()

    assert (
        everywhere["outstanding_minor"]
        == at_owner["outstanding_minor"] + at_market["outstanding_minor"]
    )
    assert at_owner["outstanding_minor"] < everywhere["outstanding_minor"]


def test_a_seller_cannot_read_another_book(client, owner_auth, market_auth, fx_series) -> None:
    # The scope comes from the token, so asking about a customer id from someone else's
    # book returns that book's rows scoped to YOUR seller id -- which is nothing.
    body = client.get("/customers/breakdown?customer_id=m1", headers=owner_auth).json()

    assert body["outstanding_minor"] == 0
    assert body["principal_minor"] == 0


def test_a_buyer_with_no_records_owes_nothing(client, fx_series) -> None:
    # u3 has a claimed record; a freshly registered user has none at all. The empty
    # selection is an answer, not a failure.
    registered = client.post(
        "/users",
        json={"phone": "+905559998877", "display_name": "Yeni", "is_seller": False},
    )
    assert registered.status_code == 201

    token = client.post(
        "/auth/otp/verify", json={"phone": "+905559998877", "code": "123456"}
    ).json()["token"]

    body = client.get(
        "/me/debts/breakdown", headers={"Authorization": f"Bearer {token}"}
    ).json()

    assert body == {
        "principal_minor": 0,
        "indexation_minor": 0,
        "total_paid_minor": 0,
        "outstanding_minor": 0,
        "fx_at_open": None,
        "fx_today": None,
        "projected_3m_minor": None,
    }


def test_breakdown_requires_a_seller_account(client, buyer_auth) -> None:
    assert client.get("/customers/breakdown", headers=buyer_auth).status_code == 403


def test_breakdown_is_not_read_as_a_customer_id(client, owner_auth) -> None:
    # The literal path has to win against /customers/{customer_id}. If it did not, this
    # would 404 with "no such customer" -- the same trap `lookup` was written to avoid.
    response = client.get("/customers/breakdown", headers=owner_auth)

    assert response.status_code == 200
    assert "outstanding_minor" in response.json()


# --- the value-over-time half -----------------------------------------------------

def test_fx_snapshots_bracket_the_account(client, owner_auth, fx_series) -> None:
    body = client.get("/customers/breakdown?customer_id=c1", headers=owner_auth).json()

    assert body["fx_at_open"]["as_of"] <= body["fx_today"]["as_of"]
    assert body["fx_at_open"]["usd_minor"] > 0


def test_fx_is_null_rather_than_zero_when_unknown(client, owner_auth) -> None:
    # No fx_series. A client handed zeros here would divide by them; null forces it to
    # render "unknown" instead of a wrong number.
    body = client.get("/customers/breakdown?customer_id=c1", headers=owner_auth).json()

    assert body["fx_at_open"] is None
    assert body["fx_today"] is None
    assert body["projected_3m_minor"] is None


def test_a_settled_account_is_not_projected(client, owner_auth, fx_series) -> None:
    # c3 is square: 80 in, 80 out. "In three months this will be 0,00" is noise.
    body = client.get("/customers/breakdown?customer_id=c3", headers=owner_auth).json()

    assert body["outstanding_minor"] == 0
    assert body["projected_3m_minor"] is None


def test_a_projection_exceeds_what_is_owed_today(client, owner_auth, fx_series) -> None:
    body = client.get("/customers/breakdown?customer_id=c5", headers=owner_auth).json()

    assert body["projected_3m_minor"] > body["outstanding_minor"]


# --- the fx-rates endpoint --------------------------------------------------------

def test_fx_rates_reports_the_row_it_used(client, owner_auth, fx_series) -> None:
    # Asked for a day with no row of its own; answers with the reading that WAS in force,
    # and says which day that was rather than echoing the question back.
    body = client.get("/fx-rates?as_of=2026-03-20", headers=owner_auth).json()

    assert body["as_of"] == "2026-03-01"
    assert body["usd_minor"] > 0


def test_fx_rates_404s_outside_the_series(client, owner_auth, fx_series) -> None:
    response = client.get("/fx-rates?as_of=2020-01-01", headers=owner_auth)

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "rate_not_found"


def test_fx_rates_needs_a_token(client, fx_series) -> None:
    assert client.get("/fx-rates").status_code == 401
