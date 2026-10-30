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

Ranger policies of the Hive warehouse stored in Ozone. With the Ranger Ozone plugin, Ozone authorizes
with Ranger only (no fallback to Ozone ACLs), so the Hive user needs policies on the volumes and buckets
of its warehouse and home directories. The policies Ambari manages are identified by their name
(POLICY_PREFIX + volume[-bucket]) and carry the POLICY_LABEL label.
"""

import base64
import json
import ssl
import urllib.error
import urllib.parse
import urllib.request

from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger

POLICY_PREFIX = "hive-ozone-"
POLICY_LABEL = "ambari-hive-ozone"
VOLUME_ACCESSES = ["read", "list"]
BUCKET_ACCESSES = ["read", "write", "create", "list", "delete"]


def setup_hive_ozone_ranger_policies(required=False):
  """
  Ensures the Ranger policies of the Hive warehouse in Ozone. On Metastore start (required=False) a
  problem only logs a warning; the Hive service action (required=True) fails with it.
  """
  import params

  problem = None
  if not params.hive_ozone_ranger_buckets:
    problem = "Hive does not store its warehouse in Ozone"
  elif not params.hive_ozone_ranger_enabled:
    problem = "the Ozone Ranger plugin is not enabled"
  elif not params.hive_ozone_ranger_url or not params.hive_ozone_ranger_admin_password:
    problem = "the Ranger Admin URL or the Ranger admin credentials (ranger-env) are not set"
  if problem:
    if required:
      raise Fail("Cannot create the Ozone Ranger policies of Hive: {0}.".format(problem))
    Logger.info("Skipping the Ozone Ranger policies of Hive: {0}.".format(problem))
    return

  try:
    ensure_hive_ozone_policies(params.hive_ozone_ranger_url, params.hive_ozone_ranger_service,
                               params.hive_ozone_ranger_admin_user, params.hive_ozone_ranger_admin_password,
                               params.hive_user, params.hive_ozone_ranger_buckets)
  except Fail as error:
    if required:
      raise
    Logger.warning("The Ozone Ranger policies of Hive are not set: {0} Run the Hive service action "
                   "'Create Ozone Ranger policies' once Ranger Admin is available.".format(error))


def hive_ozone_buckets(paths, default_fs):
  """
  (volume, bucket) of each Hive path stored in Ozone, in order and without duplicates:
  ofs://<om service>/<volume>/<bucket>/..., o3fs://<bucket>.<volume>[.<om>]/... or a path without
  scheme on an Ozone default filesystem. Paths outside Ozone or above the bucket level are skipped.
  """
  default_url = urllib.parse.urlparse(str(default_fs or "").strip())
  buckets = []
  for path in paths:
    url = urllib.parse.urlparse(str(path or "").strip())
    if not url.path and not url.netloc:
      continue
    scheme, netloc = (url.scheme, url.netloc) if url.scheme else (default_url.scheme, default_url.netloc)
    bucket = None
    if scheme == "ofs":
      parts = [part for part in url.path.split("/") if part]
      if len(parts) >= 2:
        bucket = (parts[0], parts[1])
    elif scheme == "o3fs":
      host_parts = netloc.split(":")[0].split(".")
      if len(host_parts) >= 2 and host_parts[0] and host_parts[1]:
        bucket = (host_parts[1], host_parts[0])
    if bucket and bucket not in buckets:
      buckets.append(bucket)
  return buckets


def hive_ozone_policies(buckets):
  """Policies the Hive user needs: (name, resources, accesses), one per volume and one per bucket."""
  policies = []
  for volume in sorted({volume for volume, bucket in buckets}):
    policies.append((POLICY_PREFIX + volume, {"volume": [volume]}, VOLUME_ACCESSES))
  for volume, bucket in buckets:
    policies.append(("{0}{1}-{2}".format(POLICY_PREFIX, volume, bucket),
                     {"volume": [volume], "bucket": [bucket], "key": ["*"]}, BUCKET_ACCESSES))
  return policies


def ensure_hive_ozone_policies(ranger_url, service_name, admin_user, admin_password, hive_user, buckets):
  """Creates the missing policies, or adds the Hive user to a policy already covering the same resources."""
  for name, resources, accesses in hive_ozone_policies(buckets):
    policy = _find_policy(ranger_url, service_name, admin_user, admin_password, name, resources)
    if policy is None:
      _create_policy(ranger_url, service_name, admin_user, admin_password, name, resources, hive_user, accesses)
    elif _grants(policy, hive_user, accesses):
      Logger.info("Ranger Ozone policy '{0}' already grants {1} to '{2}'.".format(policy.get("name"), ",".join(accesses), hive_user))
    else:
      policy.setdefault("policyItems", []).append(_policy_item(hive_user, accesses))
      status, payload = _ranger_http("PUT", "{0}/service/public/v2/api/policy/{1}".format(ranger_url, policy["id"]),
                                     admin_user, admin_password, policy)
      if status not in (200, 204):
        raise Fail("Cannot add '{0}' to the Ranger Ozone policy '{1}': HTTP {2} {3}".format(hive_user, policy.get("name"), status, payload))
      Logger.info("Added '{0}' to the Ranger Ozone policy '{1}'.".format(hive_user, policy.get("name")))


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


def _create_policy(ranger_url, service_name, admin_user, admin_password, name, resources, hive_user, accesses):
  body = {
    "service": service_name,
    "name": name,
    "description": "Hive warehouse in Ozone, managed by Ambari (Hive Metastore start)",
    "policyLabels": [POLICY_LABEL],
    "isEnabled": True,
    "isAuditEnabled": True,
    "resources": {key: {"values": values, "isExcludes": False, "isRecursive": False} for key, values in resources.items()},
    "policyItems": [_policy_item(hive_user, accesses)],
  }
  status, payload = _ranger_http("POST", "{0}/service/public/v2/api/policy".format(ranger_url), admin_user, admin_password, body)
  if status not in (200, 201):
    raise Fail("Cannot create the Ranger Ozone policy '{0}': HTTP {1} {2}".format(name, status, payload))
  Logger.info("Created the Ranger Ozone policy '{0}' for '{1}'.".format(name, hive_user))


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
