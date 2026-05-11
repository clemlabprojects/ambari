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

Tests for purgeJitUsers — focused on the safety-critical candidate filter
(`_is_jit_candidate`).  HTTP plumbing is verified by end-to-end ansible-cluster
runs; unit tests pin the auth-type filter so we cannot regress into accidentally
deleting LOCAL / KERBEROS / PAM users.
"""

import unittest

from ambari_server.purgeJitUsers import _is_jit_candidate


class TestPurgeJitUsers(unittest.TestCase):

  def test_jwt_only_is_candidate(self):
    """Pure-OIDC users (only JWT auth) are the canonical purge target."""
    self.assertTrue(_is_jit_candidate({"JWT"}, include_jwt_with_ldap=False))

  def test_local_only_never_candidate(self):
    """Local-password users must never be purged by this action — they have no JWT."""
    self.assertFalse(_is_jit_candidate({"LOCAL"}, include_jwt_with_ldap=False))

  def test_jwt_plus_local_never_candidate(self):
    """User has both JWT and a local password → still has a non-JIT path; do not purge."""
    self.assertFalse(_is_jit_candidate({"JWT", "LOCAL"}, include_jwt_with_ldap=False))

  def test_jwt_plus_kerberos_never_candidate(self):
    self.assertFalse(_is_jit_candidate({"JWT", "KERBEROS"}, include_jwt_with_ldap=False))

  def test_jwt_plus_pam_never_candidate(self):
    self.assertFalse(_is_jit_candidate({"JWT", "PAM"}, include_jwt_with_ldap=False))

  def test_jwt_plus_ldap_default_not_candidate(self):
    """LDAP-promoted-to-JWT users represent real LDAP identities; default behavior preserves them."""
    self.assertFalse(_is_jit_candidate({"JWT", "LDAP"}, include_jwt_with_ldap=False))

  def test_jwt_plus_ldap_with_optin_is_candidate(self):
    """Opt-in via --include-jwt-with-ldap=true (migrate-away-from-LDAP scenario)."""
    self.assertTrue(_is_jit_candidate({"JWT", "LDAP"}, include_jwt_with_ldap=True))

  def test_jwt_plus_ldap_plus_local_with_optin_still_not_candidate(self):
    """Even with opt-in, presence of LOCAL aborts — LOCAL means a non-LDAP/non-JWT path exists."""
    self.assertFalse(_is_jit_candidate({"JWT", "LDAP", "LOCAL"}, include_jwt_with_ldap=True))

  def test_empty_auth_types_not_candidate(self):
    """A user with no auth-source rows is in a degenerate state; refuse to delete it from here."""
    self.assertFalse(_is_jit_candidate(set(), include_jwt_with_ldap=False))


if __name__ == "__main__":
  unittest.main()
