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
from resource_management.core.logger import Logger
from resource_management.core.resources import File
from resource_management.core.exceptions import Fail
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.setup_ranger_plugin_xml import setup_configuration_file_for_required_plugins
from resource_management.libraries.functions import ranger_functions_v2
from resource_management.libraries.functions.get_stack_version import get_stack_version
from resource_management.libraries.resources.xml_config import XmlConfig
from resource_management.libraries.script.script import Script
import base64
import copy
import json
import re
import urllib.error
import urllib.parse
import urllib.request


def _prepare_ranger_audit_runtime_config():
  """
  Normalize Ranger audit settings before the plugin XML files are rendered.

  This keeps runtime behavior deterministic even if existing cluster configs
  still contain legacy placeholders or incomplete Kerberos audit properties.
  """
  import params

  if not params.enable_ranger_polaris:
    return None

  # Ambari config dictionaries are immutable wrappers. Work on a plain mutable
  # copy and pass it explicitly to setup_ranger_plugin().
  audit_cfg = dict(params.ranger_polaris_audit)
  _normalize_hdfs_audit_dir(audit_cfg, params.config)
  _ensure_solr_kerberos_audit_settings(audit_cfg, params.security_enabled, params.polaris_jaas_principal, params.polaris_keytab_path)
  return audit_cfg


def _normalize_hdfs_audit_dir(audit_cfg, full_config):
  hdfs_enabled = str(audit_cfg.get("xasecure.audit.destination.hdfs", "false")).strip().lower() == "true"
  if not hdfs_enabled:
    return

  core_site = full_config.get("configurations", {}).get("core-site", {})
  hdfs_site = full_config.get("configurations", {}).get("hdfs-site", {})
  default_fs = str(core_site.get("fs.defaultFS", "")).strip()
  current_dir = str(audit_cfg.get("xasecure.audit.destination.hdfs.dir", "")).strip()

  if not default_fs.startswith("hdfs://"):
    Logger.warning(
      "Polaris Ranger audit: fs.defaultFS is empty or non-HDFS ({0}); keeping configured HDFS audit dir '{1}'.".format(
        default_fs, current_dir
      )
    )
    return

  normalized_default = "{0}/ranger/audit".format(default_fs.rstrip("/"))
  should_update = False

  if not current_dir or "NAMENODE_HOSTNAME" in current_dir or not current_dir.startswith("hdfs://"):
    should_update = True
  else:
    parsed_dir = urllib.parse.urlparse(current_dir)
    current_host = parsed_dir.hostname or ""
    nameservices = [
      ns.strip() for ns in str(hdfs_site.get("dfs.nameservices", "")).split(",") if ns.strip()
    ]

    # In HA/logical nameservice mode, host:port form (e.g. hdfs://ns:8020)
    # gets treated as a physical host and can trigger UnknownHostException.
    if parsed_dir.port and current_host and current_host in nameservices:
      should_update = True

  if should_update and current_dir != normalized_default:
    audit_cfg["xasecure.audit.destination.hdfs.dir"] = normalized_default
    Logger.info(
      "Polaris Ranger audit: normalized xasecure.audit.destination.hdfs.dir from '{0}' to '{1}'.".format(
        current_dir, normalized_default
      )
    )


def _ensure_solr_kerberos_audit_settings(audit_cfg, security_enabled, principal, keytab):
  if not security_enabled:
    return

  desired_static = {
    "xasecure.audit.jaas.Client.loginModuleName": "com.sun.security.auth.module.Krb5LoginModule",
    "xasecure.audit.jaas.Client.loginModuleControlFlag": "required",
    "xasecure.audit.jaas.Client.option.useKeyTab": "true",
    "xasecure.audit.jaas.Client.option.storeKey": "false",
    "xasecure.audit.jaas.Client.option.serviceName": "solr",
    "xasecure.audit.destination.solr.force.use.inmemory.jaas.config": "true",
  }
  for key, value in desired_static.items():
    if str(audit_cfg.get(key, "")).strip() != value:
      audit_cfg[key] = value

  principal = str(principal or "").strip()
  keytab = str(keytab or "").strip()
  if principal and not str(audit_cfg.get("xasecure.audit.jaas.Client.option.principal", "")).strip():
    audit_cfg["xasecure.audit.jaas.Client.option.principal"] = principal
  if keytab and not str(audit_cfg.get("xasecure.audit.jaas.Client.option.keyTab", "")).strip():
    audit_cfg["xasecure.audit.jaas.Client.option.keyTab"] = keytab


def setup_ranger_polaris():
  import params

  if not params.enable_ranger_polaris:
    Logger.info('Ranger Polaris plugin is not enabled')
    return

  from resource_management.libraries.functions.setup_ranger_plugin_xml import setup_ranger_plugin

  if params.retry_enabled:
    Logger.info("Polaris: Ranger setup will retry if Ranger Admin is temporarily unavailable.")
  else:
    Logger.info("Polaris: Ranger setup will skip if Ranger Admin is unavailable.")

  effective_ranger_polaris_audit = _prepare_ranger_audit_runtime_config()
  if effective_ranger_polaris_audit is None:
    effective_ranger_polaris_audit = params.ranger_polaris_audit

  if params.has_namenode and params.xa_audit_hdfs_is_enabled:
    try:
      params.HdfsResource("/ranger/audit",
                          type="directory",
                          action="create_on_execute",
                          owner=params.polaris_user,
                          group=params.user_group,
                          mode=0o755,
                          recursive_chmod=True)
      params.HdfsResource("/ranger/audit/polaris",
                          type="directory",
                          action="create_on_execute",
                          owner=params.polaris_user,
                          group=params.user_group,
                          mode=0o700,
                          recursive_chmod=True)
      params.HdfsResource(None, action="execute")

      if params.is_ranger_kms_ssl_enabled:
        Logger.info("Ranger KMS SSL is enabled; creating ssl-client.xml for Ranger audits.")
        setup_configuration_file_for_required_plugins(
          component_user=params.polaris_user,
          component_group=params.user_group,
          create_core_site_path=params.polaris_conf_dir,
          configurations=params.config['configurations']['ssl-client'],
          configuration_attributes=params.config['configurationAttributes']['ssl-client'],
          file_name='ssl-client.xml')
    except Exception as err:
      Logger.exception("Audit directory creation in HDFS for Polaris Ranger plugin failed with error:\n{0}".format(err))

  if not params.has_namenode:
    Logger.info("Polaris Ranger setup: no namenode detected; skipping core-site.xml and hdfs-site.xml.")
  else:
    Logger.info("Polaris Ranger setup: namenode detected; writing core-site.xml and hdfs-site.xml to Polaris conf dir.")
    setup_configuration_file_for_required_plugins(
      component_user=params.polaris_user,
      component_group=params.user_group,
      create_core_site_path=params.polaris_conf_dir,
      configurations=params.config['configurations']['core-site'],
      configuration_attributes=params.config['configurationAttributes']['core-site'],
      file_name='core-site.xml')


    XmlConfig("hdfs-site.xml",
              conf_dir=params.polaris_conf_dir,
              configurations=params.config['configurations']['hdfs-site'],
              configuration_attributes=params.config['configurationAttributes']['hdfs-site'],
              owner=params.polaris_user,
              group=params.user_group,
              mode=0o644)

  # Extend the credential-helper classpath to include Hadoop's common lib so that
  # commons-collections4 (required by hadoop-common's Configuration class since Hadoop 3.3)
  # is available when org.apache.ranger.credentialapi.buildks creates the JCEKS file.
  # This mirrors the pattern used by the KNOX Ranger plugin setup.
  stack_root = Script.get_stack_root()
  stack_version = get_stack_version('polaris-server')
  cred_lib_path_override = (
    '{root}/{ver}/ranger-polaris-plugin/install/lib/*'
    ':{root}/{ver}/hadoop/share/hadoop/common/*'
    ':{root}/{ver}/hadoop/share/hadoop/common/lib/*'
  ).format(root=stack_root, ver=stack_version)


  Logger.info("Creating Polaris Catalog Default Admin Realm User: " + params.polaris_admin_username)
  ranger_admin_v2_obj = ranger_functions_v2.RangeradminV2(url = params.policymgr_mgr_url, skip_if_rangeradmin_down = False)
  ranger_admin_v2_obj.create_policy_user(params.ranger_admin_username, params.ranger_admin_password, params.polaris_admin_username)

  setup_ranger_plugin('polaris-server', 'polaris', params.previous_jdbc_jar,
                      params.downloaded_custom_connector, params.driver_curl_source,
                      params.driver_curl_target, params.java64_home,
                      params.repo_name, params.polaris_ranger_plugin_repo,
                      params.ranger_env, params.ranger_plugin_properties,
                      params.policy_user, params.policymgr_mgr_url,
                      params.enable_ranger_polaris, conf_dict=params.polaris_conf_dir,
                      component_user=params.polaris_user, component_group=params.user_group,
                      cache_service_list=['polaris'],
                      plugin_audit_properties=effective_ranger_polaris_audit, plugin_audit_attributes=params.ranger_polaris_audit_attrs,
                      plugin_security_properties=params.ranger_polaris_security, plugin_security_attributes=params.ranger_polaris_security_attrs,
                      plugin_policymgr_ssl_properties=params.ranger_polaris_policymgr_ssl, plugin_policymgr_ssl_attributes=params.ranger_polaris_policymgr_ssl_attrs,
                      component_list=['polaris-server'], audit_db_is_enabled=False,
                      credential_file=params.credential_file, xa_audit_db_password=None,
                      ssl_truststore_password=params.ssl_truststore_password, ssl_keystore_password=params.ssl_keystore_password,
                      policy_config_dict=None,
                      api_version='v2', skip_if_rangeradmin_down=not params.retry_enabled,
                      is_security_enabled=params.security_enabled,
                      is_stack_supports_ranger_kerberos=params.stack_supports_ranger_kerberos,
                      component_user_principal=params.polaris_jaas_principal if params.security_enabled else None,
                      component_user_keytab=params.polaris_keytab_path if params.security_enabled else None,
                      cred_lib_path_override=cred_lib_path_override)

  _ensure_polaris_admin_root_policy(ranger_admin_v2_obj)


def _ensure_polaris_admin_root_policy(ranger_admin_v2_obj):
  import params

  desired_policy = params.ranger_policy_config if params.ranger_policy_config else None
  if not desired_policy:
    Logger.info("No Polaris Ranger policy template configured; skipping Polaris admin root policy ensure step.")
    return

  service_name = str(desired_policy.get("service", "")).strip()
  desired_policy_name = str(desired_policy.get("name", "")).strip()
  policy_items = desired_policy.get("policyItems") or []
  if not service_name or not desired_policy_name or not policy_items:
    Logger.warning(
      "Polaris Ranger policy template is incomplete (service={0}, name={1}); skipping admin policy ensure.".format(
        service_name, desired_policy_name
      )
    )
    return

  required_item = copy.deepcopy(policy_items[0])
  admin_principal = str(params.polaris_admin_username).strip()
  if not admin_principal:
    Logger.warning("Polaris admin principal is empty; skipping Ranger root policy ensure.")
    return
  required_item["users"] = [admin_principal]

  ranger_admin_user = str(params.ranger_admin_username).strip()
  ranger_admin_password = str(params.ranger_admin_password)
  if not ranger_admin_user or not ranger_admin_password:
    Logger.warning("Ranger admin credentials are empty; skipping Polaris Ranger root policy ensure.")
    return

  Logger.info(
    "Ensuring Polaris Ranger root policy for admin principal '{0}' (service={1}, policy={2}).".format(
      admin_principal, service_name, desired_policy_name
    )
  )
  Logger.info(
    "Polaris identities: service user='{0}', admin principal='{1}'.".format(
      params.polaris_user, admin_principal
    )
  )

  existing_policy = _ranger_get_policy_by_name(
    ranger_admin_v2_obj, service_name, desired_policy_name, ranger_admin_user, ranger_admin_password
  )
  if not existing_policy:
    existing_policy = _ranger_get_policy_by_name(
      ranger_admin_v2_obj, service_name, "all - root", ranger_admin_user, ranger_admin_password
    )

  if existing_policy:
    updated_policy, changed = _merge_admin_policy_item(existing_policy, required_item, admin_principal)
    if changed:
      _ranger_update_policy(ranger_admin_v2_obj, updated_policy, ranger_admin_user, ranger_admin_password)
      Logger.info(
        "Updated existing Polaris Ranger policy '{0}' to include/refresh admin principal '{1}'.".format(
          updated_policy.get("name"), admin_principal
        )
      )
    else:
      Logger.info(
        "Existing Polaris Ranger policy '{0}' already grants required admin permissions to '{1}'.".format(
          existing_policy.get("name"), admin_principal
        )
      )
    return

  create_status, create_response = _ranger_create_policy(
    ranger_admin_v2_obj, desired_policy, ranger_admin_user, ranger_admin_password
  )
  if create_status in (200, 201):
    Logger.info("Created Polaris Ranger root policy '{0}'.".format(desired_policy_name))
    return

  response_text = _to_text(create_response)
  if create_status == 400 and "Another policy already exists for matching resource" in response_text:
    conflict_policy_name = _extract_conflict_policy_name(response_text)
    if conflict_policy_name:
      Logger.info(
        "Polaris Ranger policy create conflicted with existing policy '{0}'; merging admin permissions.".format(
          conflict_policy_name
        )
      )
      conflict_policy = _ranger_get_policy_by_name(
        ranger_admin_v2_obj, service_name, conflict_policy_name, ranger_admin_user, ranger_admin_password
      )
      if conflict_policy:
        updated_policy, changed = _merge_admin_policy_item(conflict_policy, required_item, admin_principal)
        if changed:
          _ranger_update_policy(ranger_admin_v2_obj, updated_policy, ranger_admin_user, ranger_admin_password)
          Logger.info(
            "Updated conflicting Polaris Ranger policy '{0}' for admin principal '{1}'.".format(
              conflict_policy_name, admin_principal
            )
          )
        else:
          Logger.info(
            "Conflicting Polaris Ranger policy '{0}' already includes required admin permissions.".format(
              conflict_policy_name
            )
          )
        return

  raise Fail(
    "Unable to ensure Polaris Ranger root policy. HTTP status={0}, response={1}".format(
      create_status, response_text
    )
  )


def _ranger_get_policy_by_name(ranger_admin_v2_obj, service_name, policy_name, username, password):
  url = ranger_admin_v2_obj.url_policies_get.format(servicename=service_name) + \
        "?policyName=" + urllib.parse.quote(policy_name, safe="")
  status_code, body = _ranger_http(url, username, password, method="GET")
  if status_code != 200:
    Logger.warning(
      "Failed to query Ranger policy '{0}' for service '{1}'. HTTP status={2}".format(
        policy_name, service_name, status_code
      )
    )
    return None
  if isinstance(body, list) and body:
    return body[0]
  return None


def _ranger_create_policy(ranger_admin_v2_obj, policy_doc, username, password):
  return _ranger_http(
    ranger_admin_v2_obj.url_policies,
    username,
    password,
    method="POST",
    payload=policy_doc
  )


def _ranger_update_policy(ranger_admin_v2_obj, policy_doc, username, password):
  policy_id = policy_doc.get("id")
  if policy_id is None:
    raise Fail("Cannot update Ranger policy without id.")
  status_code, body = _ranger_http(
    ranger_admin_v2_obj.url_policies + "/" + str(policy_id),
    username,
    password,
    method="PUT",
    payload=policy_doc
  )
  if status_code not in (200, 201):
    raise Fail(
      "Failed to update Ranger policy id={0}. HTTP status={1}, response={2}".format(
        policy_id, status_code, _to_text(body)
      )
    )
  return body


def _merge_admin_policy_item(policy_doc, required_item, admin_principal):
  changed = False
  policy_items = list(policy_doc.get("policyItems") or [])

  target_item = None
  for item in policy_items:
    users = item.get("users") or []
    if admin_principal in users:
      target_item = item
      break

  if target_item is None:
    target_item = copy.deepcopy(required_item)
    target_item["users"] = [admin_principal]
    policy_items.append(target_item)
    changed = True
  else:
    users = target_item.get("users") or []
    if admin_principal not in users:
      users.append(admin_principal)
      target_item["users"] = users
      changed = True

  target_accesses = list(target_item.get("accesses") or [])
  access_by_type = {}
  for access in target_accesses:
    access_type = access.get("type")
    if access_type:
      access_by_type[access_type] = access

  for required_access in list(required_item.get("accesses") or []):
    access_type = required_access.get("type")
    if not access_type:
      continue
    existing = access_by_type.get(access_type)
    if existing is None:
      target_accesses.append(copy.deepcopy(required_access))
      changed = True
    elif _as_bool(required_access.get("isAllowed")) and not _as_bool(existing.get("isAllowed")):
      existing["isAllowed"] = True
      changed = True
  target_item["accesses"] = target_accesses

  if _as_bool(required_item.get("delegateAdmin")) and not _as_bool(target_item.get("delegateAdmin")):
    target_item["delegateAdmin"] = True
    changed = True

  if changed:
    policy_doc["policyItems"] = policy_items
  return policy_doc, changed


def _extract_conflict_policy_name(response_text):
  match = re.search(r"policy-name=\[([^\]]+)\]", response_text or "")
  if match:
    return match.group(1).strip()
  return None


def _ranger_http(url, username, password, method="GET", payload=None, timeout=20):
  headers = {
    "Accept": "application/json",
    "Content-Type": "application/json",
  }
  data = None
  if payload is not None:
    data = json.dumps(payload).encode("utf-8")

  req = urllib.request.Request(url, data=data, headers=headers)
  if method != "GET":
    req.get_method = lambda: method

  basic = base64.b64encode("{0}:{1}".format(username, password).encode()).decode().replace("\n", "")
  req.add_header("Authorization", "Basic {0}".format(basic))

  try:
    response = urllib.request.urlopen(req, timeout=timeout)
    raw = response.read().decode("utf-8")
    if not raw:
      return response.getcode(), {}
    try:
      return response.getcode(), json.loads(raw)
    except Exception:
      return response.getcode(), raw
  except urllib.error.HTTPError as http_err:
    raw = http_err.read().decode("utf-8")
    try:
      parsed = json.loads(raw) if raw else {}
    except Exception:
      parsed = raw
    return http_err.code, parsed


def _as_bool(value):
  return str(value).strip().lower() == "true"


def _to_text(value):
  if isinstance(value, str):
    return value
  try:
    return json.dumps(value)
  except Exception:
    return str(value)
