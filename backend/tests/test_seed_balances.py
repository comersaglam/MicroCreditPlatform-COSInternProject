"""
The seed's balances are a free client/server parity check.

Both apps ship the same demo book, so if the number the server derives ever stops
matching the number the device shows, one of the two ledger implementations has drifted.
Pinning the expected values here means that drift fails a test instead of being noticed
on a shop floor.

Runs against SQLite in-memory rather than Postgres: the rule under test is the SUM, and
binding the check to a running container would make it something people skip.
"""

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.db import Base
from app.ledger import balance_of, balances_by_customer, debts_by_seller
from app.seed import is_empty, seed


@pytest.fixture
def db():
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    seed(session)
    yield session
    session.close()


# The five books from app-pos's dashboard plus the two cross-shop records only
# app-mobile's seed had. Values in kuruş.
@pytest.mark.parametrize(
    "seller_id,customer_id,expected",
    [
        ("u_owner", "c1", 49000),   # Ahmet: 500 + 30 - 40 = 490,00
        ("u_owner", "c2", 16500),   # Ayşe:   120 + 45     = 165,00
        ("u_owner", "c3", 0),       # Mehmet: 80 - 80      = 0
        ("u_owner", "c4", 2550),    # Fatma:                 25,50
        ("u_owner", "c5", 21000),   # Hasan:  310 - 100    = 210,00
        ("u_market", "m1", 10000),  # u1 @ Ayşe Market:      100,00
        ("u_market", "o1", 6000),   # u_owner @ Ayşe Market:  60,00
    ],
)
def test_seed_balances(db, seller_id: str, customer_id: str, expected: int) -> None:
    assert balance_of(db, seller_id, customer_id) == expected


def test_balance_of_unknown_pair_is_zero(db) -> None:
    # COALESCE, not NULL: a customer with no entries yet owes nothing.
    assert balance_of(db, "u_owner", "does-not-exist") == 0


def test_balances_by_customer_matches_individual_sums(db) -> None:
    # The batched query is what the customer list uses; it must not disagree with the
    # single-pair query that the balance endpoint uses.
    batched = balances_by_customer(db, "u_owner")
    for customer_id, balance in batched.items():
        assert balance == balance_of(db, "u_owner", customer_id)


def test_debts_by_seller_groups_across_shops(db) -> None:
    # u1 is c1 at Ahmet Bakkal and m1 at Ayşe Market -- one person, two books. This is
    # the query behind /me/debts, and it is the reason the seed needed a second shop.
    debts = debts_by_seller(db, ["c1", "m1"])
    assert debts == {"u_owner": 49000, "u_market": 10000}


def test_seed_is_not_run_twice(db) -> None:
    # Alembic runs on every container start; an unconditional seed would double every
    # balance on the second `compose up`.
    assert is_empty(db) is False
