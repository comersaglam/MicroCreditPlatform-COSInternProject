"""
Wire shapes, one per schema in shared-contracts/openapi.yaml.

Field names are snake_case here because that IS the wire format -- the Kotlin DTOs carry
`@Json(name = "…")` annotations to reach the same strings from camelCase properties. Enum
values are plain `str` on purpose, matching the client's EnumMapping: a value neither side
recognises must be droppable, not a parse error that fails a whole response.
"""

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

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
    created_at: datetime


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
    expires_at: datetime
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
    created_at: datetime


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
    as_of: datetime


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
    requested_at: datetime


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
