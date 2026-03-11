#!/usr/bin/env ambari-python-wrap
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

# Ambari Commons & Resource Management Imports
import base64
import ambari_simplejson as json

try:
    import urllib.request as urllib_request
except ImportError:
    import urllib2 as urllib_request

from resource_management.core.logger import Logger
from resource_management.core.exceptions import Fail
from resource_management.libraries.functions.ranger_functions_v2 import RangeradminV2
from resource_management.libraries.functions.setup_ranger_plugin_xml import setup_ranger_plugin

def _csv_users(value):
    if value is None:
        return []
    return [entry.strip() for entry in str(value).split(',') if entry and entry.strip()]


def _merge_required_users(existing_csv, required_users):
    merged = _csv_users(existing_csv)
    seen = set([entry.lower() for entry in merged])
    changed = False
    for user in required_users:
        normalized = str(user).strip() if user is not None else ""
        if not normalized:
            continue
        if normalized.lower() not in seen:
            merged.append(normalized)
            seen.add(normalized.lower())
            changed = True
    return ",".join(merged), changed


def _parse_repo_payload(payload):
    if payload is None:
        return None

    if isinstance(payload, bytes):
        payload = payload.decode('utf-8')

    text = str(payload).strip()
    if not text:
        return None

    for candidate in (text, text[1:-1] if len(text) > 2 else text):
        try:
            parsed = json.loads(candidate)
        except Exception:
            continue
        if isinstance(parsed, list):
            return parsed[0] if len(parsed) > 0 else None
        if isinstance(parsed, dict):
            return parsed

    return None


def _get_repo_details_by_id_urllib2(ranger_admin, params, repo_id):
    detail_url = "{0}/service/plugins/services/{1}".format(ranger_admin.base_url, repo_id)
    usernamepassword = "{0}:{1}".format(
        params.ranger_env['ranger_admin_username'],
        str(params.ranger_env['ranger_admin_password'])
    )
    request = urllib_request.Request(detail_url)
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/json")
    base_64_string = base64.b64encode(usernamepassword.encode()).decode().replace('\n', '')
    request.add_header("Authorization", "Basic {0}".format(base_64_string))
    result = urllib_request.urlopen(request, timeout=20)
    return _parse_repo_payload(result.read())


def _get_repo_details_by_id_curl(ranger_admin, params, repo_id):
    detail_url = "{0}/service/plugins/services/{1}".format(ranger_admin.base_url, repo_id)
    response, _, _ = ranger_admin.call_curl_request(
        params.impala_user,
        params.impala_keytab,
        params.impala_principal,
        detail_url,
        False,
        request_method='GET'
    )
    return _parse_repo_payload(response)


def _ensure_repo_with_configs(ranger_admin, params, repo, use_kerberos_api):
    repo_id = None
    if isinstance(repo, dict):
        repo_id = repo.get('id')

    if repo_id is None or not str(repo_id).strip():
        Logger.warning(
            "Impala Ranger reconciliation skipped: repository '{0}' has no id in lookup response.".format(
                params.repo_name
            )
        )
        return repo

    Logger.info(
        "Fetching detailed Ranger repository '{0}' using '/service/plugins/services/{1}'.".format(
            params.repo_name, repo_id
        )
    )

    try:
        detailed_repo = _get_repo_details_by_id_urllib2(ranger_admin, params, repo_id)
    except Exception as err:
        Logger.warning(
            "Failed to fetch detailed Ranger repository '{0}' by id '{1}': {2}".format(
                params.repo_name, repo_id, err
            )
        )
        return repo

    if isinstance(detailed_repo, dict):
        return detailed_repo
    return repo


def _reconcile_hive_repo_auth_users(params):
    if not params.security_enabled:
        return

    required_users = [params.hive_user, params.impala_user]
    auth_user_keys = [
        'policy.download.auth.users',
        'tag.download.auth.users',
        'policy.grantrevoke.auth.users',
    ]

    ranger_admin = RangeradminV2(
        url=params.policymgr_mgr_url,
        skip_if_rangeradmin_down=not params.retryAble
    )

    use_kerberos_api = params.stack_supports_ranger_kerberos and params.security_enabled
    if use_kerberos_api:
        repo = ranger_admin.get_repository_by_name_curl(
            params.impala_user,
            params.impala_keytab,
            params.impala_principal,
            params.repo_name,
            'hive',
            'true'
        )
    else:
        admin_username = params.ranger_env['ranger_admin_username']
        admin_password = str(params.ranger_env['ranger_admin_password'])
        repo = ranger_admin.get_repository_by_name_urllib2(
            params.repo_name,
            'hive',
            'true',
            "{0}:{1}".format(admin_username, admin_password)
        )

    if not repo:
        Logger.warning(
            "Impala Ranger reconciliation skipped: Hive repository '{0}' was not found.".format(
                params.repo_name
            )
        )
        return

    repo = _ensure_repo_with_configs(ranger_admin, params, repo, use_kerberos_api)
    configs = repo.get('configs')
    if not isinstance(configs, dict):
        Logger.warning(
            "Impala Ranger reconciliation skipped: repository '{0}' has no 'configs' map.".format(
                params.repo_name
            )
        )
        return

    changed = False
    for key in auth_user_keys:
        merged_csv, merged_changed = _merge_required_users(configs.get(key, ""), required_users)
        if key not in configs or merged_changed:
            configs[key] = merged_csv
            changed = True

    # Ranger API responses often mask repository passwords as *****.
    # Ensure we send a valid credential value back on update.
    desired_password = params.hive_ranger_plugin_repo.get('configs', {}).get('password')
    if desired_password and configs.get('password') != desired_password:
        configs['password'] = desired_password
        changed = True

    if not changed:
        Logger.info(
            "Impala user '{0}' is already present in Hive Ranger repository auth user lists.".format(
                params.impala_user
            )
        )
        return

    repo['configs'] = configs
    ranger_admin.update_repository_urllib2(
        'hive',
        params.repo_name,
        repo.decode('utf-8') if isinstance(repo, bytes) else repo,
        params.ranger_env['admin_username'],
        params.ranger_env['admin_password']
    )


    Logger.info(
        "Updated Hive Ranger repository '{0}' to include Impala user '{1}' in policy/tag/grantrevoke auth users.".format(
            params.repo_name, params.impala_user
        )
    )


def setup_ranger_impala(upgrade_type = None):
    import params

    if params.enable_ranger and params.enable_ranger_hive:
        stack_version = None

        if upgrade_type is not None:
            stack_version = params.version

        if params.retryAble:
            Logger.info("Hive: Setup ranger: command retry enables thus retrying if ranger admin is down !")
        else:
            Logger.info("Hive: Setup ranger: command retry not enabled thus skipping if ranger admin is down !")

        if params.xa_audit_hdfs_is_enabled:
            try:
                params.HdfsResource("/ranger/audit",
                                    type="directory",
                                    action="create_on_execute",
                                    owner=params.hdfs_user,
                                    group=params.hdfs_user,
                                    mode=0o755,
                                    recursive_chmod=True
                                    )
                params.HdfsResource("/ranger/audit/impala",
                                    type="directory",
                                    action="create_on_execute",
                                    owner=params.impala_user,
                                    group=params.impala_group,
                                    mode=0o700,
                                    recursive_chmod=True
                                    )
                params.HdfsResource(None, action="execute")
            except Exception as err:
                Logger.exception("Audit directory creation in HDFS for HIVE Ranger plugin failed with error:\n{0}".format(err))

        api_version='v2'

        setup_ranger_plugin('hive-server2', 'hive', params.ranger_previous_jdbc_jar,
                            params.ranger_downloaded_custom_connector, params.ranger_driver_curl_source,
                            params.ranger_driver_curl_target, params.java64_home,
                            params.repo_name, params.hive_ranger_plugin_repo,
                            params.ranger_env, params.ranger_plugin_properties,
                            params.policy_user, params.policymgr_mgr_url,
                            params.enable_ranger, conf_dict=params.impala_conf_dir,
                            component_user=params.impala_user, component_group=params.impala_group, cache_service_list=params.cache_service_list,
                            plugin_audit_properties=params.config['configurations']['ranger-hive-audit'], plugin_audit_attributes=params.config['configurationAttributes']['ranger-hive-audit'],
                            plugin_security_properties=params.config['configurations']['ranger-hive-security'], plugin_security_attributes=params.config['configurationAttributes']['ranger-hive-security'],
                            plugin_policymgr_ssl_properties=params.config['configurations']['ranger-hive-policymgr-ssl'], plugin_policymgr_ssl_attributes=params.config['configurationAttributes']['ranger-hive-policymgr-ssl'],
                            component_list=['hive-client', 'hive-metastore', 'hive-server2'], audit_db_is_enabled=params.xa_audit_db_is_enabled,
                            credential_file=params.credential_file, xa_audit_db_password=params.xa_audit_db_password,
                            ssl_truststore_password=params.ssl_truststore_password, ssl_keystore_password=params.ssl_keystore_password,
                            stack_version_override = stack_version, skip_if_rangeradmin_down= not params.retryAble, api_version=api_version,
                            is_security_enabled = params.security_enabled,
                            is_stack_supports_ranger_kerberos = params.stack_supports_ranger_kerberos,
                            component_user_principal=params.impala_principal if params.security_enabled else None,
                            component_user_keytab=params.impala_keytab if params.security_enabled else None)

        try:
            _reconcile_hive_repo_auth_users(params)
        except Exception as err:
            if params.retryAble:
                raise
            Logger.warning(
                "Unable to reconcile Hive Ranger repository auth users for Impala; continuing because retry is disabled."
            )
            Logger.exception(
                "Impala Ranger repository reconciliation failed with error:\n{0}".format(err)
            )
    else:
        Logger.info('Ranger option in Impala configuration and Ranger Hive Plugin option is disabled')
