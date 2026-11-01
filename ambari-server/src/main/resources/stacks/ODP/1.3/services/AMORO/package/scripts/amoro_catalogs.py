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

Registration of the cluster catalogs in Amoro: the Hive Metastore, and the Ozone catalog Ambari
manages in Polaris (with the Polaris principal of Amoro, its grants, and its Ozone S3 credential).
"""
import base64
import json
import os
import re
import secrets
import shlex
import ssl
import urllib.error
import urllib.parse
import urllib.request
import uuid

from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.libraries.functions.get_user_call_output import get_user_call_output

import amoro

# The Ranger equivalent of the CATALOG_MANAGE_CONTENT grant, one policy per resource level of the
# Ranger Polaris service definition (Ranger matches a request against the levels it carries).
POLARIS_RANGER_LEVELS = [
  ("catalog", [], ["catalog-content-manage", "catalog-metadata-full", "catalog-list",
                   "catalog-properties-read", "catalog-properties-write"]),
  ("namespace", ["namespace"], ["namespace-create", "namespace-drop", "namespace-list", "namespace-metadata-full",
                                "namespace-properties-read", "namespace-properties-write"]),
  ("table", ["namespace", "table"], ["table-create", "table-drop", "table-list", "table-data-read",
                                     "table-data-write", "table-metadata-full", "table-properties-read",
                                     "table-properties-write"]),
]


def register_catalogs(required=False):
  """Registers the missing catalogs. At start (required=False) a failure only logs a warning; the
  REGISTER_CATALOGS action fails with it."""
  import params

  existing = set(c.get("catalogName") for c in (amoro.amoro_api("GET", "/catalogs") or []))
  registrations = []
  if params.has_hive:
    registrations.append((params.amoro_hive_catalog_name, register_hive_catalog))
  if params.has_polaris_ozone_catalog:
    registrations.append((params.polaris_catalog_name, register_polaris_catalog))
  if not registrations:
    Logger.info("No Hive Metastore nor Polaris Ozone catalog in the cluster: no catalog to register.")

  errors = []
  for name, register in registrations:
    if name in existing:
      Logger.info("Amoro catalog '{0}' is already registered.".format(name))
      continue
    try:
      register(name)
      Logger.info("Registered the Amoro catalog '{0}'.".format(name))
    except Fail as error:
      errors.append("{0}: {1}".format(name, error))
      Logger.warning("Could not register the Amoro catalog '{0}': {1}".format(name, error))
  if errors and required:
    raise Fail("Catalog registration failed. " + " ".join(errors))


# ---------------------------------------------------------------------------
# Hive Metastore
# ---------------------------------------------------------------------------

def register_hive_catalog(name):
  import params

  storage = {"storage.type": "Hadoop"}
  for key, path in (("hadoop.core.site", os.path.join(params.hadoop_conf_dir, "core-site.xml")),
                    ("hadoop.hdfs.site", os.path.join(params.hadoop_conf_dir, "hdfs-site.xml")),
                    ("hive.site", os.path.join(params.hive_conf_dir, "hive-site.xml"))):
    if os.path.isfile(path):
      storage[key] = upload_file(path)
    elif key != "hadoop.hdfs.site":
      raise Fail("{0} is missing on this host.".format(path))

  if params.security_enabled:
    auth = {"auth.type": "kerberos",
            "auth.kerberos.principal": params.amoro_principal,
            "auth.kerberos.keytab": upload_file(params.amoro_keytab),
            "auth.kerberos.krb5": upload_file(params.krb5_conf_path)}
  else:
    auth = {"auth.type": "simple", "auth.simple.hadoop_username": params.amoro_user}

  amoro.amoro_api("POST", "/catalogs", catalog_body(name, "hive", storage, auth, {}))


def catalog_body(name, catalog_type, storage, auth, properties):
  import params

  return {"name": name, "type": catalog_type, "optimizerGroup": params.amoro_optimizer_group,
          "storageConfig": storage, "authConfig": auth, "properties": properties,
          "tableProperties": {}, "tableFormatList": ["ICEBERG"]}


def upload_file(path):
  """Uploads a file (client configuration, keytab) to Amoro, which stores it in its database; returns its id."""
  with open(path, "rb") as source:
    content = source.read()
  boundary = uuid.uuid4().hex
  body = b"".join([
    "--{0}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{1}\"\r\n"
    "Content-Type: application/octet-stream\r\n\r\n".format(boundary, os.path.basename(path)).encode("utf-8"),
    content,
    "\r\n--{0}--\r\n".format(boundary).encode("utf-8"),
  ])
  result = amoro.amoro_api("POST", "/files", raw_body=body,
                           content_type="multipart/form-data; boundary={0}".format(boundary))
  if not result or not result.get("id"):
    raise Fail("Amoro did not store {0}: {1}".format(path, result))
  return str(result["id"])


# ---------------------------------------------------------------------------
# Ozone catalog of Polaris
# ---------------------------------------------------------------------------

def register_polaris_catalog(name):
  import params

  if not params.polaris_admin_username or not params.polaris_admin_password:
    raise Fail("The Polaris administrator credentials (polaris-env) are not set.")

  token = polaris_admin_token()
  catalog = polaris_api("GET", "/api/management/v1/catalogs/" + urllib.parse.quote(name, safe=""), token)
  storage_info = catalog.get("storageConfigInfo") or {}
  catalog_properties = catalog.get("properties") or {}
  s3_endpoint = storage_info.get("endpoint") or catalog_properties.get("s3.endpoint", "")
  s3_region = storage_info.get("region") or catalog_properties.get("client.region", "us-east-1")
  if not s3_endpoint:
    raise Fail("The Polaris catalog '{0}' has no S3 endpoint.".format(name))

  properties = polaris_catalog_auth(token)
  grant_polaris_catalog(token, name)
  access_key, secret_key = ozone_s3_credential()

  properties.update({
    "uri": params.polaris_url + "/api/catalog",
    "warehouse": name,
    "header." + params.polaris_realm_header: params.polaris_realm,
    "s3.path-style-access": str(storage_info.get("pathStyleAccess", True)).lower(),
  })
  storage = {"storage.type": "S3", "storage.s3.endpoint": s3_endpoint, "storage.s3.region": s3_region}
  auth = {"auth.type": "ak/sk", "auth.ak_sk.access_key": access_key,
          "auth.ak_sk.secret_key": secret_key}
  amoro.amoro_api("POST", "/catalogs", catalog_body(name, "rest", storage, auth, properties))


def polaris_catalog_auth(token=None):
  """OAuth properties of the Polaris catalog: the OIDC client of Amoro (token endpoint of the OIDC
  provider) when Ambari manages one, else credentials Polaris issues (needs the Polaris admin token)."""
  import params

  if params.amoro_polaris_oidc:
    return {"credential": "{0}:{1}".format(params.amoro_oidc_client_id, params.amoro_oidc_client_secret),
            "oauth2-server-uri": oidc_token_endpoint(), "scope": "openid"}
  client_id, client_secret = polaris_principal_credentials(token or polaris_admin_token())
  return {"credential": "{0}:{1}".format(client_id, client_secret),
          "oauth2-server-uri": params.polaris_url + "/api/catalog/v1/oauth/tokens", "scope": "PRINCIPAL_ROLE:ALL"}


def oidc_token_endpoint():
  """Token endpoint of the cluster OIDC provider, from its discovery document."""
  import params

  url = params.amoro_oidc_auth_server_url + "/.well-known/openid-configuration"
  context = ssl._create_unverified_context() if url.startswith("https://") else None
  try:
    document = json.loads(urllib.request.urlopen(url, timeout=30, context=context).read().decode("utf-8"))
  except (urllib.error.URLError, OSError, ValueError) as error:
    raise Fail("Cannot read the OIDC discovery document {0}: {1}".format(url, error))
  if not document.get("token_endpoint"):
    raise Fail("The OIDC discovery document {0} has no token_endpoint.".format(url))
  return document["token_endpoint"]


def polaris_api(method, path, token=None, body=None, form=None, ok=(200, 201, 204)):
  """Calls Polaris; returns (decoded body) or raises Fail. A status outside ok raises, with the code
  in the message."""
  import params

  headers = {params.polaris_realm_header: params.polaris_realm, "Accept": "application/json"}
  data = None
  if token:
    headers["Authorization"] = "Bearer " + token
  if form is not None:
    data = urllib.parse.urlencode(form).encode("utf-8")
    headers["Content-Type"] = "application/x-www-form-urlencoded"
  elif body is not None:
    data = json.dumps(body).encode("utf-8")
    headers["Content-Type"] = "application/json"
  request = urllib.request.Request(params.polaris_url + path, data=data, method=method, headers=headers)
  context = ssl._create_unverified_context() if params.polaris_url.startswith("https://") else None
  try:
    response = urllib.request.urlopen(request, timeout=30, context=context)
    status, raw = response.getcode(), response.read().decode("utf-8")
  except urllib.error.HTTPError as error:
    status, raw = error.code, error.read().decode("utf-8", "replace")
  except (urllib.error.URLError, OSError) as error:
    raise Fail("Cannot reach Polaris at {0}: {1}".format(params.polaris_url, error))
  if status not in ok:
    raise PolarisError(status, "Polaris {0} {1}: HTTP {2} {3}".format(method, path, status, raw[:300]))
  return json.loads(raw) if raw.strip() else {}


class PolarisError(Fail):
  def __init__(self, status, message):
    super(PolarisError, self).__init__(message)
    self.status = status


def polaris_admin_token():
  import params

  result = polaris_api("POST", "/api/catalog/v1/oauth/tokens", form={
    "grant_type": "client_credentials", "client_id": params.polaris_admin_username,
    "client_secret": params.polaris_admin_password, "scope": "PRINCIPAL_ROLE:ALL"})
  if not result.get("access_token"):
    raise Fail("Polaris returned no access token for its administrator.")
  return result["access_token"]


def polaris_principal_credentials(token):
  """Client id and secret of the Polaris principal of Amoro: created, or its credentials rotated when it
  exists (Polaris returns a secret only then; the Amoro catalog being registered is its only user)."""
  import params

  principal = params.amoro_polaris_principal
  try:
    result = polaris_api("POST", "/api/management/v1/principals", token, {"principal": {"name": principal}})
    Logger.info("Created the Polaris principal '{0}'.".format(principal))
  except PolarisError as error:
    if error.status != 409:
      raise
    result = polaris_api("POST", "/api/management/v1/principals/{0}/rotate".format(urllib.parse.quote(principal)), token)
    Logger.info("Rotated the credentials of the Polaris principal '{0}'.".format(principal))
  credentials = result.get("credentials") or {}
  if not credentials.get("clientId") or not credentials.get("clientSecret"):
    raise Fail("Polaris returned no credentials for the principal '{0}'.".format(principal))
  return credentials["clientId"], credentials["clientSecret"]


def grant_polaris_catalog(token, catalog):
  """Gives the Polaris principal of Amoro the content of the catalog: Ranger policies when Polaris
  delegates its authorization to Ranger, CATALOG_MANAGE_CONTENT through a catalog role otherwise."""
  import params

  principal = params.amoro_polaris_principal
  if params.polaris_authorization_type == "ranger":
    for level, wildcards, accesses in POLARIS_RANGER_LEVELS:
      resources = {"root": ["*"], "catalog": [catalog]}
      resources.update((key, ["*"]) for key in wildcards)
      ensure_ranger_policy(params.polaris_ranger_service, "amoro-polaris-{0}-{1}".format(catalog, level),
                           resources, principal, accesses)
    return

  role = principal
  quoted_catalog, quoted_role = urllib.parse.quote(catalog, safe=""), urllib.parse.quote(role, safe="")
  for method, path, body in (
      ("POST", "/api/management/v1/principal-roles", {"principalRole": {"name": role}}),
      ("PUT", "/api/management/v1/principals/{0}/principal-roles".format(urllib.parse.quote(principal, safe="")),
       {"principalRole": {"name": role}}),
      ("POST", "/api/management/v1/catalogs/{0}/catalog-roles".format(quoted_catalog), {"catalogRole": {"name": role}}),
      ("PUT", "/api/management/v1/catalogs/{0}/catalog-roles/{1}/grants".format(quoted_catalog, quoted_role),
       {"grant": {"type": "catalog", "privilege": "CATALOG_MANAGE_CONTENT"}}),
      ("PUT", "/api/management/v1/principal-roles/{0}/catalog-roles/{1}".format(quoted_role, quoted_catalog),
       {"catalogRole": {"name": role}})):
    polaris_api(method, path, token, body, ok=(200, 201, 204, 409))
  Logger.info("Granted CATALOG_MANAGE_CONTENT on the Polaris catalog '{0}' to '{1}'.".format(catalog, principal))


def ozone_s3_credential():
  """(access key, secret) of the Amoro user on the Ozone S3 gateway. The secret is generated and set on
  Ozone here, then kept by the Amoro catalog only: registering the catalog again replaces it."""
  import params

  secret = secrets.token_urlsafe(30)
  if not params.ozone_security_enabled:
    # Without Ozone security the S3 gateway checks no signature: the access key names the user.
    return params.amoro_user, secret
  # getsecret creates the S3 identity of the principal (and prints its first secret, hence quiet).
  output = run_ozone(["s3", "getsecret", "-e"], secret=True)
  match = re.search(r"AWS_ACCESS_KEY_ID=['\"]?([^'\"\s]+)", output) or re.search(r"awsAccessKey\s*[:=]\s*(\S+)", output)
  access_key = match.group(1) if match else params.amoro_principal
  run_ozone(["s3", "setsecret", "-s", secret, "-e"], secret=True)
  Logger.info("Set a new Ozone S3 secret for '{0}'.".format(access_key))
  return access_key, secret


def run_ozone(args, secret=False):
  """Runs the ozone shell as the Amoro user, with its Kerberos identity."""
  import params

  command = [params.ozone_cmd, "--config", params.hadoop_conf_dir] + list(args)
  if params.ozone_om_service_id:
    command += ["--om-service-id", params.ozone_om_service_id]
  shell = "{0} -kt {1} {2} && {3}".format(
    shlex.quote(params.kinit_path_local), shlex.quote(params.amoro_keytab), shlex.quote(params.amoro_principal),
    " ".join(shlex.quote(part) for part in command))
  code, out, err = get_user_call_output(shell, user=params.amoro_user, is_checked_call=False, quiet=secret)
  if code != 0:
    raise Fail("ozone {0} failed: {1}".format(args[1], (err or out or "").strip()[:300]))
  return "\n".join(filter(None, [out, err]))


# ---------------------------------------------------------------------------
# Ranger
# ---------------------------------------------------------------------------

def ranger_api(method, path, body=None):
  import params

  if not params.ranger_admin_url or not params.ranger_admin_username or not params.ranger_admin_password:
    raise Fail("The Ranger admin URL or credentials (admin-properties, ranger-env) are not set.")
  headers = {"Accept": "application/json", "Authorization": "Basic " + base64.b64encode("{0}:{1}".format(
    params.ranger_admin_username, params.ranger_admin_password).encode("utf-8")).decode("utf-8")}
  data = None
  if body is not None:
    data = json.dumps(body).encode("utf-8")
    headers["Content-Type"] = "application/json"
  request = urllib.request.Request(params.ranger_admin_url + path, data=data, method=method, headers=headers)
  context = ssl._create_unverified_context() if params.ranger_admin_url.startswith("https://") else None
  try:
    response = urllib.request.urlopen(request, timeout=30, context=context)
    status, raw = response.getcode(), response.read().decode("utf-8")
  except urllib.error.HTTPError as error:
    status, raw = error.code, error.read().decode("utf-8", "replace")
  except (urllib.error.URLError, OSError) as error:
    raise Fail("Cannot reach Ranger at {0}: {1}".format(params.ranger_admin_url, error))
  try:
    return status, json.loads(raw) if raw.strip() else {}
  except ValueError:
    return status, {"raw": raw}


def ensure_ranger_user(name):
  """Ranger rejects policies naming unknown users: the Amoro user is created when missing."""
  status, result = ranger_api("GET", "/service/xusers/users?name=" + urllib.parse.quote(name))
  if status == 200 and any(user.get("name") == name for user in result.get("vXUsers", [])):
    return
  status, result = ranger_api("POST", "/service/xusers/secure/users", {
    "name": name, "firstName": name, "lastName": "(Amoro)", "emailAddress": "", "status": 1,
    "userRoleList": ["ROLE_USER"], "password": uuid.uuid4().hex + "Aa1!",
    "description": "Service user of Amoro, provisioned by Ambari"})
  if status // 100 != 2 and "already exists" not in json.dumps(result).lower():
    raise Fail("Could not create the Ranger user '{0}': HTTP {1} {2}".format(name, status, result))
  Logger.info("Created the Ranger user '{0}'.".format(name))


POLICY_LABEL = "ambari-amoro"


def ensure_ranger_policy(service, name, resources, user, accesses, recursive=()):
  """Grants accesses on resources to user: in the policy name, or in the policy that already owns the
  same resources (Ranger refuses two policies on the same resources). Idempotent. The resources named
  in recursive match their descendants (HDFS paths)."""
  ensure_ranger_user(user)
  status, found = ranger_api("GET", "/service/public/v2/api/service/{0}/policy?policyName={1}".format(
    urllib.parse.quote(service, safe=""), urllib.parse.quote(name, safe="")))
  policy = found[0] if status == 200 and isinstance(found, list) and found else None
  if policy is None:
    body = {"service": service, "name": name, "isEnabled": True, "isAuditEnabled": True,
            "description": "Access of the Amoro service user, managed by Ambari",
            "policyLabels": [POLICY_LABEL],
            "resources": {key: {"values": values, "isExcludes": False, "isRecursive": key in recursive}
                          for key, values in resources.items()},
            "policyItems": [policy_item(user, accesses)]}
    status, result = ranger_api("POST", "/service/public/v2/api/policy", body)
    if status // 100 == 2:
      Logger.info("Created the Ranger policy '{0}' on {1}.".format(name, service))
      return
    owner = re.search(r"policy-name=\[(.*?)\]", json.dumps(result))
    if status != 400 or not owner:
      raise Fail("Could not create the Ranger policy '{0}': HTTP {1} {2}".format(name, status, result))
    status, found = ranger_api("GET", "/service/public/v2/api/service/{0}/policy?policyName={1}".format(
      urllib.parse.quote(service, safe=""), urllib.parse.quote(owner.group(1), safe="")))
    if status != 200 or not found:
      raise Fail("Could not read the Ranger policy '{0}' owning the resources of '{1}'.".format(owner.group(1), name))
    policy = found[0]

  items = policy.setdefault("policyItems", [])
  granted = set()
  for item in items:
    if user in item.get("users", []):
      granted.update(access.get("type") for access in item.get("accesses", []) if access.get("isAllowed", True))
  if "all" in granted or set(accesses) <= granted:
    return
  items.append(policy_item(user, accesses))
  status, result = ranger_api("PUT", "/service/public/v2/api/policy/{0}".format(policy["id"]), policy)
  if status // 100 != 2:
    raise Fail("Could not update the Ranger policy '{0}': HTTP {1} {2}".format(policy.get("name"), status, result))
  Logger.info("Granted '{0}' {1} in the Ranger policy '{2}'.".format(user, ",".join(accesses), policy.get("name")))


def policy_item(user, accesses):
  return {"users": [user], "groups": [], "delegateAdmin": False,
          "accesses": [{"type": access, "isAllowed": True} for access in accesses]}
