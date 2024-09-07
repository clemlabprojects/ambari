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
from resource_management.libraries.functions.setup_ranger_plugin_xml import setup_ranger_plugin
from resource_management.libraries.functions import ranger_functions_v2

def setup_ranger_ozone(upgrade_type=None, service_name="ozone-manager"):
  import params

  if params.enable_ranger_ozone:

    stack_version = None

    if upgrade_type is not None:
      stack_version = params.version

    if params.retryAble:
      Logger.info("Ozone: Setup ranger: command retry enables thus retrying if ranger admin is down !")
    else:
      Logger.info("Ozone: Setup ranger: command retry not enabled thus skipping if ranger admin is down !")

    if params.is_hdfs_enabled:
      if params.xa_audit_hdfs_is_enabled and service_name == 'ozone-manager':
        try:
          params.HdfsResource("/ranger/audit",
                            type="directory",
                            action="create_on_execute",
                            owner=params.ozone_user,
                            group=params.ozone_user,
                            mode=0o755,
                            recursive_chmod=True
          )
          params.HdfsResource("/ranger/audit/ozoneManager",
                            type="directory",
                            action="create_on_execute",
                            owner=params.ozone_user,
                            group=params.ozone_user,
                            mode=0o700,
                            recursive_chmod=True
          )
          params.HdfsResource(None, action="execute")
        except Exception as err:
          Logger.exception("Audit directory creation in HDFS for Ozone Ranger plugin failed with error:\n{0}".format(err))

    api_version = 'v2'
    Logger.info("Creating Ranger Ozone Repository User: " + params.repo_config_username)
    ranger_admin_v2_obj = ranger_functions_v2.RangeradminV2(url = params.policymgr_mgr_url, skip_if_rangeradmin_down = False)
    ranger_admin_v2_obj.create_policy_user(params.ranger_admin_username, params.ranger_admin_password, params.repo_config_username)

    setup_ranger_plugin('ozone-client', 'ozone', params.previous_jdbc_jar, params.downloaded_custom_connector,
                        params.driver_curl_source, params.driver_curl_target, params.java64_home,
                        params.repo_name, params.ozone_ranger_plugin_repo,
                        params.ranger_env, params.ranger_plugin_properties,
                        params.policy_user, params.policymgr_mgr_url,
                        params.enable_ranger_ozone, conf_dict=params.ozone_conf_dir,
                        component_user=params.ozone_user, component_group=params.user_group, cache_service_list=['ozoneManager'],
                        plugin_audit_properties=params.config['configurations']['ranger-ozone-audit'], plugin_audit_attributes=params.config['configurationAttributes']['ranger-ozone-audit'],
                        plugin_security_properties=params.config['configurations']['ranger-ozone-security'], plugin_security_attributes=params.config['configurationAttributes']['ranger-ozone-security'],
                        plugin_policymgr_ssl_properties=params.config['configurations']['ranger-ozone-policymgr-ssl'], plugin_policymgr_ssl_attributes=params.config['configurationAttributes']['ranger-ozone-policymgr-ssl'],
                        component_list=['ozone-manager'], audit_db_is_enabled=params.xa_audit_db_is_enabled,
                        credential_file=params.credential_file, xa_audit_db_password=params.xa_audit_db_password,
                        ssl_truststore_password=params.ssl_truststore_password, ssl_keystore_password=params.ssl_keystore_password, policy_config_dict = params.ranger_policy_config if params.ranger_policy_config else None,
                        stack_version_override = stack_version, skip_if_rangeradmin_down= not params.retryAble, api_version=api_version,
                        is_security_enabled = params.security_enabled,
                        is_stack_supports_ranger_kerberos = params.stack_supports_ranger_kerberos if params.security_enabled else None,
                        component_user_principal=params.ranger_ozone_principal if params.security_enabled else None,
                        component_user_keytab=params.ranger_ozone_keytab if params.security_enabled else None, copy_jar = False,
                        rangerlookup_password = params.rangerlookup_password if params.rangerlookup_create_user else None)
  else:
    Logger.info('Ranger Ozone plugin is not enabled')