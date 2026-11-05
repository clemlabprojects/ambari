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

The Hive user may submit the Tez sessions of HiveServer2 on a Kerberized cluster: a Ranger YARN policy with
the Ranger YARN plugin, the Capacity Scheduler submit ACL without it.
"""

import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

from resource_management.core.logger import Logger

Logger.initialize_logger()

SERVICE = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.0/services/HIVE"
sys.path.insert(0, str(SERVICE / "package/scripts"))

spec = importlib.util.spec_from_file_location("odp_hive_yarn_ranger", SERVICE / "package/scripts/hive_yarn_ranger.py")
yarn_ranger = importlib.util.module_from_spec(spec)
spec.loader.exec_module(yarn_ranger)

spec = importlib.util.spec_from_file_location("odp_hive_queue_advisor", SERVICE / "service_advisor.py")
ADVISOR = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ADVISOR)

RANGER = "https://ranger:6182"
SERVICE_NAME = "cl_yarn"


class FakeRanger(object):
  """Ranger public v2 policy API of one service, in memory."""

  def __init__(self, policies=()):
    self.policies = [dict(policy, id=index) for index, policy in enumerate(policies, 1)]
    self.calls = []

  def __call__(self, method, url, username, password, body=None, timeout=20):
    self.calls.append(method)
    if method == "GET":
      name = url.split("policyName=", 1)[1]
      return 200, [p for p in self.policies if p["name"] == name]
    if method == "POST":
      for policy in self.policies:
        if policy["resources"]["queue"]["values"] == body["resources"]["queue"]["values"]:
          return 400, {"statusCode": 1, "msgDesc": "(0) Validation failure: error code[3010], reason[Another policy "
                       "already exists for matching resource: policy-name=[{0}], service=[cl_yarn]]".format(policy["name"])}
      self.policies.append(dict(body, id=len(self.policies) + 1))
      return 200, body
    if method == "PUT":
      policy_id = int(url.rsplit("/", 1)[1])
      self.policies = [body if p["id"] == policy_id else p for p in self.policies]
      return 200, body
    return 405, {}


def ensure(ranger, queues):
  with patch.object(yarn_ranger, "_ranger_http", ranger):
    yarn_ranger.ensure_hive_yarn_policy(RANGER, SERVICE_NAME, "admin", "secret", "hive", queues)


class TestQueuePaths(unittest.TestCase):

  def test_queues_get_their_root_path_once(self):
    self.assertEqual(["root.default", "root.etl"], yarn_ranger.yarn_queue_paths("default, root.etl", "default"))
    self.assertEqual([], yarn_ranger.yarn_queue_paths("", None))
    self.assertEqual(["root.default"], ADVISOR.hive_queue_paths("", None))


class TestRangerYarnPolicy(unittest.TestCase):

  def test_creates_the_policy(self):
    ranger = FakeRanger()
    ensure(ranger, ["root.default"])
    policy = ranger.policies[0]
    self.assertEqual("hive-yarn-queues", policy["name"])
    self.assertEqual(["root.default"], policy["resources"]["queue"]["values"])
    self.assertEqual(["hive"], policy["policyItems"][0]["users"])
    self.assertEqual(["submit-app"], [a["type"] for a in policy["policyItems"][0]["accesses"]])

  def test_completes_the_queues_of_the_policy(self):
    ranger = FakeRanger([{"name": "hive-yarn-queues", "resources": {"queue": {"values": ["root.default"]}},
                          "policyItems": [{"users": ["hive"], "accesses": [{"type": "submit-app", "isAllowed": True}]}]}])
    ensure(ranger, ["root.default", "root.etl"])
    self.assertEqual(["root.default", "root.etl"], ranger.policies[0]["resources"]["queue"]["values"])
    self.assertEqual(1, len(ranger.policies[0]["policyItems"]))

  def test_grants_in_the_policy_already_owning_the_queues(self):
    # Ranger refuses two policies on the same resources (here the Amoro optimizer queue)
    ranger = FakeRanger([{"name": "amoro-yarn-optimizer-queue", "resources": {"queue": {"values": ["root.default"]}},
                          "policyItems": [{"users": ["amoro"], "accesses": [{"type": "submit-app", "isAllowed": True}]}]}])
    ensure(ranger, ["root.default"])
    self.assertEqual(1, len(ranger.policies))
    self.assertEqual([["amoro"], ["hive"]], [item["users"] for item in ranger.policies[0]["policyItems"]])
    self.assertEqual(["root.default"], ranger.policies[0]["resources"]["queue"]["values"])

  def test_leaves_a_complete_policy_alone(self):
    ranger = FakeRanger([{"name": "hive-yarn-queues", "resources": {"queue": {"values": ["root.default"]}},
                          "policyItems": [{"users": ["hive"], "accesses": [{"type": "submit-app", "isAllowed": True}]}]}])
    ensure(ranger, ["root.default"])
    self.assertEqual(["GET"], ranger.calls)


class TestQueueAclRecommendation(unittest.TestCase):

  def recommend(self, capacity_scheduler, ranger_yarn="No", queues="default"):
    services = {
      "services": [{"StackServices": {"service_name": name}} for name in ("HIVE", "YARN")],
      "configurations": {
        "capacity-scheduler": {"properties": capacity_scheduler},
        "hive-env": {"properties": {"hive_user": "hive"}},
        "hive-site": {"properties": {"hive.server2.tez.default.queues": queues}},
        "ranger-yarn-plugin-properties": {"properties": {"ranger-yarn-plugin-enabled": ranger_yarn}},
      },
    }
    configurations = {}
    ADVISOR.HiveRecommender().recommendHiveQueueAcl(configurations, services)
    return configurations.get("capacity-scheduler", {}).get("properties", {})

  KERBEROS_ACLS = {"yarn.scheduler.capacity.root.acl_submit_applications": "yarn,ambari-qa",
                   "yarn.scheduler.capacity.root.default.acl_submit_applications": "yarn,yarn-ats"}

  def test_the_kerberos_acls_get_the_hive_user(self):
    self.assertEqual({"yarn.scheduler.capacity.root.default.acl_submit_applications": "yarn,yarn-ats,hive"},
                     self.recommend(dict(self.KERBEROS_ACLS)))

  def test_nothing_when_a_parent_allows_everyone(self):
    acls = dict(self.KERBEROS_ACLS, **{"yarn.scheduler.capacity.root.acl_submit_applications": "*"})
    self.assertEqual({}, self.recommend(acls))

  def test_nothing_with_the_ranger_yarn_plugin(self):
    self.assertEqual({}, self.recommend(dict(self.KERBEROS_ACLS), ranger_yarn="Yes"))


if __name__ == "__main__":
  unittest.main()
