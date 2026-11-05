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

Ranger YARN policy of the Hive user. HiveServer2 runs its Tez sessions as the Hive user, and on a
Kerberized cluster the YARN queue ACLs only let yarn and the smoke user submit (YARN kerberos.json):
with the Ranger YARN plugin, the Hive user gets submit-app on the queues of its Tez sessions through the
policy POLICY_NAME, labelled POLICY_LABEL. Without the plugin, the service advisor adds the Hive user
to the queue ACLs instead.
"""

import json
import re
import urllib.parse

from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger

from hive_ozone_ranger import _grants, _policy_item, _ranger_http

POLICY_NAME = "hive-yarn-queues"
POLICY_LABEL = "ambari-hive-yarn"
ACCESSES = ["submit-app"]


def yarn_queue_paths(*queue_lists):
  """Capacity Scheduler paths (root.<queue>) of comma-separated queue lists, in order, without duplicates."""
  paths = []
  for queues in queue_lists:
    for queue in str(queues or "").split(","):
      queue = queue.strip()
      if not queue:
        continue
      path = queue if queue == "root" or queue.startswith("root.") else "root." + queue
      if path not in paths:
        paths.append(path)
  return paths


def setup_hive_yarn_ranger_policy():
  """Ensures the Ranger YARN policy of the Hive user on HiveServer2 start; a problem only logs a warning."""
  import params

  if not params.hive_yarn_ranger_enabled:
    Logger.info("Skipping the Ranger YARN policy of Hive: the Ranger YARN plugin is not enabled.")
    return
  if not params.hive_ozone_ranger_url or not params.hive_ozone_ranger_admin_password:
    Logger.info("Skipping the Ranger YARN policy of Hive: the Ranger Admin URL or the Ranger admin "
                "credentials (ranger-env) are not set.")
    return
  try:
    ensure_hive_yarn_policy(params.hive_ozone_ranger_url, params.hive_yarn_ranger_service,
                            params.hive_ozone_ranger_admin_user, params.hive_ozone_ranger_admin_password,
                            params.hive_user, params.hive_yarn_queues)
  except Fail as error:
    Logger.warning("The Ranger YARN policy of Hive is not set, YARN may refuse its Tez sessions: {0}".format(error))


def ensure_hive_yarn_policy(ranger_url, service_name, admin_user, admin_password, hive_user, queues):
  """Creates the policy, or completes its queues and its grant of the Hive user."""
  base = "{0}/service/public/v2/api/service/{1}/policy".format(ranger_url, urllib.parse.quote(service_name, safe=""))
  status, payload = _ranger_http("GET", base + "?policyName=" + urllib.parse.quote(POLICY_NAME, safe=""),
                                 admin_user, admin_password)
  if status != 200:
    raise Fail("Cannot read the Ranger YARN policies of '{0}': HTTP {1} {2}".format(service_name, status, payload))
  policy = next((p for p in (payload if isinstance(payload, list) else []) if p.get("name") == POLICY_NAME), None)

  if policy is None:
    body = {
      "service": service_name,
      "name": POLICY_NAME,
      "description": "Tez sessions of HiveServer2, managed by Ambari (HiveServer2 start)",
      "policyLabels": [POLICY_LABEL],
      "isEnabled": True,
      "isAuditEnabled": True,
      "resources": {"queue": {"values": queues, "isExcludes": False, "isRecursive": False}},
      "policyItems": [_policy_item(hive_user, ACCESSES)],
    }
    status, payload = _ranger_http("POST", "{0}/service/public/v2/api/policy".format(ranger_url),
                                   admin_user, admin_password, body)
    if status in (200, 201):
      Logger.info("Created the Ranger YARN policy '{0}' for '{1}' on {2}.".format(POLICY_NAME, hive_user, ", ".join(queues)))
      return
    # Ranger refuses two policies on the same resources: grant the Hive user in the policy owning them
    owner = re.search(r"policy-name=\[(.*?)\]", json.dumps(payload))
    if status != 400 or not owner:
      raise Fail("Cannot create the Ranger YARN policy '{0}': HTTP {1} {2}".format(POLICY_NAME, status, payload))
    status, payload = _ranger_http("GET", base + "?policyName=" + urllib.parse.quote(owner.group(1), safe=""),
                                   admin_user, admin_password)
    policy = next((p for p in (payload if status == 200 and isinstance(payload, list) else [])
                   if p.get("name") == owner.group(1)), None)
    if policy is None:
      raise Fail("Cannot read the Ranger YARN policy '{0}' owning the queues {1}.".format(owner.group(1), ", ".join(queues)))
    queues = []

  values = policy.setdefault("resources", {}).setdefault("queue", {"isExcludes": False, "isRecursive": False})
  missing = [queue for queue in queues if queue not in values.get("values", [])]
  granted = _grants(policy, hive_user, ACCESSES)
  if not missing and granted:
    Logger.info("Ranger YARN policy '{0}' already grants submit-app to '{1}'.".format(policy.get("name"), hive_user))
    return
  values["values"] = values.get("values", []) + missing
  if not granted:
    policy.setdefault("policyItems", []).append(_policy_item(hive_user, ACCESSES))
  status, payload = _ranger_http("PUT", "{0}/service/public/v2/api/policy/{1}".format(ranger_url, policy["id"]),
                                 admin_user, admin_password, policy)
  if status not in (200, 204):
    raise Fail("Cannot update the Ranger YARN policy '{0}': HTTP {1} {2}".format(POLICY_NAME, status, payload))
  Logger.info("Updated the Ranger YARN policy '{0}' for '{1}' on {2}.".format(policy.get("name"), hive_user, ", ".join(values["values"])))
