"""Environment-backed settings, read once at import."""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str = "postgresql+psycopg://veresiye:veresiye@localhost:5432/veresiye"
    jwt_secret: str = "dev-secret-not-for-production"

    # Deliberately short so the 401 -> refresh -> retry path is exercised in normal use
    # rather than only in a contrived test. Device testing drops this to 60 to watch
    # TokenAuthenticator renew a session without the shopkeeper seeing the login gate.
    token_ttl_seconds: int = 3600
    refresh_ttl_seconds: int = 60 * 60 * 24 * 30

    # The OTP is mocked server-side for now: any number receives a code, and this fixed
    # value verifies. What changed from the client mock is WHERE the code is judged --
    # a real SMS provider replaces only this constant's origin.
    mock_otp_code: str = "123456"

    seed_on_startup: bool = True

    # The admin panel's only credential. Weak by default for the same reason jwt_secret
    # is: a value that works out of the box on a laptop, and is obviously not one to ship.
    #
    # ⚠️ Understand what this single string opens. There is no admin user, no role column,
    # no rate limit and no lockout (deferred.md §L.6) -- and since Turn 48 it also opens
    # POST /admin/reset, which truncates every table. Anyone holding it holds the whole
    # database. Acceptable for a demo behind a laptop; acceptable for nothing else.
    admin_password: str = "admin"


settings = Settings()
