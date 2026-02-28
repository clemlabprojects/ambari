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

from resource_management.libraries.functions.default import default
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.get_bare_principal import get_bare_principal
from resource_management.libraries.functions.get_kinit_path import get_kinit_path
from resource_management.libraries.functions.is_empty import is_empty
from resource_management.libraries.functions.setup_ranger_plugin_xml import generate_ranger_service_config
from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.stack_features import check_stack_feature, get_stack_feature_version

config = Script.get_config()
stack_root = Script.get_stack_root()

version = default("/commandParams/version", None)
version_for_stack_feature_checks = get_stack_feature_version(config)
retry_enabled = default("/commandParams/command_retry_enabled", False)

polaris_user = config['configurations']['polaris-env']['polaris_user']
user_group = config['configurations']['cluster-env']['user_group']
java64_home = config['ambariLevelParams']['java_home']

security_enabled = str(default("/configurations/cluster-env/security_enabled", "false")).lower() == "true"
kinit_path_local = get_kinit_path(default("/configurations/kerberos-env/executable_search_paths", None))

polaris_log_dir = default("/configurations/polaris-env/polaris_log_dir", "/var/log/polaris")
polaris_pid_dir = default("/configurations/polaris-env/polaris_pid_dir", "/var/run/polaris")
polaris_pid_file = format("{polaris_pid_dir}/polaris.pid")
polaris_conf_dir = default("/configurations/polaris-env/polaris_conf_dir", "/etc/polaris/conf")
polaris_conf_file = default("/configurations/polaris-env/polaris_conf_file", "application.properties")
polaris_data_dir = default("/configurations/polaris-env/polaris_data_dir", "/var/lib/polaris")
polaris_keytab_path = default("/configurations/polaris-env/polaris_keytab", "/etc/security/keytabs/polaris.service.keytab")
polaris_principal_value = default("/configurations/polaris-env/polaris_principal", "polaris/_HOST@EXAMPLE.COM")
polaris_jaas_conf = default("/configurations/polaris-env/polaris_jaas_file", format("{polaris_conf_dir}/polaris_jaas.conf"))
polaris_jaas_context = default("/configurations/polaris-env/polaris_jaas_context", "PolarisServer")

polaris_home = default("/configurations/polaris-env/polaris_home",
                      format("{stack_root}/current/polaris-server"))
polaris_tools_home = default("/configurations/polaris-env/polaris_tools_home",
                            format("{stack_root}/current/polaris-tools"))
polaris_mcp_home = default("/configurations/polaris-env/polaris_mcp_home", polaris_tools_home)

polaris_env_content = config['configurations']['polaris-env']['content']
polaris_opts = config['configurations']['polaris-env']['polaris_opts']
polaris_admin_username = config['configurations']['polaris-env']['polaris_admin_username']
polaris_admin_password = config['configurations']['polaris-env']['polaris_admin_password']
polaris_jaas_conf_template = config['configurations']['polaris-jaas-conf']['content']

polaris_start_command = default("/configurations/polaris-env/polaris_start_command", "").strip()
polaris_stop_command = default("/configurations/polaris-env/polaris_stop_command", "").strip()

if not polaris_start_command:
  polaris_start_command = format("{polaris_home}/bin/server")
else:
  # Backward compatibility: older stacks used bin/polaris as start command.
  # bin/polaris is now the CLI wrapper; service runtime must use bin/server.
  start_parts = polaris_start_command.split()
  if len(start_parts) > 0 and start_parts[0].endswith("/bin/polaris"):
    start_parts[0] = start_parts[0][:-len("/bin/polaris")] + "/bin/server"
    polaris_start_command = " ".join(start_parts)
if not polaris_stop_command:
  polaris_stop_command = None
else:
  stop_parts = polaris_stop_command.split()
  if len(stop_parts) > 0 and stop_parts[0].endswith("/bin/polaris"):
    stop_parts[0] = stop_parts[0][:-len("/bin/polaris")] + "/bin/server"
    polaris_stop_command = " ".join(stop_parts)

application_properties = dict(config['configurations']['polaris-application-properties'])

polaris_protocol = default("/configurations/polaris-env/polaris_protocol", "http")
polaris_port = int(default("/configurations/polaris-application-properties/quarkus.http.port", 8181))

polaris_hosts = sorted(default("/clusterHostInfo/polaris_server_hosts", []))
polaris_service_host = polaris_hosts[0] if polaris_hosts else config['agentLevelParams']['hostname']
polaris_service_url = "{0}://{1}:{2}".format(polaris_protocol, polaris_service_host, polaris_port)

polaris_mcp_transport = default("/configurations/polaris-env/polaris_mcp_transport", "http")
polaris_mcp_host = default("/configurations/polaris-env/polaris_mcp_host", "0.0.0.0")
polaris_mcp_port = int(default("/configurations/polaris-env/polaris_mcp_port", 8000))
polaris_mcp_protocol = default("/configurations/polaris-env/polaris_mcp_protocol", "http")
polaris_mcp_pid_file = format("{polaris_pid_dir}/polaris-mcp.pid")

polaris_mcp_base_url = default("/configurations/polaris-env/polaris_mcp_base_url", "").strip()
if not polaris_mcp_base_url:
  polaris_mcp_base_url = "{0}/".format(polaris_service_url.rstrip('/'))

polaris_mcp_start_command = default("/configurations/polaris-env/polaris_mcp_start_command", "").strip()
if not polaris_mcp_start_command:
  polaris_mcp_start_command = format(
    "{polaris_mcp_home}/bin/polaris-mcp --transport {polaris_mcp_transport} "
    "--host {polaris_mcp_host} --port {polaris_mcp_port}"
  )
polaris_mcp_stop_command = default("/configurations/polaris-env/polaris_mcp_stop_command", "").strip()
if not polaris_mcp_stop_command:
  polaris_mcp_stop_command = None

polaris_mcp_hosts = sorted(default("/clusterHostInfo/polaris_mcp_server_hosts", []))

polaris_jaas_principal = None
polaris_bare_principal = None
polaris_kerberos_opts = ""
if security_enabled:
  current_host = config['agentLevelParams']['hostname'].lower()
  polaris_jaas_principal = polaris_principal_value.replace('_HOST', current_host)
  polaris_bare_principal = get_bare_principal(polaris_principal_value)
  polaris_kerberos_opts = format(
    "-Djava.security.auth.login.config={polaris_jaas_conf} -Djavax.security.auth.useSubjectCredsOnly=false"
  )

# Ranger Polaris plugin integration
ranger_policy_config = {}
stack_supports_ranger_kerberos = check_stack_feature(StackFeature.RANGER_KERBEROS_SUPPORT, version_for_stack_feature_checks)
xml_configurations_supported = check_stack_feature(StackFeature.RANGER_XML_CONFIGURATION, version_for_stack_feature_checks)
ranger_admin_hosts = default("/clusterHostInfo/ranger_admin_hosts", [])
has_ranger_admin = len(ranger_admin_hosts) > 0
enable_ranger_polaris = default("/configurations/ranger-polaris-plugin-properties/ranger-polaris-plugin-enabled", "No")
enable_ranger_polaris = str(enable_ranger_polaris).lower() == "yes"

namenode_host = default("/clusterHostInfo/namenode_host", None)
has_namenode = namenode_host is not None
hadoop_bin_dir = stack_select.get_hadoop_dir("bin") if has_namenode else None
hadoop_conf_dir = os.environ.get("HADOOP_CONF_DIR", "/etc/hadoop/conf")

is_ranger_kms_ssl_enabled = default("configurations/ranger-kms-site/ranger.service.https.attrib.ssl.enabled", False)

# Defaults for Ranger setup arguments that are optional for Polaris
previous_jdbc_jar = None
downloaded_custom_connector = None
driver_curl_source = None
driver_curl_target = None

if enable_ranger_polaris:
  repo_name = "{0}_polaris".format(config['clusterName'])
  repo_name_value = config['configurations']['ranger-polaris-security']['ranger.plugin.polaris.service.name']
  if not is_empty(repo_name_value) and repo_name_value != "{{repo_name}}":
    repo_name = repo_name_value

  ssl_keystore_password = config['configurations']['ranger-polaris-policymgr-ssl']['xasecure.policymgr.clientssl.keystore.password']
  ssl_truststore_password = config['configurations']['ranger-polaris-policymgr-ssl']['xasecure.policymgr.clientssl.truststore.password']
  credential_file = format('/etc/ranger/{repo_name}/cred.jceks')
  xa_audit_hdfs_is_enabled = default('/configurations/ranger-polaris-audit/xasecure.audit.destination.hdfs', False)

  policymgr_mgr_url = config['configurations']['ranger-polaris-security']['ranger.plugin.polaris.policy.rest.url']
  if is_empty(policymgr_mgr_url) or policymgr_mgr_url == "{{policymgr_mgr_url}}":
    policymgr_mgr_url = default('/configurations/admin-properties/policymgr_external_url', None)
    if is_empty(policymgr_mgr_url):
      policymgr_mgr_url = default('/configurations/ranger-admin-site/ranger.service.http.url', None)
  if not is_empty(policymgr_mgr_url) and policymgr_mgr_url.endswith('/'):
    policymgr_mgr_url = policymgr_mgr_url.rstrip('/')

  ranger_env = config['configurations']['ranger-env']
  if not has_ranger_admin:
    external_admin_username = default('/configurations/ranger-polaris-plugin-properties/external_admin_username', 'admin')
    external_admin_password = default('/configurations/ranger-polaris-plugin-properties/external_admin_password', 'admin')
    external_ranger_admin_username = default('/configurations/ranger-polaris-plugin-properties/external_ranger_admin_username', 'amb_ranger_admin')
    external_ranger_admin_password = default('/configurations/ranger-polaris-plugin-properties/external_ranger_admin_password', 'amb_ranger_admin')
    ranger_env = {
      'admin_username': external_admin_username,
      'admin_password': external_admin_password,
      'ranger_admin_username': external_ranger_admin_username,
      'ranger_admin_password': external_ranger_admin_password,
    }
    ranger_admin_username = external_ranger_admin_username
    ranger_admin_password = external_admin_password
  else:
    ranger_admin_username = config['configurations']['ranger-env']['admin_username']
    ranger_admin_password = config['configurations']['ranger-env']['admin_password']

  ranger_plugin_properties = config['configurations']['ranger-polaris-plugin-properties']
  ranger_polaris_audit = config['configurations']['ranger-polaris-audit']
  ranger_polaris_audit_attrs = config['configurationAttributes']['ranger-polaris-audit']
  ranger_polaris_security = config['configurations']['ranger-polaris-security']
  ranger_polaris_security_attrs = config['configurationAttributes']['ranger-polaris-security']
  ranger_polaris_policymgr_ssl = config['configurations']['ranger-polaris-policymgr-ssl']
  ranger_polaris_policymgr_ssl_attrs = config['configurationAttributes']['ranger-polaris-policymgr-ssl']

  policy_user = ranger_plugin_properties['policy_user']
  polaris_service_url_override = default('/configurations/ranger-polaris-plugin-properties/polaris.service.url', '').strip()
  polaris_repository_url = polaris_service_url_override if polaris_service_url_override else polaris_service_url

  polaris_repository_configuration = {
    'username': ranger_plugin_properties['REPOSITORY_CONFIG_USERNAME'],
    'password': ranger_plugin_properties['REPOSITORY_CONFIG_PASSWORD'],
    'polaris.rest.address': polaris_repository_url,
    'commonNameForCertificate': ranger_plugin_properties['common.name.for.certificate'],
    'ambari.service.check.user': policy_user,
  }

  custom_ranger_service_config = generate_ranger_service_config(ranger_plugin_properties)
  if len(custom_ranger_service_config) > 0:
    polaris_repository_configuration.update(custom_ranger_service_config)

  if security_enabled:
    polaris_repository_configuration['policy.download.auth.users'] = polaris_user
    polaris_repository_configuration['tag.download.auth.users'] = polaris_user

  polaris_ranger_plugin_repo = {
    'isEnabled': 'true',
    'configs': polaris_repository_configuration,
    'description': 'polaris repo',
    'name': repo_name,
    'type': 'polaris',
  }

  if has_namenode:
    hdfs_user = config['configurations']['hadoop-env']['hdfs_user']
    hdfs_user_keytab = config['configurations']['hadoop-env']['hdfs_user_keytab']
    hdfs_principal_name = config['configurations']['hadoop-env']['hdfs_principal_name']
    hdfs_site = config['configurations']['hdfs-site']
    default_fs = config['configurations']['core-site']['fs.defaultFS']
    dfs_type = default("/clusterLevelParams/dfs_type", "")

    import functools
    from resource_management.libraries.functions.get_not_managed_resources import get_not_managed_resources
    from resource_management.libraries.resources.hdfs_resource import HdfsResource

    HdfsResource = functools.partial(
      HdfsResource,
      user=hdfs_user,
      hdfs_resource_ignore_file="/var/lib/ambari-agent/data/.hdfs_resource_ignore",
      security_enabled=security_enabled,
      keytab=hdfs_user_keytab,
      kinit_path_local=kinit_path_local,
      hadoop_bin_dir=hadoop_bin_dir,
      hadoop_conf_dir=hadoop_conf_dir,
      principal_name=hdfs_principal_name,
      hdfs_site=hdfs_site,
      default_fs=default_fs,
      immutable_paths=get_not_managed_resources(),
      dfs_type=dfs_type,
    )

smoke_test_user = config['configurations']['cluster-env']['smokeuser']
