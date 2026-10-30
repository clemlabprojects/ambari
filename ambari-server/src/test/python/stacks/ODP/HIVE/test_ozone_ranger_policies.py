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
import sys
from types import ModuleType
import unittest
from unittest.mock import patch

from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger

Logger.initialize_logger()


SCRIPTS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.0/services/HIVE/package/scripts"
spec = importlib.util.spec_from_file_location("odp_hive_ozone_ranger", SCRIPTS / "hive_ozone_ranger.py")
ozone_ranger = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ozone_ranger)

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
      name = url.split("policyName=", 1)[1]
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

  def named(self, name):
    return [p for p in self.policies if p["name"] == name]


def resources(**values):
  return {key: {"values": value} for key, value in values.items()}


class TestHiveOzoneBuckets(unittest.TestCase):

  def test_ofs_paths_and_paths_on_an_ozone_default_filesystem(self):
    self.assertEqual([("warehouse", "tablespace"), ("user", "hive")], ozone_ranger.hive_ozone_buckets(
      ["/warehouse/tablespace/managed/hive", "ofs://omservice/warehouse/tablespace/external/hive", "/user/hive"],
      "ofs://omservice"))

  def test_o3fs_paths_name_their_bucket_and_volume_in_the_authority(self):
    self.assertEqual([("warehouse", "tablespace")], ozone_ranger.hive_ozone_buckets(
      ["o3fs://tablespace.warehouse.omservice/managed/hive", "/external/hive"], "o3fs://tablespace.warehouse.omservice"))

  def test_the_per_user_tmp_mount_is_not_a_hive_bucket(self):
    self.assertEqual([("user", "hive")], ozone_ranger.hive_ozone_buckets(
      ["/tmp/hive", "ofs://omservice/tmp/hive", "/user/hive/scratch"], "ofs://omservice"))

  def test_paths_outside_ozone_or_above_the_bucket_level_are_skipped(self):
    self.assertEqual([], ozone_ranger.hive_ozone_buckets(
      ["/warehouse/tablespace/managed/hive", "s3a://bucket/hive", "ofs://omservice/warehouse", None],
      "hdfs://nn:8020"))


class TestEnsureHiveOzonePolicies(unittest.TestCase):

  def ensure(self, ranger, buckets=(("warehouse", "tablespace"),)):
    with patch.object(ozone_ranger, "_ranger_http", ranger):
      ozone_ranger.ensure_hive_ozone_policies(RANGER, SERVICE, "admin", "secret", "hive", list(buckets))

  def test_creates_the_missing_policies_with_their_label(self):
    ranger = FakeRanger()
    self.ensure(ranger)

    volume, bucket = ranger.named("hive-ozone-warehouse"), ranger.named("hive-ozone-warehouse-tablespace")
    self.assertEqual(["read", "list"], [a["type"] for a in volume[0]["policyItems"][0]["accesses"]])
    self.assertEqual({"volume": ["warehouse"], "bucket": ["tablespace"], "key": ["*"]},
                     {k: v["values"] for k, v in bucket[0]["resources"].items()})
    self.assertEqual(["ambari-hive-ozone"], bucket[0]["policyLabels"])
    self.assertEqual(["hive"], bucket[0]["policyItems"][0]["users"])

  def test_existing_policies_granting_hive_are_left_as_they_are(self):
    ranger = FakeRanger()
    self.ensure(ranger)
    calls = len(ranger.calls)
    self.ensure(ranger)
    self.assertEqual([], [c for c in ranger.calls[calls:] if c[0] != "GET"])

  def test_hive_is_added_to_a_policy_already_covering_the_same_resources(self):
    other = {"name": "Hive warehouse - warehouse/tablespace", "resources": resources(volume=["warehouse"], bucket=["tablespace"], key=["*"]),
             "policyItems": [{"users": ["etl"], "accesses": [{"type": "all", "isAllowed": True}]}]}
    ranger = FakeRanger([other])
    self.ensure(ranger)

    self.assertEqual([], ranger.named("hive-ozone-warehouse-tablespace"))
    items = ranger.named("Hive warehouse - warehouse/tablespace")[0]["policyItems"]
    self.assertEqual([["etl"], ["hive"]], [item["users"] for item in items])


class TestSetupHiveOzoneRangerPolicies(unittest.TestCase):

  def setUp(self):
    self.params = ModuleType("params")
    self.params.hive_ozone_ranger_buckets = [("warehouse", "tablespace")]
    self.params.hive_ozone_ranger_enabled = True
    self.params.hive_ozone_ranger_url = RANGER
    self.params.hive_ozone_ranger_service = SERVICE
    self.params.hive_ozone_ranger_admin_user = "admin"
    self.params.hive_ozone_ranger_admin_password = "secret"
    self.params.hive_user = "hive"
    sys.modules["params"] = self.params

  def tearDown(self):
    sys.modules.pop("params", None)

  def test_disabled_plugin_is_skipped_on_start_and_refused_by_the_action(self):
    self.params.hive_ozone_ranger_enabled = False
    ozone_ranger.setup_hive_ozone_ranger_policies()
    with self.assertRaises(Fail) as error:
      ozone_ranger.setup_hive_ozone_ranger_policies(required=True)
    self.assertIn("the Ozone Ranger plugin is not enabled", str(error.exception))

  def test_unreachable_ranger_only_warns_on_start(self):
    def down(*args, **kwargs):
      raise Fail("Cannot reach Ranger Admin")
    with patch.object(ozone_ranger, "_ranger_http", down), patch.object(ozone_ranger.Logger, "warning") as warning:
      ozone_ranger.setup_hive_ozone_ranger_policies()
      self.assertIn("Create Ozone Ranger policies", warning.call_args[0][0])
      with self.assertRaises(Fail):
        ozone_ranger.setup_hive_ozone_ranger_policies(required=True)


if __name__ == "__main__":
  unittest.main()
