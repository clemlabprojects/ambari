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

"""

import base64
import json
import re
import ssl
import urllib.error
import urllib.parse
import urllib.request

from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.libraries.functions.ranger_functions_v2 import RangeradminV2


def _ranger_admin():
  import params
  return RangeradminV2(url=params.ranger_policymgr_url, skip_if_rangeradmin_down=True)


def create_spark_ranger_user():
  """
  Declares the spark user in Ranger, so that policies can name it before the user sync has brought it.
  Never fails the command: the Spark servers do not need Ranger to start.
  """
  import params

  if not params.ranger_yarn_authorization:
    return
  try:
    _ranger_admin().create_policy_user(params.ranger_admin_username, params.ranger_admin_password, params.spark_user)
  except Exception as e:
    Logger.warning("Could not create the user %s in Ranger: %s" % (params.spark_user, str(e)))


POLICY_NAME = "spark-thriftserver-yarn-queue"
POLICY_LABEL = "ambari-spark-yarn"
ACCESSES = ["submit-app"]


def create_thrift_server_yarn_policy():
  """
  Lets the spark user submit to the YARN queue of the Thrift server. With the Ranger YARN plugin, a new cluster
  only authorizes the yarn user and the service check user, and the Thrift server fails to start.
  Never fails the command: a problem only logs a warning.
  """
  import params

  if not params.ranger_yarn_authorization or not params.spark_thrift_on_yarn:
    return
  try:
    ensure_yarn_policy(params.ranger_policymgr_url, params.ranger_yarn_repo_name, params.ranger_admin_username,
                       params.ranger_admin_password, params.spark_user, params.spark_thrift_yarn_queue)
  except Exception as e:
    Logger.warning("The Ranger YARN policy of the Spark Thrift server is not set, YARN may refuse its application: %s" % str(e))


def ensure_yarn_policy(ranger_url, service_name, admin_user, admin_password, user, queue):
  """Creates the policy, or grants the user in the policy that already owns the queue."""
  base = "{0}/service/public/v2/api/service/{1}/policy".format(ranger_url, urllib.parse.quote(service_name, safe=""))
  policy = _find_policy(base, POLICY_NAME, admin_user, admin_password)
  if policy is None:
    body = {
      "service": service_name,
      "name": POLICY_NAME,
      "description": "Application of the Spark Thrift server, managed by Ambari (Thrift server start)",
      "policyLabels": [POLICY_LABEL],
      "isEnabled": True,
      "isAuditEnabled": True,
      "resources": {"queue": {"values": [queue], "isExcludes": False, "isRecursive": False}},
      "policyItems": [_policy_item(user)],
    }
    status, payload = _ranger_http("POST", "{0}/service/public/v2/api/policy".format(ranger_url),
                                   admin_user, admin_password, body)
    if status in (200, 201):
      Logger.info("Created the Ranger YARN policy '{0}' for '{1}' on {2}.".format(POLICY_NAME, user, queue))
      return
    # Ranger refuses two policies on the same resources: grant the user in the policy owning the queue
    owner = re.search(r"policy-name=\[(.*?)\]", json.dumps(payload))
    if status != 400 or not owner:
      raise Fail("Cannot create the Ranger YARN policy '{0}': HTTP {1} {2}".format(POLICY_NAME, status, payload))
    policy = _find_policy(base, owner.group(1), admin_user, admin_password)
    if policy is None:
      raise Fail("Cannot read the Ranger YARN policy '{0}' owning the queue {1}.".format(owner.group(1), queue))

  if _grants(policy, user):
    Logger.info("Ranger YARN policy '{0}' already grants submit-app to '{1}'.".format(policy.get("name"), user))
    return
  policy.setdefault("policyItems", []).append(_policy_item(user))
  status, payload = _ranger_http("PUT", "{0}/service/public/v2/api/policy/{1}".format(ranger_url, policy["id"]),
                                 admin_user, admin_password, policy)
  if status not in (200, 204):
    raise Fail("Cannot update the Ranger YARN policy '{0}': HTTP {1} {2}".format(policy.get("name"), status, payload))
  Logger.info("Granted submit-app to '{0}' in the Ranger YARN policy '{1}'.".format(user, policy.get("name")))


def _find_policy(base, name, admin_user, admin_password):
  status, payload = _ranger_http("GET", base + "?policyName=" + urllib.parse.quote(name, safe=""), admin_user, admin_password)
  if status != 200:
    raise Fail("Cannot read the Ranger YARN policies: HTTP {0} {1}".format(status, payload))
  return next((p for p in (payload if isinstance(payload, list) else []) if p.get("name") == name), None)


def _policy_item(user):
  return {"users": [user], "groups": [], "accesses": [{"type": access, "isAllowed": True} for access in ACCESSES],
          "delegateAdmin": False}


def _grants(policy, user):
  granted = set()
  for item in policy.get("policyItems", []):
    if user in item.get("users", []):
      granted.update(access.get("type") for access in item.get("accesses", []) if access.get("isAllowed", True))
  return "all" in granted or "admin-queue" in granted or set(ACCESSES).issubset(granted)


def _ranger_http(method, url, username, password, body=None, timeout=20):
  headers = {"Accept": "application/json",
             "Authorization": "Basic " + base64.b64encode("{0}:{1}".format(username, password).encode("utf-8")).decode("utf-8")}
  data = None
  if body is not None:
    data = json.dumps(body).encode("utf-8")
    headers["Content-Type"] = "application/json"
  request = urllib.request.Request(url=url, data=data, headers=headers, method=method)
  context = ssl._create_unverified_context() if url.lower().startswith("https://") else None
  try:
    response = urllib.request.urlopen(request, timeout=timeout, context=context)
    status, raw = response.getcode(), response.read().decode("utf-8")
  except urllib.error.HTTPError as error:
    status, raw = error.code, error.read().decode("utf-8") if error.fp else ""
  except urllib.error.URLError as error:
    raise Fail("Cannot reach Ranger Admin at {0}: {1}".format(url, error.reason))
  try:
    return status, json.loads(raw) if raw.strip() else {}
  except ValueError:
    return status, raw[:500]
