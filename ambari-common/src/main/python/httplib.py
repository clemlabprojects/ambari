#!/usr/bin/env python3

"""
Compatibility shim for Python 3 where httplib was renamed to http.client.
Provides minimal API surface used by Ambari scripts/tests.
"""

from http.client import (  # noqa: F401
    HTTPConnection,
    HTTPSConnection,
    HTTPResponse,
    HTTPException,
    responses,
)

__all__ = [
    "HTTPConnection",
    "HTTPSConnection",
    "HTTPResponse",
    "HTTPException",
    "responses",
]
