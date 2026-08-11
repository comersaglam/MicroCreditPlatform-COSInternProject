"""
Wire shapes, one per schema in shared-contracts/openapi.yaml.

Field names are snake_case here because that IS the wire format -- the Kotlin DTOs carry
`@Json(name = "…")` annotations to reach the same strings from camelCase properties. Enum
values are plain `str` on purpose, matching the client's EnumMapping: a value neither side
recognises must be droppable, not a parse error that fails a whole response.
"""

from datetime import UTC, datetime
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, PlainSerializer


def _iso_utc(value: datetime) -> str:
    """
    Render exactly "2026-07-20T06:15:00Z" -- second precision, literal Z, always UTC.

    The client parses with SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"), which matches
    that pattern LITERALLY: microseconds or a "+00:00" offset both fail to parse. On the
    display path a failure is silent (TimeFormat falls back to the raw string, so the user
    sees "2026-07-20T06:15:00.123456Z" where a date belongs), which is exactly the kind of
    bug that survives to production -- so the shape is pinned here, once, for every
    timestamp the API emits.

    A naive value is treated as ALREADY UTC rather than converted. Everything written here
    is UTC by construction (`datetime.now(UTC)` and the seed's literals), but a driver may
    hand the value back without its tzinfo -- SQLite does exactly that. Calling
    astimezone() on such a value would assume the SERVER's local zone and shift the
    timestamp: on a UTC+3 machine a replayed entry came back three hours later than the
    original, which is the same class of error the seed literals once had.
    """
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


# Every datetime on the wire goes through the serializer above.
IsoUtc = Annotated[datetime, PlainSerializer(_iso_utc, return_type=str)]

# --- auth ---


class OtpRequest(BaseModel):
    phone: str


class OtpRequestResult(BaseModel):
    sent: bool
    channel: str | None = None


class OtpVerify(BaseModel):
    phone: str
    code: str


class Refresh(BaseModel):
    refresh_token: str


# --- user ---


class SellerInfo(BaseModel):
    shop_name: str
    shop_phone: str | None = None


class User(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    user_id: str
    phone: str
    display_name: str
    is_buyer: bool
    is_seller: bool
    email: str | None = None
    seller_info: SellerInfo | None = None
    created_at: IsoUtc


class UserCreate(BaseModel):
    phone: str
    display_name: str = ""
    is_seller: bool


class UserPatch(BaseModel):
    display_name: str | None = None
    email: str | None = None


class BecomeSeller(BaseModel):
    shop_name: str
    shop_phone: str | None = None


class Session(BaseModel):
    token: str
    refresh_token: str | None = None
    expires_at: IsoUtc
    user: User


# --- customer ---


class Customer(BaseModel):
    customer_id: str
    display_name: str
    phone: str
    claim_status: str
    claimed_by_user_id: str | None = None

    # Derived by the server from the ledger; never stored.
    balance_minor: int


class CustomerCreate(BaseModel):
    display_name: str
    phone: str


# --- basket / orderBody ---


class OrderItem(BaseModel):
    name: str
    price: int
    quantity: int
    tax_percent: int
    section_no: int = 1
    status: int = 1
    type: int = 0
    item_limit: int = 0


class OrderBody(BaseModel):
    basket_id: str
    create_invoice: bool = False
    document_type: int = 0
    is_void: bool = False
    items: list[OrderItem] = Field(default_factory=list)


# --- transaction ---


class Transaction(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    transaction_id: str
    seller_id: str
    customer_id: str
    amount_minor: int
    type: str
    description: str
    basket_id: str | None = None
    settled_via_pgw: bool = False
    receipt_no: str | None = None
    created_at: IsoUtc


class TransactionCreate(BaseModel):
    transaction_id: str
    customer_id: str
    amount_minor: int = Field(ge=0)
    type: str
    description: str

    # There is deliberately NO seller_id: on seller-scoped endpoints it comes from the
    # bearer token and a body field is never trusted for it.
    basket: OrderBody | None = None


class Balance(BaseModel):
    seller_id: str
    customer_id: str
    balance_minor: int
    as_of: IsoUtc


class SellerDebt(BaseModel):
    seller_id: str
    shop_name: str
    balance_minor: int


# --- approval ---


class Approval(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    approval_id: str
    initiator_user_id: str
    initiator_role: str
    target_user_id: str
    seller_id: str
    shop_name: str
    customer_id: str
    amount_minor: int
    type: str
    description: str | None = None
    channel: str
    status: str
    requested_at: IsoUtc


class ApprovalCreate(BaseModel):
    seller_id: str
    customer_id: str
    amount_minor: int = Field(ge=0)
    type: str
    description: str | None = None

    # Present because a buyer can start an approval too, in which case seller_id cannot
    # come from the token. The router still verifies it -- see routers/approvals.py.
    initiator_role: str
    target_user_id: str


# --- error ---


class ErrorBody(BaseModel):
    code: str
    message: str


class Error(BaseModel):
    error: ErrorBody
