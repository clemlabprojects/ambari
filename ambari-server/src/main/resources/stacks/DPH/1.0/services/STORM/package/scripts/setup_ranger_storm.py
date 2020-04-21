#!/usr/bin/env python
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
import os

from resource_management.core.logger import Logger
from resource_management.libraries.functions.setup_ranger_plugin_xml import setup_ranger_plugin
from resource_management.libraries.functions.setup_ranger_plugin_xml import setup_configuration_file_for_required_plugins
from resource_management.libraries.functions.format import format
from resource_management.core.resources import File, Directory

def setup_ranger_storm(upgrade_type=None):
  """
  :param upgrade_type: Upgrade Type such as "rolling" or "nonrolling"
  """
  import params
  if params.enable_ranger_storm and params.security_enabled:
    site_files_create_path = format('{storm_component_home_dir}/extlib-daemon/ranger-storm-plugin-impl/conf')
    Directory(site_files_create_path,
              owner = params.storm_user,
              group = params.user_group,
              mode=0775,
              create_parents = True,
              cd_access = 'a'
              )

    stack_version = None
    if upgrade_type is not None:
      stack_version = params.version

    if params.retryAble:
      Logger.info("Storm: Setup ranger: command retry enables thus retrying if ranger admin is down !")
    else:
      Logger.info("Storm: Setup ranger: command retry not enabled thus skipping if ranger admin is down !")

    if params.has_namenode and params.xa_audit_hdfs_is_enabled:
      try:
        params.HdfsResource("/ranger/audit",
                           type="directory",
                           action="create_on_execute",
                           owner=params.hdfs_user,
                           group=params.hdfs_user,
                           mode=0755,
                           recursive_chmod=True
        )
        params.HdfsResource("/ranger/audit/storm",
                           type="directory",
                           action="create_on_execute",
                           owner=params.storm_user,
                           group=params.storm_user,
                           mode=0700,
                           recursive_chmod=True
        )
        params.HdfsResource(None, action="execute")
        if params.is_ranger_kms_ssl_enabled:
          Logger.info('Ranger KMS is ssl enabled, configuring ssl-client for hdfs audits.')
          setup_configuration_file_for_required_plugins(component_user = params.storm_user, component_group = params.user_group,
                                                        create_core_site_path = site_files_create_path, configurations = params.config['configurations']['ssl-client'],
                                                        configuration_attributes = params.config['configurationAttributes']['ssl-client'], file_name='ssl-client.xml')
        else:
          Logger.info('Ranger KMS is not ssl enabled, skipping ssl-client for hdfs audits.')
      except Exception, err:
        Logger.exception("Audit directory creation in HDFS for STORM Ranger plugin failed with error:\n{0}".format(err))

    api_version = 'v2'
    setup_ranger_plugin('storm-nimbus', 'storm', params.previous_jdbc_jar,
                        params.downloaded_custom_connector, params.driver_curl_source,
                        params.driver_curl_target, params.java64_home,
                        params.repo_name, params.storm_ranger_plugin_repo,
                        params.ranger_env, params.ranger_plugin_properties,
                        params.policy_user, params.policymgr_mgr_url,
                        params.enable_ranger_storm, conf_dict=params.conf_dir,
                        component_user=params.storm_user, component_group=params.user_group, cache_service_list=['storm'],
                        plugin_audit_properties=params.config['configurations']['ranger-storm-audit'], plugin_audit_attributes=params.config['configurationAttributes']['ranger-storm-audit'],
                        plugin_security_properties=params.config['configurations']['ranger-storm-security'], plugin_security_attributes=params.config['configurationAttributes']['ranger-storm-security'],
                        plugin_policymgr_ssl_properties=params.config['configurations']['ranger-storm-policymgr-ssl'], plugin_policymgr_ssl_attributes=params.config['configurationAttributes']['ranger-storm-policymgr-ssl'],
                        component_list=['storm-client', 'storm-nimbus'], audit_db_is_enabled=params.xa_audit_db_is_enabled,
                        credential_file=params.credential_file, xa_audit_db_password=params.xa_audit_db_password,
                        ssl_truststore_password=params.ssl_truststore_password, ssl_keystore_password=params.ssl_keystore_password,
                        stack_version_override = stack_version, skip_if_rangeradmin_down= not params.retryAble,api_version=api_version,
                        is_security_enabled = params.security_enabled,
                        is_stack_supports_ranger_kerberos = params.stack_supports_ranger_kerberos,
                        component_user_principal=params.ranger_storm_principal if params.security_enabled else None,
                        component_user_keytab=params.ranger_storm_keytab if params.security_enabled else None)



    if params.stack_supports_core_site_for_ranger_plugin and params.enable_ranger_storm and params.security_enabled:
      if params.has_namenode:

        mount_table_xml_inclusion_file_full_path = None
        mount_table_content = None
        if 'viewfs-mount-table' in params.config['configurations']:
          xml_inclusion_file_name = 'viewfs-mount-table.xml'
          mount_table = params.config['configurations']['viewfs-mount-table']

          if 'content' in mount_table and mount_table['content'].strip():
            mount_table_xml_inclusion_file_full_path = os.path.join(site_files_create_path, xml_inclusion_file_name)
            mount_table_content = mount_table['content']

        Logger.info("Stack supports core-site.xml creation for Ranger plugin and Namenode is installed, creating create core-site.xml from namenode configurations")
        setup_configuration_file_for_required_plugins(component_user = params.storm_user, component_group = params.user_group,
                                                      create_core_site_path = site_files_create_path, configurations = params.config['configurations']['core-site'],
                                                      configuration_attributes = params.config['configuration_attributes']['core-site'], file_name='core-site.xml',
                                                      xml_include_file=mount_table_xml_inclusion_file_full_path, xml_include_file_content=mount_table_content)
      else:
        Logger.info("Stack supports core-site.xml creation for Ranger plugin and Namenode is not installed, creating create core-site.xml from default configurations")
        setup_configuration_file_for_required_plugins(component_user = params.storm_user, component_group = params.user_group,
                                                      create_core_site_path = site_files_create_path, configurations = { 'hadoop.security.authentication' : 'kerberos' if params.security_enabled else 'simple' },
                                                      configuration_attributes = {}, file_name='core-site.xml')

      if len(params.namenode_hosts) > 1:
        Logger.info('Ranger Storm plugin is enabled along with security and NameNode is HA , creating hdfs-site.xml')
        setup_configuration_file_for_required_plugins(component_user = params.storm_user, component_group = params.user_group,
                                                      create_core_site_path = site_files_create_path, configurations = params.config['configurations']['hdfs-site'],
                                                      configuration_attributes = params.config['configurationAttributes']['hdfs-site'], file_name = 'hdfs-site.xml')
      else:
        Logger.info('Ranger Storm plugin is not enabled or security is disabled, removing hdfs-site.xml')
        File(format('{site_files_create_path}/hdfs-site.xml'), action="delete")
    else:
      Logger.info("Stack does not support core-site.xml creation for Ranger plugin, skipping core-site.xml configurations")
  else:
    Logger.info('Ranger Storm plugin is not enabled')
