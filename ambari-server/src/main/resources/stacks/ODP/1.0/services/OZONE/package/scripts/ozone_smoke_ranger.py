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

Ranger policies of the Ozone service check. With the Ranger Ozone plugin, Ranger authorizes the creation
of a volume against policies on the volume alone, and of a bucket against policies on the volume and the
bucket: a policy on the keys does not cover them, so the smoke user needs one policy per level.
"""

import base64
import json
import ssl
import urllib.error
import urllib.parse
import urllib.request

from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger

SMOKE_VOLUME = "ambarismokevolume"
SMOKE_BUCKET = "ambarismokebucket"
# name of the key policy Ambari created before, kept so that existing clusters reuse it
POLICY_NAME = "[AMBARI] - Ozone Smoke User Check"
ACCESSES = ["read", "write", "create", "list", "delete"]


def smoke_policies():
  """Policies the smoke user needs: (name, resources, accesses), on the volume, the bucket and its keys."""
  return [
    (POLICY_NAME + " - volume", {"volume": [SMOKE_VOLUME]}, ACCESSES),
    (POLICY_NAME + " - bucket", {"volume": [SMOKE_VOLUME], "bucket": [SMOKE_BUCKET]}, ACCESSES),
    (POLICY_NAME, {"volume": [SMOKE_VOLUME], "bucket": [SMOKE_BUCKET], "key": ["*"]}, ACCESSES),
  ]


def ensure_smoke_policies(ranger_url, service_name, admin_user, admin_password, smoke_user):
  """Creates the missing policies, or adds the smoke user to a policy already covering the same resources."""
  for name, resources, accesses in smoke_policies():
    policy = _find_policy(ranger_url, service_name, admin_user, admin_password, name, resources)
    if policy is None:
      _create_policy(ranger_url, service_name, admin_user, admin_password, name, resources, smoke_user, accesses)
    elif _grants(policy, smoke_user, accesses):
      Logger.info("Ranger Ozone policy '{0}' already grants {1} to '{2}'.".format(policy.get("name"), ",".join(accesses), smoke_user))
    else:
      policy.setdefault("policyItems", []).append(_policy_item(smoke_user, accesses))
      status, payload = _ranger_http("PUT", "{0}/service/public/v2/api/policy/{1}".format(ranger_url, policy["id"]),
                                     admin_user, admin_password, policy)
      if status not in (200, 204):
        raise Fail("Cannot add '{0}' to the Ranger Ozone policy '{1}': HTTP {2} {3}".format(smoke_user, policy.get("name"), status, payload))
      Logger.info("Added '{0}' to the Ranger Ozone policy '{1}'.".format(smoke_user, policy.get("name")))


def _find_policy(ranger_url, service_name, admin_user, admin_password, name, resources):
  """The policy with this name, else the policy whose resources are exactly these ones (any name)."""
  base = "{0}/service/public/v2/api/service/{1}/policy".format(ranger_url, urllib.parse.quote(service_name, safe=""))
  status, payload = _ranger_http("GET", base + "?policyName=" + urllib.parse.quote(name, safe=""), admin_user, admin_password)
  if status != 200:
    raise Fail("Cannot read the Ranger Ozone policies of '{0}': HTTP {1} {2}".format(service_name, status, payload))
  for policy in payload if isinstance(payload, list) else []:
    if policy.get("name") == name:
      return policy

  query = "&".join("resource:{0}={1}".format(key, urllib.parse.quote(values[0], safe="")) for key, values in resources.items())
  status, payload = _ranger_http("GET", base + "?" + query, admin_user, admin_password)
  for policy in payload if status == 200 and isinstance(payload, list) else []:
    policy_resources = {key: sorted(value.get("values", [])) for key, value in policy.get("resources", {}).items()}
    if policy_resources == {key: sorted(values) for key, values in resources.items()}:
      return policy
  return None


def _create_policy(ranger_url, service_name, admin_user, admin_password, name, resources, smoke_user, accesses):
  body = {
    "service": service_name,
    "name": name,
    "description": "Ozone service check, managed by Ambari",
    "isEnabled": True,
    "isAuditEnabled": True,
    "resources": {key: {"values": values, "isExcludes": False, "isRecursive": False} for key, values in resources.items()},
    "policyItems": [_policy_item(smoke_user, accesses)],
  }
  status, payload = _ranger_http("POST", "{0}/service/public/v2/api/policy".format(ranger_url), admin_user, admin_password, body)
  if status not in (200, 201):
    raise Fail("Cannot create the Ranger Ozone policy '{0}': HTTP {1} {2}".format(name, status, payload))
  Logger.info("Created the Ranger Ozone policy '{0}' for '{1}'.".format(name, smoke_user))


def _policy_item(user, accesses):
  return {"users": [user], "groups": [], "accesses": [{"type": access, "isAllowed": True} for access in accesses],
          "delegateAdmin": False}


def _grants(policy, user, accesses):
  granted = set()
  for item in policy.get("policyItems", []):
    if user in item.get("users", []):
      granted.update(access.get("type") for access in item.get("accesses", []) if access.get("isAllowed", True))
  return "all" in granted or set(accesses).issubset(granted)


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
