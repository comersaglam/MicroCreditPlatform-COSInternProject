"""
Test harness: a seeded SQLite database behind the real FastAPI app.

SQLite rather than Postgres so the suite runs without a container. The rules under test
here -- idempotency, ownership, the balance sum, approval direction -- are application
logic, not dialect behaviour. The one thing SQLite cannot check is the append-only
trigger, which lives in the migration and is verified against the real database instead.
"""

from datetime import date

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool
from sqlalchemy.orm import sessionmaker

from app import models
from app.config import settings
from app.db import Base, get_db
from app.main import app
from app.seed import seed


@pytest.fixture
def db_session():
    # StaticPool keeps every connection pointed at the SAME in-memory database; the
    # default pool would hand the app a fresh, empty one per connection.
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)()
    seed(session)
    yield session
    session.close()


@pytest.fixture
def client(db_session, monkeypatch):
    # Startup seeding is switched OFF for tests. dependency_overrides only redirects
    # request-time sessions; lifespan builds its own from SessionLocal and would try to
    # reach the real Postgres URL, failing the whole suite on a machine with no container.
    monkeypatch.setattr(settings, "seed_on_startup", False)

    app.dependency_overrides[get_db] = lambda: db_session
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()


@pytest.fixture
def owner_token(client) -> str:
    """A signed-in u_owner -- the shopkeeper most seller-scoped tests act as."""
    response = client.post(
        "/auth/otp/verify", json={"phone": "+905554443322", "code": "123456"}
    )
    assert response.status_code == 200
    return response.json()["token"]


@pytest.fixture
def owner_auth(owner_token) -> dict[str, str]:
    return {"Authorization": f"Bearer {owner_token}"}


@pytest.fixture
def market_auth(client) -> dict[str, str]:
    """u_market -- the SECOND shop, for anything that must not cross book boundaries."""
    response = client.post(
        "/auth/otp/verify", json={"phone": "+905553334455", "code": "123456"}
    )
    assert response.status_code == 200
    return {"Authorization": f"Bearer {response.json()['token']}"}


@pytest.fixture
def buyer_auth(client) -> dict[str, str]:
    """u1 -- a plain buyer, with records in both shops' books (c1 and m1)."""
    response = client.post(
        "/auth/otp/verify", json={"phone": "+905551112233", "code": "123456"}
    )
    assert response.status_code == 200
    return {"Authorization": f"Bearer {response.json()['token']}"}


@pytest.fixture
def fx_series(db_session):
    """
    Twelve months of index at a clean 3% per month, opt-in.

    Deliberately NOT part of the base seed. Indexation is written whenever a balance is
    read, so seeding rates globally would silently move the balances every other test
    asserts on -- and those assertions are the thing that proves the ledger sums the way
    both clients do.

    Requesting this fixture is how a test says "now let inflation run". The rate is a round
    3% so an expected figure can be worked out by hand in the test that reads it.
    """
    cpi = 100_000
    for month in range(1, 13):
        db_session.add(
            models.FxRate(
                as_of=date(2026, month, 1),
                usd_minor=3_200 + month * 50,
                eur_minor=3_500 + month * 50,
                gold_minor=400_000 + month * 5_000,
                cpi_index=cpi,
            )
        )
        cpi = round(cpi * 1.03)
    db_session.commit()
