"""
The server's phone normalisation must agree with PhoneFormat.toStored on the device.

The number IS the identity, so a shape one side accepts and the other rejects (or that
the two canonicalise differently) splits one person into two accounts with two ledgers.
"""

import pytest

from app.phone import to_stored


@pytest.mark.parametrize(
    "raw,expected",
    [
        # The ordinary case: 11 digits starting with 0, however the user spaced it.
        ("05554443322", "+905554443322"),
        ("0555 444 3322", "+905554443322"),
        ("0555-444-33-22", "+905554443322"),
        # Idempotent: an already-stored number survives a second pass unchanged. The
        # client had a real bug here once -- a double conversion returned null and left
        # the session unset while the UI still reported success.
        ("+905554443322", "+905554443322"),
        ("905554443322", "+905554443322"),
    ],
)
def test_accepts_and_canonicalises(raw: str, expected: str) -> None:
    assert to_stored(raw) == expected


@pytest.mark.parametrize(
    "raw",
    [
        "",
        None,
        "12345",             # too short
        "5554443322",        # 10 digits, no leading 0
        "005554443322",      # 12 digits but not the 90 prefix
        "+15554443322",      # not a Turkish number
        "abcdefghijk",       # no digits at all
    ],
)
def test_rejects_everything_else(raw: str | None) -> None:
    # Rejected, NOT passed through as raw digits. A "keep whatever we got" fallback would
    # accept numbers the client cannot even produce, which is how duplicates start.
    assert to_stored(raw) is None
