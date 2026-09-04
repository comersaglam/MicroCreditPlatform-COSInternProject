"""
The admin gate -- the platform's SECOND authentication system.

Kept in its own module rather than added to deps.py, which says of itself that it holds
"the database session and the signed-in user". An admin is neither. There is no row to
look up, no phone, no OTP: a password from the environment is exchanged for a token, and
the token names no one (see security.ADMIN_SUBJECT). deferred.md §L.6 words the tradeoff
as "an admin is a key, not a user", and this file is that key's whole implementation --
which also makes it the file to delete on the day `users.is_admin` lands.

⚠️ The consequence of having no user behind the token: "who did this" has no answer. Two
people sharing the password are indistinguishable in anything we record.
"""

import secrets
from typing import Annotated

from fastapi import Depends, Header

from .config import settings
from .security import api_error, decode_token


def verify_password(candidate: str) -> bool:
    """
    Whether this is the admin password.

    compare_digest rather than `==` so the comparison does not return early on the first
    wrong character. The timing signal is small and this is a demo, but the correct
    comparison costs one import.
    """
    return secrets.compare_digest(candidate, settings.admin_password)


def require_admin(
    authorization: Annotated[str | None, Header()] = None,
) -> str:
    """
    Resolve an admin bearer, or raise 401.

    Deliberately shaped like deps.get_current_user, minus the database: same header
    handling, same error wording. The one line that matters is decode_token(token,
    "admin") -- the SAME type check that already keeps access and refresh tokens out of
    each other's endpoints (security.py). Reusing it is what makes an ordinary user's
    access token useless here; a second, hand-rolled verifier would be a second chance to
    get that wrong.

    Returns the subject, which is always ADMIN_SUBJECT. Endpoints do not need it -- it is
    returned so the dependency has something to hand back and so /admin/me can echo it.
    """
    if not authorization or not authorization.startswith("Bearer "):
        raise api_error(401, "unauthorized", "Missing bearer token")

    return decode_token(authorization.removeprefix("Bearer "), "admin")


AdminAuth = Annotated[str, Depends(require_admin)]
