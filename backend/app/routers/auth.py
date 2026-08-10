"""
Sign-in and token lifecycle.

The OTP itself is still a mock, but it is now the SERVER's mock: the code is judged here
rather than by OtpService on the device. Swapping in a real SMS provider replaces where
the code comes from and touches nothing else -- which is the whole point of moving it.
"""

from fastapi import APIRouter, Response, status
from sqlalchemy import select

from .. import models, schemas
from ..config import settings
from ..deps import CurrentUser, DbSession
from ..phone import to_stored
from ..security import api_error, create_access_token, create_refresh_token, decode_token
from ..serializers import user_out

router = APIRouter(tags=["auth"])


def _session_for(user: models.User) -> schemas.Session:
    token, expires_at = create_access_token(user.user_id)
    return schemas.Session(
        token=token,
        # Always issued, though the contract marks it nullable: TokenAuthenticator gives
        # up the moment it finds none, dropping the shopkeeper to the login gate in the
        # middle of a sale. See plan §0.8.
        refresh_token=create_refresh_token(user.user_id),
        expires_at=expires_at,
        user=user_out(user),
    )


def _require_phone(raw: str) -> str:
    """Normalise or reject -- never fall back to raw digits (plan §0.4)."""
    phone = to_stored(raw)
    if phone is None:
        raise api_error(400, "invalid_phone", "Phone must be a valid Turkish mobile number")
    return phone


@router.post("/auth/otp/request", status_code=status.HTTP_202_ACCEPTED)
def request_otp(body: schemas.OtpRequest, db: DbSession) -> schemas.OtpRequestResult:
    """
    Dispatch a code. Answers the same way whether or not the number has an account.

    That symmetry is deliberate: a different answer for a known number would turn this
    endpoint into a way to test which phone numbers are registered. Whether the account
    exists is revealed at verify time, to someone who proved they hold the phone.
    """
    phone = _require_phone(body.phone)

    # The channel still reflects reality -- a user with the app gets a push, everyone
    # else an SMS -- because the client branches on it to pick the wording it shows.
    exists = db.execute(
        select(models.User.user_id).where(models.User.phone == phone)
    ).first()
    return schemas.OtpRequestResult(sent=True, channel="APP_PUSH" if exists else "SMS_OTP")


@router.post("/auth/otp/verify")
def verify_otp(body: schemas.OtpVerify, db: DbSession) -> schemas.Session:
    """
    Verify a code and return a session.

    Does NOT auto-register (firm decision, api-endpoints.md A.1). An unknown phone gets
    404 user_not_found and the client registers through POST /users as a separate step;
    keeping verify pure is what lets the sign-up flow change without touching sign-in.
    """
    phone = _require_phone(body.phone)

    if body.code != settings.mock_otp_code:
        raise api_error(401, "invalid_code", "Verification code is incorrect")

    user = db.execute(
        select(models.User).where(models.User.phone == phone)
    ).scalar_one_or_none()
    if user is None:
        raise api_error(404, "user_not_found", "No account for this phone")

    return _session_for(user)


@router.post("/auth/refresh")
def refresh(body: schemas.Refresh, db: DbSession) -> schemas.Session:
    """
    Trade a refresh token for a fresh session.

    Requires no bearer -- the refresh token IS the credential. The client calls this only
    after a 401, retries once, and falls back to the sign-in gate if this fails too.
    """
    user_id = decode_token(body.refresh_token, "refresh")

    user = db.get(models.User, user_id)
    if user is None:
        # 401 rather than 404: to the caller this is simply a credential that no longer
        # works, and the client's recovery (drop to the login gate) is the same.
        raise api_error(401, "invalid_token", "Unknown user")

    return _session_for(user)


@router.post("/auth/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(current_user: CurrentUser) -> Response:
    """
    End the session.

    Server-side this is currently a no-op: tokens are stateless JWTs, so there is nothing
    to invalidate short of a revocation list, and a short access TTL plus the client
    clearing its TokenStore covers the actual risk on a shop-floor terminal. The endpoint
    exists so the client has one call to make, and so a revocation list can land here
    later without the client changing.

    Still authenticated, so a caller cannot log out a session they do not hold.
    """
    return Response(status_code=status.HTTP_204_NO_CONTENT)
