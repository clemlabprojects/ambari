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
from resource_management.libraries.functions import ranger_functions_v2

def setup_ranger_atlas(upgrade_type=None):
  import params

  if params.enable_ranger_atlas:

    from resource_management.libraries.functions.setup_ranger_plugin_xml import setup_ranger_plugin

    if params.retry_enabled:
      Logger.info("ATLAS: Setup ranger: command retry enables thus retrying if ranger admin is down !")
    else:
      Logger.info("ATLAS: Setup ranger: command retry not enabled thus skipping if ranger admin is down !")

    if params.has_namenode and params.xa_audit_hdfs_is_enabled:
      try:
        params.HdfsResource("/ranger/audit",
                            type="directory",
                            action="create_on_execute",
                            owner=params.metadata_user,
                            group=params.user_group,
                            mode=0o755,
                            recursive_chmod=True
        )
        params.HdfsResource("/ranger/audit/atlas",
                            type="directory",
                            action="create_on_execute",
                            owner=params.metadata_user,
                            group=params.user_group,
                            mode=0o700,
                            recursive_chmod=True
        )
        params.HdfsResource(None, action="execute")
        if params.is_ranger_kms_ssl_enabled:
          Logger.info('Ranger KMS is ssl enabled, configuring ssl-client for hdfs audits.')
          setup_configuration_file_for_required_plugins(component_user = params.metadata_user, component_group = params.user_group,
                                                        create_core_site_path = params.conf_dir, configurations = params.config['configurations']['ssl-client'],
                                                        configuration_attributes = params.config['configurationAttributes']['ssl-client'], file_name='ssl-client.xml')
        else:
          Logger.info('Ranger KMS is not ssl enabled, skipping ssl-client for hdfs audits.')
      except Exception as err:
        Logger.exception("Audit directory creation in HDFS for ATLAS Ranger plugin failed with error:\n{0}".format(err))

    ranger_admin_v2_obj = ranger_functions_v2.RangeradminV2(url = params.policymgr_mgr_url, skip_if_rangeradmin_down = False)
    if params.rangranger_kafka_plugin_enabled:
      Logger.info("Creating Ranger Kafka Notification Topics Users")
      # Atlas hook notification publishers (hive, nifi, hbase)
      for ranger_policy_user in params.atlas_hook_publishers:
        ranger_admin_v2_obj.create_policy_user(params.ranger_admin_username, params.ranger_admin_password, ranger_policy_user)
      # Ranger Tag Sync
      ranger_admin_v2_obj.create_policy_user(params.ranger_admin_username, params.ranger_admin_password, rangertagsync_user)

      # Kafka Topics related notifications
      for ranger_policy in params.ranger_atlas_kafka_policies:
        Logger.info("Creating Ranger Policy: {0}".format(ranger_policy.name))
        ranger_admin_v2_obj.create_policy_urllib2(ranger_policy, params.ranger_admin_username, params.ranger_admin_password)

    # cred_lib_path_override  = format('{params.stack_root}/{params.stack_version}/ranger-atlas-plugin/libext/li/*')
    setup_ranger_plugin('atlas-server', 'atlas',None,
                        params.downloaded_custom_connector, params.driver_curl_source,
                        params.driver_curl_target, params.java64_home,
                        params.repo_name, params.atlas_ranger_plugin_repo,
                        params.ranger_env, params.ranger_plugin_properties,
                        params.policy_user, params.policymgr_mgr_url,
                        params.enable_ranger_atlas, conf_dict=params.conf_dir,
                        component_user=params.metadata_user, component_group=params.user_group, cache_service_list=['atlas'],
                        plugin_audit_properties=params.config['configurations']['ranger-atlas-audit'], plugin_audit_attributes=params.config['configurationAttributes']['ranger-atlas-audit'],
                        plugin_security_properties=params.config['configurations']['ranger-atlas-security'], plugin_security_attributes=params.config['configurationAttributes']['ranger-atlas-security'],
                        plugin_policymgr_ssl_properties=params.config['configurations']['ranger-atlas-policymgr-ssl'], plugin_policymgr_ssl_attributes=params.config['configurationAttributes']['ranger-atlas-policymgr-ssl'],
                        component_list=['atlas-server'], audit_db_is_enabled=False,
                        credential_file=params.credential_file, xa_audit_db_password=None,
                        ssl_truststore_password=params.ssl_truststore_password, ssl_keystore_password=params.ssl_keystore_password, policy_config_dict = params.ranger_policy_config if params.ranger_policy_config else None,
                        api_version = 'v2', skip_if_rangeradmin_down = not params.retry_enabled, is_security_enabled = params.security_enabled,
                        is_stack_supports_ranger_kerberos = params.stack_supports_ranger_kerberos,
                        component_user_principal=params.atlas_jaas_principal if params.security_enabled else None,
                        component_user_keytab=params.atlas_keytab_path if params.security_enabled else None)
  else:
    Logger.info('Ranger Atlas plugin is not enabled')