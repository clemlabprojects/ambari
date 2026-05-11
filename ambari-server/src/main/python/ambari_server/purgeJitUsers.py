#!/usr/bin/env python3

"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

purgeJitUsers: bulk-revoke JIT-provisioned (JWT-authenticated) users.

Why this exists
---------------
``setup-sso-oidc --sso-oidc-user-auto-create=true`` auto-creates Ambari users on first JWT
presentation.  Over time the local user table accumulates JIT records for people who have left
the org, switched accounts, or shouldn't be in Ambari at all.  Single-deletion via the UI /
REST is slow and easy to forget.

This action lists candidates and (with ``--confirm``) deletes them through the Ambari REST API.
JIT users are identified by a sole ``UserAuthenticationType=JWT`` row in their
``/authentication_sources`` subresource.  Users with additional auth types (LOCAL, LDAP, ...)
are NOT purged even if they also have JWT — they have a non-OIDC path to authenticate.

Common operational flow
-----------------------
1. Disable revoked users in bulk via the UI / REST (sets ``Users/active=false``).  This
   immediately blocks logins regardless of JWT validity (``Users.validateLogin`` rejects
   inactive users).
2. Run ``ambari-server purge-jit-users --inactive=true --dry-run`` to preview what would
   be removed.
3. Run with ``--confirm`` to actually delete.  Re-runs are safe — already-deleted users
   simply don't appear in subsequent listings.

Filtering options
-----------------
* ``--inactive=true``     - only users with ``Users/active=false``
* ``--user=<name>``       - target a specific user (still safety-checked: must be JWT-only)
* ``--include-jwt-with-ldap`` - off by default; opt in to purge users with both JWT and LDAP
                                auth types (e.g. when migrating away from LDAP entirely)
"""

"""Imports are kept minimal at module scope so unit tests of pure-logic helpers
(`_is_jit_candidate`) can load this module without dragging in the full Ambari
runtime config (which requires ``/etc/ambari-server/conf/ambari.properties``
on disk).  Heavier imports (setupSecurity, serverConfiguration, ...) are done
lazily inside ``purge_jit_users()``.
"""

import json
import sys


def _client_url(ambari_properties, get_value_from_properties):
  """Construct the local Ambari Server URL using the server's own properties (port + SSL)."""
  port = get_value_from_properties(ambari_properties, "client.api.port", "8080")
  ssl_port = get_value_from_properties(ambari_properties, "client.api.ssl.port", "")
  api_ssl = get_value_from_properties(ambari_properties, "api.ssl", "false")
  if api_ssl and api_ssl.lower() == "true":
    return "https://localhost:{0}".format(ssl_port or port)
  return "http://localhost:{0}".format(port)


def _build_opener(admin_login, admin_password):
  """HTTP client with basic auth + tolerant TLS (the server's own cert may be self-signed)."""
  import ssl
  from urllib.request import build_opener, HTTPBasicAuthHandler, HTTPPasswordMgrWithDefaultRealm, HTTPSHandler
  pw_mgr = HTTPPasswordMgrWithDefaultRealm()
  pw_mgr.add_password(None, "http://localhost", admin_login, admin_password)
  pw_mgr.add_password(None, "https://localhost", admin_login, admin_password)
  ctx = ssl._create_unverified_context()
  return build_opener(HTTPBasicAuthHandler(pw_mgr), HTTPSHandler(context=ctx))


def _http_json(opener, method, url, body=None):
  """Issue a single HTTP call returning parsed JSON (or {} for non-JSON 204 responses)."""
  from urllib.request import Request
  from urllib.error import HTTPError
  from ambari_commons.exceptions import FatalException
  data = None
  if body is not None:
    data = json.dumps(body).encode("utf-8")
  req = Request(url, data=data)
  req.get_method = lambda: method
  req.add_header("X-Requested-By", "ambari")
  req.add_header("Content-Type", "application/json")
  try:
    resp = opener.open(req)
    raw = resp.read()
    return json.loads(raw.decode("utf-8")) if raw else {}
  except HTTPError as e:
    raw = e.read()
    try:
      body = json.loads(raw.decode("utf-8")) if raw else {}
    except Exception:
      body = {"raw": raw.decode("utf-8", errors="replace")}
    raise FatalException(e.code, "HTTP {0} on {1} {2}: {3}".format(e.code, method, url, body))


def _list_users(opener, base_url):
  """Return [{name, active}] for all Ambari users."""
  data = _http_json(opener, "GET", base_url + "/api/v1/users?fields=Users/active")
  out = []
  for item in data.get("items", []):
    u = item.get("Users", {})
    if "user_name" in u:
      out.append({"name": u["user_name"], "active": bool(u.get("active", True))})
  return out


def _auth_sources(opener, base_url, user_name):
  """Return the set of UserAuthenticationType strings configured for a given user."""
  data = _http_json(opener, "GET",
    base_url + "/api/v1/users/{0}/sources?fields=AuthenticationSourceInfo/authentication_type".format(user_name))
  out = set()
  for item in data.get("items", []):
    info = item.get("AuthenticationSourceInfo", {})
    if "authentication_type" in info:
      out.add(info["authentication_type"])
  return out


def _is_jit_candidate(auth_types, include_jwt_with_ldap):
  """A user is a JIT-purge candidate iff JWT is in their auth types AND either:
  * JWT is the ONLY auth type they have (the common case for OIDC-only deployments)
  * --include-jwt-with-ldap was passed and they have JWT + LDAP only (no LOCAL/KERBEROS/PAM)
  """
  if "JWT" not in auth_types:
    return False
  others = auth_types - {"JWT"}
  if not others:
    return True
  if include_jwt_with_ldap and others == {"LDAP"}:
    return True
  return False


def _delete_user(opener, base_url, user_name):
  _http_json(opener, "DELETE", base_url + "/api/v1/users/{0}".format(user_name))


def purge_jit_users(options):
  """Action entry point — invoked from ``ambari-server purge-jit-users``."""
  # Lazy imports: keep the module unit-testable in isolation (these imports require
  # /etc/ambari-server/conf/ambari.properties to exist on disk, which a unit-test env doesn't have).
  from ambari_commons.exceptions import FatalException, NonFatalException
  from ambari_commons.logging_utils import print_info_msg, print_warning_msg
  from ambari_server.setupSecurity import get_ambari_admin_username_password_pair
  from ambari_server.serverConfiguration import get_ambari_properties, get_value_from_properties
  from ambari_server.userInput import get_YN_input

  print_info_msg("Purging JIT (JWT-authenticated) Ambari users")

  ambari_properties = get_ambari_properties()
  admin_login, admin_password = get_ambari_admin_username_password_pair(options)
  base_url = _client_url(ambari_properties, get_value_from_properties)
  opener = _build_opener(admin_login, admin_password)

  inactive_only = (getattr(options, "purge_jit_inactive", None) == "true")
  include_jwt_with_ldap = (getattr(options, "purge_jit_include_jwt_with_ldap", None) == "true")
  target_user = getattr(options, "purge_jit_user", None)
  dry_run = (getattr(options, "purge_jit_dry_run", None) == "true")
  confirm = (getattr(options, "purge_jit_confirm", None) == "true")

  candidates = []
  if target_user:
    candidates.append({"name": target_user, "active": True})
  else:
    candidates = _list_users(opener, base_url)
    if inactive_only:
      candidates = [u for u in candidates if not u["active"]]

  # Safety filter: confirm each candidate has only JWT-typed auth (or JWT+LDAP if opted-in).
  jit_users = []
  skipped = []
  for u in candidates:
    auth_types = _auth_sources(opener, base_url, u["name"])
    if not auth_types:
      skipped.append((u["name"], "no auth sources"))
      continue
    if _is_jit_candidate(auth_types, include_jwt_with_ldap):
      jit_users.append({"name": u["name"], "active": u["active"], "auth_types": sorted(auth_types)})
    else:
      skipped.append((u["name"], "non-JIT auth types: " + ",".join(sorted(auth_types))))

  sys.stdout.write("\n=== Purge candidates ({0}) ===\n".format(len(jit_users)))
  for u in jit_users:
    sys.stdout.write("  {0:30s} active={1!s:5s} auth={2}\n".format(u["name"], u["active"], ",".join(u["auth_types"])))
  if skipped and target_user:
    sys.stdout.write("\n=== Skipped (safety filter) ===\n")
    for name, reason in skipped:
      sys.stdout.write("  {0:30s} {1}\n".format(name, reason))

  if dry_run:
    sys.stdout.write("\n--dry-run: no deletions performed.\n")
    return

  if not jit_users:
    sys.stdout.write("Nothing to purge.\n")
    return

  if not confirm:
    if not get_YN_input("\nProceed with deletion of {0} user(s) [y/n] (n)? ".format(len(jit_users)), False):
      raise NonFatalException("Aborted by operator.")

  deleted = 0
  failures = []
  for u in jit_users:
    try:
      _delete_user(opener, base_url, u["name"])
      sys.stdout.write("  DELETED  {0}\n".format(u["name"]))
      deleted += 1
    except FatalException as e:
      print_warning_msg("Failed to delete {0}: {1}".format(u["name"], e.reason))
      failures.append((u["name"], e.reason))

  sys.stdout.write("\nDeleted {0} JIT user(s); {1} failure(s).\n".format(deleted, len(failures)))
  if failures:
    raise FatalException(1, "Some deletions failed: " + "; ".join("{0}={1}".format(n, r) for n, r in failures))
