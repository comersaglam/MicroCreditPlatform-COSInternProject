"""FastAPI application: wiring, the shared error envelope, and startup seeding."""

from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .config import settings
from .db import SessionLocal
from .routers import approvals, auth, buyer, customers, ledger, users
from .seed import is_empty, seed


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Seeds only an empty database. Alembic runs on every container start, so an
    # unconditional seed would double every balance on the second `compose up` -- and a
    # doubled balance is exactly the symptom that is supposed to mean "client and server
    # have drifted apart".
    if settings.seed_on_startup:
        with SessionLocal() as db:
            if is_empty(db):
                seed(db)
    yield


app = FastAPI(
    title="Veresiye Platform API",
    version="1.0.0",
    lifespan=lifespan,
)


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
    """
    Answer every error with the contract's `{"error": {"code", "message"}}` envelope.

    Routers raise HTTPException with that body already shaped (see security.api_error);
    anything raised elsewhere -- FastAPI's own 401/404/405 -- carries a plain string
    detail, which is wrapped here so no endpoint can leak a differently shaped error.
    """
    if isinstance(exc.detail, dict) and "error" in exc.detail:
        body = exc.detail
    else:
        body = {"error": {"code": "http_error", "message": str(exc.detail)}}
    return JSONResponse(status_code=exc.status_code, content=body, headers=exc.headers)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    """422s from Pydantic get the same envelope, for the same reason."""
    return JSONResponse(
        status_code=422,
        content={
            "error": {"code": "validation_error", "message": str(exc.errors())}
        },
    )


app.include_router(auth.router)
app.include_router(users.router)
app.include_router(customers.router)
app.include_router(ledger.router)
app.include_router(buyer.router)
app.include_router(approvals.router)


@app.get("/health", tags=["meta"])
def health() -> dict[str, str]:
    return {"status": "ok"}
