"""
Canonical phone formatting, kept byte-for-byte in step with the client's
app-pos/app/src/main/java/com/example/app_pos/util/PhoneFormat.kt.

Both sides must agree, because the phone number IS the identity: if the server accepted
a shape the client rejects (or normalised it differently), one person would end up with
two accounts and a silently split ledger.

Note the deliberate absence of a "just keep the raw digits" fallback. The client returns
null for anything that is not one of the two recognised shapes, and a fallback here would
accept numbers the client cannot even produce.
"""


def to_stored(raw: str | None) -> str | None:
    """Return the canonical '+90…' form, or None when the input is not a valid number."""
    if not raw:
        return None

    digits = "".join(ch for ch in raw if ch.isdigit())

    # Idempotent: an already-stored number ('+905554443322' -> '905554443322', 12 digits
    # starting with 90) comes back unchanged, so normalising twice is safe.
    if len(digits) == 12 and digits.startswith("90"):
        return "+" + digits

    # A raw local number: 11 digits starting with 0, e.g. '0555 444 3322'.
    if len(digits) != 11 or not digits.startswith("0"):
        return None

    # Drop the leading 0, prepend the country code.
    return "+90" + digits[1:]
