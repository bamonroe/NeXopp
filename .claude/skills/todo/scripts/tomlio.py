"""Dependency-free TOML I/O for the todo skill.

Reading uses the stdlib :mod:`tomllib` (Python 3.11+). Writing is a small,
purpose-built serializer covering exactly the value types this skill stores:
``str``, ``int``, ``bool``, ``datetime.date`` and flat lists of those. It is
deliberately *not* a general TOML writer — keeping it narrow keeps it easy to
audit and keeps the on-disk files predictable.
"""

from __future__ import annotations

import datetime
import tomllib


def load(path):
    """Parse a TOML file into a dict."""
    with open(path, "rb") as fh:
        return tomllib.load(fh)


def loads(text):
    """Parse a TOML string into a dict."""
    return tomllib.loads(text)


def fmt_value(value):
    """Render a single value as TOML source text."""
    if isinstance(value, list):
        return "[" + ", ".join(fmt_value(item) for item in value) + "]"
    return _fmt_scalar(value)


def fmt_kv(key, value):
    """Render one ``key = value`` line (no trailing newline)."""
    return f"{key} = {fmt_value(value)}"


def _fmt_scalar(value):
    # bool must be checked before int (bool is a subclass of int).
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return repr(value)
    if isinstance(value, datetime.datetime):
        return value.isoformat()
    if isinstance(value, datetime.date):
        return value.isoformat()  # bare local date, e.g. 2026-07-31
    if isinstance(value, str):
        return _fmt_str(value)
    raise TypeError(f"unsupported TOML scalar: {type(value)!r}")


def _fmt_str(text):
    if "\n" in text:
        # Multi-line basic string. Escape backslashes and any triple-quote run.
        body = text.replace("\\", "\\\\").replace('"""', '""\\"')
        return '"""\n' + body + '"""'
    esc = (
        text.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\t", "\\t")
        .replace("\r", "\\r")
    )
    return '"' + esc + '"'
