"""Shared FastAPI dependencies: the database session and the signed-in user."""

from typing import Annotated

from fastapi import Depends, Header
from sqlalchemy.orm import Session

from . import models
from .db import get_db
from .security import api_error, decode_token

DbSession = Annotated[Session, Depends(get_db)]


def get_current_user(
    db: DbSession,
    authorization: Annotated[str | None, Header()] = None,
) -> models.User:
    """
    Resolve the bearer token to a user row.

    Every seller-scoped endpoint takes its seller_id from THIS, never from a request
    body -- trusting a body field for ownership is exactly the hole the contract closes.
    """
    if not authorization or not authorization.startswith("Bearer "):
        raise api_error(401, "unauthorized", "Missing bearer token")

    user_id = decode_token(authorization.removeprefix("Bearer "), "access")

    user = db.get(models.User, user_id)
    if user is None:
        # A validly signed token for a user that no longer exists: treat as unauthorised
        # rather than 404, so a deleted account cannot be probed through this endpoint.
        raise api_error(401, "unauthorized", "Unknown user")
    return user


CurrentUser = Annotated[models.User, Depends(get_current_user)]
