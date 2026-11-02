#!/usr/bin/env python3
"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger

Logger.initialize_logger()


SCRIPTS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.0/services/OZONE/package/scripts"
spec = importlib.util.spec_from_file_location("odp_ozone_smoke_ranger", SCRIPTS / "ozone_smoke_ranger.py")
smoke_ranger = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke_ranger)

RANGER = "http://ranger:6080"
SERVICE = "cl_ozone"


class FakeRanger(object):
  """Ranger public v2 policy API of one service, in memory."""

  def __init__(self, policies=()):
    self.policies = [dict(policy, id=index) for index, policy in enumerate(policies, 1)]
    self.calls = []

  def __call__(self, method, url, username, password, body=None, timeout=20):
    self.calls.append((method, url))
    if method == "GET" and "policyName=" in url:
      name = smoke_ranger.urllib.parse.unquote(url.split("policyName=", 1)[1])
      return 200, [p for p in self.policies if p["name"] == name]
    if method == "GET":
      return 200, list(self.policies)
    if method == "POST":
      self.policies.append(dict(body, id=len(self.policies) + 1))
      return 200, body
    if method == "PUT":
      policy_id = int(url.rsplit("/", 1)[1])
      self.policies = [body if p["id"] == policy_id else p for p in self.policies]
      return 200, body
    return 405, {}

  def resources(self):
    return sorted(tuple(sorted(p["resources"])) for p in self.policies)


def resources(**values):
  return {key: {"values": value} for key, value in values.items()}


class TestOzoneSmokeRangerPolicies(unittest.TestCase):

  def ensure(self, ranger):
    with patch.object(smoke_ranger, "_ranger_http", ranger):
      smoke_ranger.ensure_smoke_policies(RANGER, SERVICE, "admin", "secret", "ambari-qa")

  def test_one_policy_per_level_so_the_volume_and_bucket_can_be_created(self):
    ranger = FakeRanger()
    self.ensure(ranger)
    self.assertEqual([("bucket", "key", "volume"), ("bucket", "volume"), ("volume",)], ranger.resources())
    for policy in ranger.policies:
      self.assertEqual(["ambari-qa"], policy["policyItems"][0]["users"])
      self.assertIn("create", [access["type"] for access in policy["policyItems"][0]["accesses"]])

  def test_the_key_policy_ambari_created_before_is_kept(self):
    old = {"name": smoke_ranger.POLICY_NAME,
           "resources": resources(volume=["ambarismokevolume"], bucket=["ambarismokebucket"], key=["*"]),
           "policyItems": [{"users": ["ambari-qa"], "accesses": [{"type": "all", "isAllowed": True}]}]}
    ranger = FakeRanger([old])
    self.ensure(ranger)
    self.assertEqual(3, len(ranger.policies))
    self.assertNotIn("PUT", [method for method, url in ranger.calls])
    self.assertEqual([smoke_ranger.POLICY_NAME], [p["name"] for p in ranger.policies if "key" in p["resources"]])

  def test_the_smoke_user_is_added_to_a_policy_on_the_same_volume(self):
    other = {"name": "volume admins", "resources": resources(volume=["ambarismokevolume"]),
             "policyItems": [{"users": ["ozone"], "accesses": [{"type": "all", "isAllowed": True}]}]}
    ranger = FakeRanger([other])
    self.ensure(ranger)
    volume_policies = [p for p in ranger.policies if sorted(p["resources"]) == ["volume"]]
    self.assertEqual(["volume admins"], [p["name"] for p in volume_policies])
    self.assertEqual([["ozone"], ["ambari-qa"]], [item["users"] for item in volume_policies[0]["policyItems"]])

  def test_a_ranger_error_fails_with_its_status(self):
    def denied(method, url, username, password, body=None, timeout=20):
      return 401, "Unauthorized"
    with self.assertRaisesRegex(Fail, "HTTP 401"):
      self.ensure(denied)


if __name__ == "__main__":
  unittest.main()
