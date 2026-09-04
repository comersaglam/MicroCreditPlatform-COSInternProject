"""JWT minting and verification, plus the shared error envelope."""

import uuid
from datetime import UTC, datetime, timedelta

import jwt
from fastapi import HTTPException

from .config import settings

ALGORITHM = "HS256"


def api_error(status: int, code: str, message: str) -> HTTPException:
    """Raise-ready HTTPException carrying the contract's `{error:{code,message}}` body."""
    return HTTPException(
        status_code=status, detail={"error": {"code": code, "message": message}}
    )


def _encode(subject: str, ttl_seconds: int, token_type: str) -> tuple[str, datetime]:
    expires_at = datetime.now(UTC) + timedelta(seconds=ttl_seconds)
    payload = {
        "sub": subject,
        "exp": expires_at,
        "typ": token_type,
        # A unique id per token so two tokens minted in the same second for the same user
        # are still distinct strings -- TokenAuthenticator compares the old and new bearer
        # to decide whether another thread already refreshed, and identical strings would
        # make it retry with what it believes is a stale token.
        "jti": uuid.uuid4().hex,
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=ALGORITHM), expires_at


def create_access_token(user_id: str) -> tuple[str, datetime]:
    return _encode(user_id, settings.token_ttl_seconds, "access")


def create_refresh_token(user_id: str) -> str:
    # Always issued, even though the contract marks it nullable. TokenAuthenticator gives
    # up the moment it finds no refresh token, dropping the shopkeeper to the login gate
    # mid-sale -- so a deployment that omits it cannot exercise 401 -> refresh -> retry.
    token, _ = _encode(user_id, settings.refresh_ttl_seconds, "refresh")
    return token


# The `sub` of an admin token. A constant, not a user id, because there is no user row
# behind it -- the admin is a key, not an account (deferred.md §L.6). That is also why this
# takes no argument where the two above take a user_id.
ADMIN_SUBJECT = "admin"


def create_admin_token() -> tuple[str, datetime]:
    """
    Mint the panel's bearer. A THIRD token type, beside access and refresh.

    It lives here rather than in admin_auth.py so all three minting functions stay in one
    place and nothing has to reach across a module for `_encode`. What makes the type
    matter is decode_token below: an access token presented to an admin endpoint fails on
    `typ` exactly the way a refresh token already fails on an access-only one. Without a
    distinct type, every signed-in shopkeeper would hold an admin bearer.

    Same TTL as an access token. A panel session outliving a shop session would only widen
    the window on a credential that has no rotation of its own.
    """
    return _encode(ADMIN_SUBJECT, settings.token_ttl_seconds, "admin")


def decode_token(token: str, expected_type: str) -> str:
    """Return the subject (user_id), or raise 401."""
    try:
        payload = jwt.decode(token, settings.jwt_secret, algorithms=[ALGORITHM])
    except jwt.ExpiredSignatureError:
        raise api_error(401, "token_expired", "Token expired") from None
    except jwt.InvalidTokenError:
        raise api_error(401, "invalid_token", "Token invalid") from None

    # An access token must not be usable where a refresh token is expected, or vice
    # versa: the two have very different lifetimes, and swapping them would silently
    # grant a month-long bearer.
    if payload.get("typ") != expected_type:
        raise api_error(401, "invalid_token", "Wrong token type")

    subject = payload.get("sub")
    if not subject:
        raise api_error(401, "invalid_token", "Token has no subject")
    return subject
