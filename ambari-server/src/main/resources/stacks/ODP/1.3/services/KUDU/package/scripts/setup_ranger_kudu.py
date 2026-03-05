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
from resource_management.libraries.functions.setup_ranger_plugin_xml import setup_configuration_file_for_required_plugins


def setup_ranger_kudu():
  import params

  if not params.enable_ranger_kudu:
    Logger.info('Ranger Kudu plugin is not enabled')
    return

  if not params.policymgr_mgr_url:
    Logger.warning('Ranger Kudu plugin is enabled but Ranger Admin URL is not configured. Skipping setup.')
    return

  from resource_management.libraries.functions.setup_ranger_plugin_xml import setup_ranger_plugin

  if params.retry_enabled:
    Logger.info("Kudu: Ranger setup will retry if Ranger Admin is temporarily unavailable.")
  else:
    Logger.info("Kudu: Ranger setup will skip if Ranger Admin is unavailable.")

  if params.has_namenode and params.xa_audit_hdfs_is_enabled and hasattr(params, 'HdfsResource'):
    try:
      params.HdfsResource("/ranger/audit",
                          type="directory",
                          action="create_on_execute",
                          owner=params.kudu_user,
                          group=params.kudu_group,
                          mode=0o755,
                          recursive_chmod=True)
      params.HdfsResource("/ranger/audit/kudu",
                          type="directory",
                          action="create_on_execute",
                          owner=params.kudu_user,
                          group=params.kudu_group,
                          mode=0o700,
                          recursive_chmod=True)
      params.HdfsResource(None, action="execute")
    except Exception as err:
      Logger.exception("Audit directory creation in HDFS for Kudu Ranger plugin failed with error:\n{0}".format(err))

  if params.has_namenode:
    setup_configuration_file_for_required_plugins(
      component_user=params.kudu_user,
      component_group=params.kudu_group,
      create_core_site_path=params.kudu_conf_dir,
      configurations=params.config['configurations']['core-site'],
      configuration_attributes=params.config['configurationAttributes']['core-site'],
      file_name='core-site.xml')

  setup_ranger_plugin('kudu-master', 'kudu', params.previous_jdbc_jar,
                      params.downloaded_custom_connector, params.driver_curl_source,
                      params.driver_curl_target, params.java_home,
                      params.repo_name, params.kudu_ranger_plugin_repo,
                      params.ranger_env, params.ranger_plugin_properties,
                      params.policy_user, params.policymgr_mgr_url,
                      params.enable_ranger_kudu, conf_dict=params.kudu_conf_dir,
                      component_user=params.kudu_user, component_group=params.kudu_group,
                      cache_service_list=['kudu'],
                      plugin_audit_properties=params.ranger_kudu_audit, plugin_audit_attributes=params.ranger_kudu_audit_attrs,
                      plugin_security_properties=params.ranger_kudu_security, plugin_security_attributes=params.ranger_kudu_security_attrs,
                      plugin_policymgr_ssl_properties=params.ranger_kudu_policymgr_ssl, plugin_policymgr_ssl_attributes=params.ranger_kudu_policymgr_ssl_attrs,
                      component_list=['kudu-master'], audit_db_is_enabled=params.xa_audit_db_is_enabled,
                      credential_file=params.credential_file, xa_audit_db_password=None,
                      ssl_truststore_password=params.ssl_truststore_password, ssl_keystore_password=params.ssl_keystore_password,
                      policy_config_dict=params.ranger_policy_config if params.ranger_policy_config else None,
                      api_version='v2', skip_if_rangeradmin_down=not params.retry_enabled,
                      is_security_enabled=params.security_enabled,
                      is_stack_supports_ranger_kerberos=params.stack_supports_ranger_kerberos,
                      component_user_principal=params.ranger_kudu_principal if params.security_enabled else None,
                      component_user_keytab=params.ranger_kudu_keytab if params.security_enabled else None)
