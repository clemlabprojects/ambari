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

  if params.has_namenode:
    setup_configuration_file_for_required_plugins(
      component_user=params.polaris_user,
      component_group=params.user_group,
      create_core_site_path=params.polaris_conf_dir,
      configurations=params.config['configurations']['core-site'],
      configuration_attributes=params.config['configurationAttributes']['core-site'],
      file_name='core-site.xml')

  setup_ranger_plugin('polaris-server', 'polaris', params.previous_jdbc_jar,
                      params.downloaded_custom_connector, params.driver_curl_source,
                      params.driver_curl_target, params.java64_home,
                      params.repo_name, params.polaris_ranger_plugin_repo,
                      params.ranger_env, params.ranger_plugin_properties,
                      params.policy_user, params.policymgr_mgr_url,
                      params.enable_ranger_polaris, conf_dict=params.polaris_conf_dir,
                      component_user=params.polaris_user, component_group=params.user_group,
                      cache_service_list=['polaris'],
                      plugin_audit_properties=params.ranger_polaris_audit, plugin_audit_attributes=params.ranger_polaris_audit_attrs,
                      plugin_security_properties=params.ranger_polaris_security, plugin_security_attributes=params.ranger_polaris_security_attrs,
                      plugin_policymgr_ssl_properties=params.ranger_polaris_policymgr_ssl, plugin_policymgr_ssl_attributes=params.ranger_polaris_policymgr_ssl_attrs,
                      component_list=['polaris-server'], audit_db_is_enabled=False,
                      credential_file=params.credential_file, xa_audit_db_password=None,
                      ssl_truststore_password=params.ssl_truststore_password, ssl_keystore_password=params.ssl_keystore_password,
                      policy_config_dict=params.ranger_policy_config if params.ranger_policy_config else None,
                      api_version='v2', skip_if_rangeradmin_down=not params.retry_enabled,
                      is_security_enabled=params.security_enabled,
                      is_stack_supports_ranger_kerberos=params.stack_supports_ranger_kerberos,
                      component_user_principal=params.polaris_jaas_principal if params.security_enabled else None,
                      component_user_keytab=params.polaris_keytab_path if params.security_enabled else None)
