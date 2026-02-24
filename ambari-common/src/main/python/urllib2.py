#!/usr/bin/env python3

"""
Compatibility shim for Python 3 where urllib2 was removed.
Provides minimal API surface used by Ambari scripts/tests.
"""

from urllib.request import (  # noqa: F401
    Request,
    urlopen,
    build_opener,
    install_opener,
    ProxyHandler,
    HTTPPasswordMgrWithDefaultRealm,
    HTTPBasicAuthHandler,
)
from urllib.error import HTTPError, URLError  # noqa: F401
from urllib.parse import urlencode, urlparse  # noqa: F401

__all__ = [
    "Request",
    "urlopen",
    "build_opener",
    "install_opener",
    "ProxyHandler",
    "HTTPPasswordMgrWithDefaultRealm",
    "HTTPBasicAuthHandler",
    "HTTPError",
    "URLError",
    "urlencode",
    "urlparse",
]
