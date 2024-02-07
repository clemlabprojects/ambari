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

from resource_management import *
from resource_management import get_bare_principal
from resource_management.libraries.script.script import Script
from resource_management.core import sudo
import sys, os, glob, socket, re
from resource_management.libraries.functions import format
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.version import format_stack_version
from resource_management.libraries.functions.version_select_util import *
from resource_management.libraries.functions import StackFeature
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.stack_features import get_stack_feature_version
from resource_management.libraries.resources.hdfs_resource import HdfsResource
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions import conf_select
from resource_management.libraries.functions import get_kinit_path
from resource_management.libraries.functions.get_not_managed_resources import get_not_managed_resources
import ambari_simplejson as json # simplejson is much faster comparing to Python 2.6 json module and has the same functions set

import config_utils

# server configurations
config = Script.get_config()
stack_root = Script.get_stack_root()
zk_root = Script.get_stack_root();
tmp_dir = Script.get_tmp_dir()
stack_name = default("/clusterLevelParams/stack_name", None)
stack_version_buildnum = default("/commandParams/version", None)
zk_stack_version_buildnum = default("/commandParams/version", None)

if stack_name == "ODP":
  # Override ODP stack root
  stack_root = "/usr/odp"
  # # When installing on ODP, ZK will be in /usr/odp
  zk_root = "/usr/odp"
  # Override ODP stack version
  stack_version_buildnum = get_component_version_with_stack_selector("/usr/bin/odp-select", "nifi")
  # When installing on ODP, ZK will come from ODP so use odp-select instead of hdf-select
  zk_stack_version_buildnum = get_component_version_with_stack_selector("/usr/bin/odp-select", "zookeeper-client")
elif not stack_version_buildnum and stack_name:
  stack_version_buildnum = get_component_version_from_symlink(stack_name, "nifi")
  zk_stack_version_buildnum = get_component_version_from_symlink(stack_name, "zookeeper-client")

service_name = 'nifi'
version_for_stack_feature_checks = get_stack_feature_version(config)

script_dir = os.path.dirname(__file__)
toolkit_files_dir = os.path.realpath(os.path.join(os.path.dirname(script_dir), 'files'))
toolkit_tmp_dir = tmp_dir

# Version being upgraded/downgraded to
version = default("/commandParams/version", None)
#upgrade direction
upgrade_direction = default("/commandParams/upgrade_direction", None)

nifi_install_dir = os.path.join(stack_root, "current", "nifi")
# nifi registry properties
if 'nifi-registry-ambari-config' in config['configurations']:
  nifi_registry_port = config['configurations']['nifi-registry-ambari-config']['nifi.registry.port']
  nifi_registry_ssl_port = config['configurations']['nifi-registry-ambari-config']['nifi.registry.port.ssl']
  nifi_registry_ssl_enabled = config['configurations']['nifi-registry-ambari-ssl-config']['nifi.registry.ssl.isenabled']
  nifi_registry_url_port = nifi_registry_ssl_port if nifi_registry_ssl_enabled else nifi_registry_port
  nifi_registry_master_hosts = default("/clusterHostInfo/nifi_registry_master_hosts", [])
  nifi_registry_host = None if len(nifi_registry_master_hosts) == 0 else nifi_registry_master_hosts[0]
  nifi_registry_protocol = "https" if nifi_registry_ssl_enabled else "http"
  nifi_registry_url = format("{nifi_registry_protocol}://{nifi_registry_host}:{nifi_registry_url_port}")
else:
  nifi_registry_url = None


# params from nifi-ambari-config
nifi_initial_mem = config['configurations']['nifi-ambari-config']['nifi.initial_mem']
nifi_max_mem = config['configurations']['nifi-ambari-config']['nifi.max_mem']
nifi_ambari_reporting_frequency = config['configurations']['nifi-ambari-config']['nifi.ambari_reporting_frequency']
nifi_ambari_reporting_enabled = config['configurations']['nifi-ambari-config']['nifi.ambari_reporting_enabled']


nifi_host_name = config['agentLevelParams']['hostname']

### SSL ###
# note: nifi.node.port and nifi.node.ssl.port must be defined in same xml file for quicklinks to work
nifi_ambari_ssl_enabled = config['configurations']['nifi-ambari-ssl-config']['nifi.node.ssl.isenabled']
#nifi_ssl_enabled = config['configurations']['nifi-ambari-ssl-config']['nifi.node.ssl.isenabled']

if nifi_ambari_ssl_enabled:
  # params from nifi-ambari-ssl-config
  nifi_keystore = config['configurations']['nifi-ambari-ssl-config']['nifi.security.keystore']
  nifi_keystoreType = config['configurations']['nifi-ambari-ssl-config']['nifi.security.keystoreType']
  nifi_keystorePasswd = config['configurations']['nifi-ambari-ssl-config']['nifi.security.keystorePasswd']
  nifi_keyPasswd = config['configurations']['nifi-ambari-ssl-config']['nifi.security.keyPasswd']
  nifi_truststore = config['configurations']['nifi-ambari-ssl-config']['nifi.security.truststore']
  nifi_truststoreType = config['configurations']['nifi-ambari-ssl-config']['nifi.security.truststoreType']
  nifi_truststorePasswd = config['configurations']['nifi-ambari-ssl-config']['nifi.security.truststorePasswd']
  nifi_initial_admin_id = config['configurations']['nifi-ambari-ssl-config']['nifi.initial.admin.identity']
  nifi_ssl_config_content = config['configurations']['nifi-ambari-ssl-config']['content']
else:
  nifi_keystore = config['configurations']['nifi-properties']['nifi.security.keystore']
  nifi_keystoreType = config['configurations']['nifi-properties']['nifi.security.keystoreType']
  nifi_keystorePasswd = config['configurations']['nifi-properties']['nifi.security.keystorePasswd']
  nifi_keyPasswd = config['configurations']['nifi-properties']['nifi.security.keyPasswd']
  nifi_truststore = config['configurations']['nifi-properties']['nifi.security.truststore']
  nifi_truststoreType = config['configurations']['nifi-properties']['nifi.security.truststoreType']
  nifi_truststorePasswd = config['configurations']['nifi-properties']['nifi.security.truststorePasswd']
  nifi_initial_admin_id = config['configurations']['nifi-ambari-ssl-config']['nifi.initial.admin.identity']
  nifi_ssl_config_content = config['configurations']['nifi-ambari-ssl-config']['content']

nifi_node_protocol_port = config['configurations']['nifi-ambari-config']['nifi.node.protocol.port']

#property that is set to hostname regardless of whether SSL enabled
nifi_node_host = socket.getfqdn()

if nifi_ambari_ssl_enabled:
  nifi_ssl_enabled = True
elif nifi_keystore !="":
  nifi_ssl_enabled = True
else:
  nifi_ssl_enabled = False

if nifi_ssl_enabled is False:
  nifi_node_ssl_host = ""
  nifi_node_ssl_port = ""
  nifi_node_nonssl_host = nifi_node_host
  nifi_node_port = config['configurations']['nifi-ambari-config']['nifi.node.port']
else:
  nifi_node_ssl_host = nifi_node_host
  nifi_node_ssl_port = config['configurations']['nifi-ambari-config']['nifi.node.ssl.port']
  nifi_node_nonssl_host = ""
  nifi_node_port = ""

nifi_url = format("https://{nifi_host_name}:{nifi_node_ssl_port}") if nifi_ssl_enabled else format("http://{nifi_host_name}:{nifi_node_port}")

#zookeeper node path
nifi_znode = config['configurations']['nifi-ambari-config']['nifi.nifi_znode']

nifi_internal_dir=config['configurations']['nifi-ambari-config']['nifi.internal.dir']
nifi_state_dir=config['configurations']['nifi-ambari-config']['nifi.state.dir']
nifi_database_dir=config['configurations']['nifi-ambari-config']['nifi.database.dir']
nifi_flowfile_repo_dir=config['configurations']['nifi-ambari-config']['nifi.flowfile.repository.dir']
nifi_provenance_repo_dir_default=config['configurations']['nifi-ambari-config']['nifi.provenance.repository.dir.default']
nifi_config_dir = config['configurations']['nifi-ambari-config']['nifi.config.dir']
nifi_flow_config_dir = config['configurations']['nifi-ambari-config']['nifi.flow.config.dir']
nifi_sensitive_props_key = config['configurations']['nifi-ambari-config']['nifi.sensitive.props.key']
nifi_security_encrypt_configuration_password = config['configurations']['nifi-ambari-config']['nifi.security.encrypt.configuration.password']

#param for nifi explicit key tab
nifi_allow_explicit_keytab =  str(config['configurations']['nifi-ambari-config']['nifi.allow.explicit.keytab']).lower() if 'nifi.allow.explicit.keytab' in config['configurations']['nifi-ambari-config'] else 'true'

#multiple content repository directories may be defined so search for all values
nifi_content_repo_dir_default = None

#check if default property is available in configurations
if 'nifi.content.repository.dir.default' in config['configurations']['nifi-ambari-config']:
  nifi_content_repo_dir_default=config['configurations']['nifi-ambari-config']['nifi.content.repository.dir.default']

nifi_content_repo_dirs = [ v.replace('{{nifi_content_repo_dir_default}}',nifi_content_repo_dir_default) for k,v in config['configurations']['nifi-properties'].items() if k.startswith('nifi.content.repository.dir')]

if nifi_content_repo_dir_default is not None:
  nifi_content_repo_dirs.append(nifi_content_repo_dir_default)

nifi_flow_config_dir = nifi_flow_config_dir.replace('{nifi_internal_dir}',nifi_internal_dir)
nifi_state_dir = nifi_state_dir.replace('{nifi_internal_dir}',nifi_internal_dir)
nifi_config_dir = nifi_config_dir.replace('{nifi_install_dir}',nifi_install_dir)

master_configs = config['clusterHostInfo']
nifi_master_hosts = master_configs['nifi_master_hosts']

#nifi bootstrap file location
nifi_bootstrap_file = nifi_config_dir + '/bootstrap.conf'

# detect if running in single (sandbox) box
nifi_num_nodes = len(master_configs['nifi_master_hosts'])

if nifi_num_nodes > 1:
  nifi_is_node='true'
else:
  nifi_is_node='false'

#is node joining an existing cluster
is_additional_node = False

nifi_node_dir=nifi_install_dir
bin_dir = os.path.join(*[nifi_node_dir,'bin'])
lib_dir = os.path.join(*[nifi_node_dir,'lib'])

nifi_ca_host = None
if 'nifi_ca_hosts' in master_configs:
  nifi_ca_hosts = master_configs['nifi_ca_hosts']
  if len(nifi_ca_hosts) > 0:
    nifi_ca_host = nifi_ca_hosts[0]

if 'nifi.security.needClientAuth' in config['configurations']['nifi-ambari-ssl-config']:
  nifi_needClientAuth = config['configurations']['nifi-ambari-ssl-config']['nifi.security.needClientAuth']
else:
  nifi_needClientAuth = ""

#default keystore/truststore type if empty
nifi_keystoreType = '' if len(nifi_keystoreType) == 0 else nifi_keystoreType
nifi_truststoreType = '' if len(nifi_truststoreType) == 0 else nifi_truststoreType

nifi_truststore = nifi_truststore.replace('{nifi_node_ssl_host}',nifi_node_host)
nifi_keystore = nifi_keystore.replace('{nifi_node_ssl_host}',nifi_node_host)

#populate properties whose values depend on whether SSL enabled
nifi_keystore = nifi_keystore.replace('{{nifi_config_dir}}',nifi_config_dir)
nifi_truststore = nifi_truststore.replace('{{nifi_config_dir}}',nifi_config_dir)

nifi_ca_parent_config = config['configurations']['nifi-ambari-ssl-config']
nifi_use_ca = nifi_ca_parent_config['nifi.toolkit.tls.token']
nifi_toolkit_dn_prefix = nifi_ca_parent_config['nifi.toolkit.dn.prefix']
nifi_toolkit_dn_suffix = nifi_ca_parent_config['nifi.toolkit.dn.suffix']
nifi_toolkit_tls_regenerate = nifi_ca_parent_config['nifi.toolkit.tls.regenerate']
nifi_ca_log_file_stdout = config['configurations']['nifi-env']['nifi_node_log_dir'] + '/nifi-ca.stdout'
nifi_ca_log_file_stderr = config['configurations']['nifi-env']['nifi_node_log_dir'] + '/nifi-ca.stderr'

force_skip_registry_registration = default('/configurations/nifi-env/skip_registry_registration', False)

nifi_ca_config = {
  "days" : int(nifi_ca_parent_config['nifi.toolkit.tls.helper.days']),
  "keyStore" : nifi_config_dir + '/nifi-certificate-authority-keystore.jks',
  "token" : nifi_ca_parent_config['nifi.toolkit.tls.token'],
  "caHostname" : nifi_ca_host,
  "port" : int(nifi_ca_parent_config['nifi.toolkit.tls.port'])
}

toolkit_ca_api_port = int(nifi_ca_parent_config['nifi.toolkit.tls.port'])
toolkit_ca_check_url = format("https://{nifi_ca_host}:{toolkit_ca_api_port}/v1/api")

if nifi_ca_host:
  nifi_ca_config['dn'] = nifi_toolkit_dn_prefix + nifi_ca_host + nifi_toolkit_dn_suffix

stack_support_tls_toolkit_san = check_stack_feature('tls_toolkit_san', version_for_stack_feature_checks)

nifi_ca_client_config = {
  "days" : int(nifi_ca_parent_config['nifi.toolkit.tls.helper.days']),
  "keyStore" : nifi_keystore,
  "keyStoreType" : nifi_keystoreType,
  "keyStorePassword" : nifi_keystorePasswd,
  "keyPassword" : nifi_keyPasswd,
  "token" : nifi_ca_parent_config['nifi.toolkit.tls.token'],
  "dn" : nifi_toolkit_dn_prefix + nifi_node_host + nifi_toolkit_dn_suffix,
  "port" : int(nifi_ca_parent_config['nifi.toolkit.tls.port']),
  "caHostname" : nifi_ca_host,
  "trustStore" : nifi_truststore,
  "trustStoreType" : nifi_truststoreType,
  "trustStorePassword": nifi_truststorePasswd
}

if stack_support_tls_toolkit_san:
  nifi_ca_client_config["domainAlternativeNames"] = nifi_node_host

# params from nifi-env
nifi_user = config['configurations']['nifi-env']['nifi_user']
nifi_group = config['configurations']['nifi-env']['nifi_group']

nifi_node_log_dir = config['configurations']['nifi-env']['nifi_node_log_dir']
nifi_node_log_file = os.path.join(nifi_node_log_dir,'nifi-setup.log')

# limits related params
limits_conf_dir = '/etc/security/limits.d'
nifi_user_nofile_limit = config['configurations']['nifi-env']['nifi_user_nofile_limit']
nifi_user_nproc_limit = config['configurations']['nifi-env']['nifi_user_nproc_limit']

# params from nifi-boostrap
nifi_env_content = config_utils.merge_env(config['configurations']['nifi-env'])


# params from nifi-logback
nifi_master_logback_content = config['configurations']['nifi-master-logback-env']['content']
nifi_node_logback_content = config['configurations']['nifi-node-logback-env']['content']

# params from nifi-properties-env
nifi_master_properties_content = config['configurations']['nifi-master-properties-env']['content']
nifi_properties = config['configurations']['nifi-properties'].copy()

#kerberos params
nifi_kerberos_authentication_expiration = config['configurations']['nifi-properties']['nifi.kerberos.spnego.authentication.expiration']
nifi_kerberos_realm = default("/configurations/kerberos-env/realm", None)

# params from nifi-flow
nifi_flow_content = config['configurations']['nifi-flow-env']['content']

# params from nifi-state-management-env
nifi_state_management_content = config['configurations']['nifi-state-management-env']['content']

# params from nifi-authorizers-env
nifi_authorizers_content = config['configurations']['nifi-authorizers-env']['content']
nifi_authorizers_dict = config['configurations']['nifi-authorizers-env']
# params from nifi-login-identity-providers-env
nifi_login_identity_providers_content = config['configurations']['nifi-login-identity-providers-env']['content']
nifi_login_identity_providers_dict = config['configurations']['nifi-login-identity-providers-env']
# params from nifi-boostrap
nifi_boostrap_content = config_utils.merge_env(config['configurations']['nifi-bootstrap-env'])

# params from nifi-bootstrap-notification-services-env
nifi_boostrap_notification_content = config['configurations']['nifi-bootstrap-notification-services-env']['content']
nifi_boostrap_notification_dict = config['configurations']['nifi-bootstrap-notification-services-env']
# params from nifi-toolkit-env
nifi_toolkit_java_options = config['configurations']['nifi-toolkit-env']['nifi_toolkit_java_options'] if 'nifi-toolkit-env' in config['configurations'] else '-Xms128m -Xmx256m'

#autodetect jdk home
jdk64_home=config['ambariLevelParams']['java_home']

#autodetect ambari server for metrics
if 'metrics_collector_hosts' in config['clusterHostInfo']:
  if default("/configurations/ams-site/timeline.metrics.service.http.policy", "HTTP_ONLY") == "HTTPS_ONLY":
    metrics_collector_protocol = 'https'
  else:
    metrics_collector_protocol = 'http'
  metrics_collector_host = str(config['clusterHostInfo']['metrics_collector_hosts'][0])
  metrics_collector_port = str(get_port_from_url(config['configurations']['ams-site']['timeline.metrics.service.webapp.address']))
else:
  metrics_collector_host = ''
  metrics_collector_port = ''


#detect zookeeper_quorum
zookeeper_port=default('/configurations/zoo.cfg/clientPort', None)
#get comma separated list of zookeeper hosts from clusterHostInfo
index = 0
zookeeper_quorum=""
zk_hosts_property = 'zookeeper_hosts' if 'zookeeper_hosts' in config['clusterHostInfo'] else "zookeeper_server_hosts"
for host in config['clusterHostInfo'][zk_hosts_property]:
  zookeeper_quorum += host + ":"+str(zookeeper_port)
  index += 1
  if index < len(config['clusterHostInfo'][zk_hosts_property]):
    zookeeper_quorum += ","


#setup ranger configuration
ranger_policy_config = {}
retryAble = default("/commandParams/command_retry_enabled", False)
version = default("/commandParams/version", None)
namenode_hosts = default("/clusterHostInfo/namenode_host", None)

if type(namenode_hosts) is list:
  namenode_host = namenode_hosts[0]
else:
  namenode_host = namenode_hosts

has_namenode = not namenode_host == None

nifi_authorizer = 'file-provider'

nifi_host_port = config['configurations']['nifi-ambari-config']['nifi.node.port']
java_home = config['ambariLevelParams']['java_home']
security_enabled = config['configurations']['cluster-env']['security_enabled']
smokeuser = config['configurations']['cluster-env']['smokeuser']
smokeuser_principal = config['configurations']['cluster-env']['smokeuser_principal_name']
smoke_user_keytab = config['configurations']['cluster-env']['smokeuser_keytab']
kinit_path_local = get_kinit_path(default('/configurations/kerberos-env/executable_search_paths', None))
stack_support_nifi_toolkit_package = check_stack_feature('nifi_admin_toolkit_support', version_for_stack_feature_checks)
#some released ODP stacks will not have this stack feature, manually check
if not stack_support_nifi_toolkit_package and stack_name == "ODP":
  marker_script = os.path.join(stack_root, "current/nifi-toolkit/bin/tls-toolkit.sh")
  if sudo.path_isfile(marker_script):
    stack_support_nifi_toolkit_package = True
stack_support_encrypt_config = check_stack_feature('nifi_encrypt_config', version_for_stack_feature_checks)
stack_support_toolkit_update = check_stack_feature('toolkit_config_update', version_for_stack_feature_checks)
stack_support_admin_toolkit = check_stack_feature('admin_toolkit_support', version_for_stack_feature_checks)
stack_support_nifi_jaas = check_stack_feature('nifi_jaas_conf_create', version_for_stack_feature_checks)
stack_support_encrypt_authorizers = check_stack_feature('nifi_encrypted_authorizers_config', version_for_stack_feature_checks)
stack_support_nifi_auto_client_registration = check_stack_feature('nifi_auto_client_registration', version_for_stack_feature_checks)

if security_enabled:
  _hostname_lowercase = nifi_host_name.lower()
  nifi_properties['nifi.kerberos.service.principal'] = nifi_properties['nifi.kerberos.service.principal'].replace('_HOST',_hostname_lowercase)
  nifi_properties['nifi.kerberos.spnego.principal'] = nifi_properties['nifi.kerberos.spnego.principal'].replace('_HOST',_hostname_lowercase)

  if stack_support_nifi_jaas:
    nifi_service_principal = nifi_properties['nifi.kerberos.service.principal']
    nifi_service_keytab = nifi_properties['nifi.kerberos.service.keytab.location']
    nifi_jaas_conf_template = config['configurations']['nifi-jaas-conf']['content']
    nifi_jaas_conf= nifi_config_dir +"/nifi_jaas.conf"

  zookeeper_principal = default("/configurations/zookeeper-env/zookeeper_principal_name", "zookeeper/_HOST@EXAMPLE.COM")
  zookeeper_principal_primary = get_bare_principal(zookeeper_principal)

# ranger host
# E.g., 2.3
stack_version_unformatted = config['clusterLevelParams']['stack_version']
stack_version_formatted = format_stack_version(stack_version_unformatted)
stack_supports_ranger_kerberos = stack_version_formatted and check_stack_feature(StackFeature.RANGER_KERBEROS_SUPPORT, stack_version_formatted)
stack_supports_ranger_audit_db = stack_version_formatted and check_stack_feature(StackFeature.RANGER_AUDIT_DB_SUPPORT, stack_version_formatted)

ranger_admin_hosts = default("/clusterHostInfo/ranger_admin_hosts", [])
has_ranger_admin = not len(ranger_admin_hosts) == 0
xml_configurations_supported = config['configurations']['ranger-env']['xml_configurations_supported']

ambari_server_hostname = config['clusterHostInfo']['ambari_server_host'][0]

# ranger nifi properties
policymgr_mgr_url = config['configurations']['admin-properties']['policymgr_external_url']

if 'admin-properties' in config['configurations'] and 'policymgr_external_url' in config['configurations']['admin-properties'] and policymgr_mgr_url.endswith('/'):
  policymgr_mgr_url = policymgr_mgr_url.rstrip('/')

xa_audit_db_name = config['configurations']['admin-properties']['db_name']
xa_audit_db_user = config['configurations']['admin-properties']['db_user']
xa_db_host = config['configurations']['admin-properties']['db_host']
repo_name = str(config['clusterName']) + '_nifi'

repo_config_username = config['configurations']['ranger-nifi-plugin-properties']['REPOSITORY_CONFIG_USERNAME']

ranger_env = config['configurations']['ranger-env']
ranger_plugin_properties = config['configurations']['ranger-nifi-plugin-properties']
policy_user = config['configurations']['ranger-nifi-plugin-properties']['policy_user']

#For curl command in ranger plugin to get db connector
jdk_location = config['ambariLevelParams']['jdk_location']
java_share_dir = '/usr/share/java'

if has_ranger_admin:
  enable_ranger_nifi = (config['configurations']['ranger-nifi-plugin-properties']['ranger-nifi-plugin-enabled'].lower() == 'yes')
  xa_audit_db_password = config['configurations']['admin-properties']['db_password'] if stack_supports_ranger_audit_db else None
  repo_config_password = config['configurations']['ranger-nifi-plugin-properties']['REPOSITORY_CONFIG_PASSWORD']
  xa_audit_db_flavor = (config['configurations']['admin-properties']['DB_FLAVOR']).lower()
  previous_jdbc_jar_name= None

  if stack_supports_ranger_audit_db:
    if xa_audit_db_flavor == 'mysql':
      jdbc_jar_name = default("/ambariLevelParams/custom_mysql_jdbc_name", None)
      previous_jdbc_jar_name = default("/ambariLevelParams/previous_custom_mysql_jdbc_name", None)
      audit_jdbc_url = format('jdbc:mysql://{xa_db_host}/{xa_audit_db_name}')
      jdbc_driver = "com.mysql.jdbc.Driver"
    elif xa_audit_db_flavor == 'oracle':
      jdbc_jar_name = default("/ambariLevelParams/custom_oracle_jdbc_name", None)
      previous_jdbc_jar_name = default("/ambariLevelParams/previous_custom_oracle_jdbc_name", None)
      colon_count = xa_db_host.count(':')
      if colon_count == 2 or colon_count == 0:
        audit_jdbc_url = format('jdbc:oracle:thin:@{xa_db_host}')
      else:
        audit_jdbc_url = format('jdbc:oracle:thin:@//{xa_db_host}')
      jdbc_driver = "oracle.jdbc.OracleDriver"
    elif xa_audit_db_flavor == 'postgres':
      jdbc_jar_name = default("/ambariLevelParams/custom_postgres_jdbc_name", None)
      previous_jdbc_jar_name = default("/ambariLevelParams/previous_custom_postgres_jdbc_name", None)
      audit_jdbc_url = format('jdbc:postgresql://{xa_db_host}/{xa_audit_db_name}')
      jdbc_driver = "org.postgresql.Driver"
    elif xa_audit_db_flavor == 'mssql':
      jdbc_jar_name = default("/ambariLevelParams/custom_mssql_jdbc_name", None)
      previous_jdbc_jar_name = default("/ambariLevelParams/previous_custom_mssql_jdbc_name", None)
      audit_jdbc_url = format('jdbc:sqlserver://{xa_db_host};databaseName={xa_audit_db_name}')
      jdbc_driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
    elif xa_audit_db_flavor == 'sqla':
      jdbc_jar_name = default("/ambariLevelParams/custom_sqlanywhere_jdbc_name", None)
      previous_jdbc_jar_name = default("/ambariLevelParams/previous_custom_sqlanywhere_jdbc_name", None)
      audit_jdbc_url = format('jdbc:sqlanywhere:database={xa_audit_db_name};host={xa_db_host}')
      jdbc_driver = "sap.jdbc4.sqlanywhere.IDriver"

  downloaded_custom_connector = format("{tmp_dir}/{jdbc_jar_name}") if stack_supports_ranger_audit_db else None
  driver_curl_source = format("{jdk_location}/{jdbc_jar_name}") if stack_supports_ranger_audit_db else None

  driver_curl_target = format("{stack_root}/current/nifi/ext/{jdbc_jar_name}") if stack_supports_ranger_audit_db else None
  previous_jdbc_jar = format("{stack_root}/current/nifi/ext/{previous_jdbc_jar_name}") if stack_supports_ranger_audit_db else None
  sql_connector_jar = ''

  ssl_keystore_password = config['configurations']['ranger-nifi-policymgr-ssl']['xasecure.policymgr.clientssl.keystore.password'] if xml_configurations_supported else None
  ssl_truststore_password = config['configurations']['ranger-nifi-policymgr-ssl']['xasecure.policymgr.clientssl.truststore.password'] if xml_configurations_supported else None
  credential_file = format('/etc/ranger/{repo_name}/cred.jceks') if xml_configurations_supported else None
  credential_file_type = 'jceks'
  ranger_admin_username = config['configurations']['ranger-env']['ranger_admin_username']
  ranger_admin_password = config['configurations']['ranger-env']['ranger_admin_password']

  #create ranger service's nifi client properties
  nifi_authentication = config['configurations']['ranger-nifi-plugin-properties']['nifi.authentication']
  ranger_id_owner_for_certificate = config['configurations']['ranger-nifi-plugin-properties']['owner.for.certificate']
  nifi_id_owner_for_certificate = config['configurations']['ranger-nifi-policymgr-ssl']['owner.for.certificate']
  regex = r"(CN)=([a-zA-Z0-9\.\-\*\[\]\|\:]*)"
  match = re.search(regex, nifi_id_owner_for_certificate)
  common_name_for_certificate = match.group(2) if match else 'NONE'

  if nifi_authentication == 'SSL':
    nifi_ranger_plugin_config = {
      'nifi.authentication': nifi_authentication,
      'nifi.url': format("https://{nifi_host_name}:{nifi_node_ssl_port}/nifi-api/resources"),
      'nifi.ssl.keystore': config['configurations']['ranger-nifi-plugin-properties']['nifi.ssl.keystore'],
      'nifi.ssl.keystoreType':config['configurations']['ranger-nifi-plugin-properties']['nifi.ssl.keystoreType'],
      'nifi.ssl.keystorePassword': config['configurations']['ranger-nifi-plugin-properties']['nifi.ssl.keystorePassword'],
      'nifi.ssl.truststore': config['configurations']['ranger-nifi-plugin-properties']['nifi.ssl.truststore'],
      'nifi.ssl.truststoreType': config['configurations']['ranger-nifi-plugin-properties']['nifi.ssl.truststoreType'],
      'nifi.ssl.truststorePassword': config['configurations']['ranger-nifi-plugin-properties']['nifi.ssl.truststorePassword'],
      'nifi.ssl.use.default.context': config['configurations']['ranger-nifi-plugin-properties']['nifi.ssl.use.default.context'],
      'commonNameForCertificate': common_name_for_certificate
    }
  else:
    nifi_ranger_plugin_config = {
      'nifi.authentication': nifi_authentication,
      'nifi.ssl.use.default.context': "false",
      'nifi.url': format("https://{nifi_host_name}:{nifi_host_port}/nifi-api/resources"),
      'commonNameForCertificate': common_name_for_certificate
    }

  nifi_ranger_plugin_repo = {
    'isActive': 'true',
    'config': json.dumps(nifi_ranger_plugin_config),
    'description': 'nifi repo',
    'name': repo_name,
    'repositoryType': 'nifi',
    'assetType': '5'
  }

  # used in nifi authorizers
  ranger_admin_identity = ranger_id_owner_for_certificate

  if stack_supports_ranger_kerberos and security_enabled:
    nifi_ranger_plugin_config['policy.download.auth.users'] = nifi_user
    nifi_ranger_plugin_config['tag.download.auth.users'] = nifi_user
    ranger_nifi_principal = config['configurations']['nifi-properties']['nifi.kerberos.service.principal'].replace('_HOST',_hostname_lowercase)
    ranger_nifi_keytab = config['configurations']['nifi-properties']['nifi.kerberos.service.keytab.location']

  if stack_supports_ranger_kerberos:
    nifi_ranger_plugin_config['ambari.service.check.user'] = policy_user

    nifi_ranger_plugin_repo = {
      'isEnabled': 'true',
      'configs': nifi_ranger_plugin_config,
      'description': 'nifi repo',
      'name': repo_name,
      'type': 'nifi'
    }

  xa_audit_db_is_enabled = False
  ranger_audit_solr_urls = config['configurations']['ranger-admin-site']['ranger.audit.solr.urls']

  if xml_configurations_supported and stack_supports_ranger_audit_db:
    xa_audit_db_is_enabled = config['configurations']['ranger-nifi-audit']['xasecure.audit.destination.solr']

  xa_audit_hdfs_is_enabled =  default('/configurations/ranger-nifi-audit/xasecure.audit.destination.hdfs', False)


  #For SQLA explicitly disable audit to DB for Ranger
  if xa_audit_db_flavor == 'sqla':
    xa_audit_db_is_enabled = False

  nifi_authorizer = 'ranger-provider'

hdfs_user = config['configurations']['hadoop-env']['hdfs_user'] if has_namenode else None
hdfs_user_keytab = config['configurations']['hadoop-env']['hdfs_user_keytab'] if has_namenode else None
hdfs_principal_name = config['configurations']['hadoop-env']['hdfs_principal_name'] if has_namenode else None
hdfs_site = config['configurations']['hdfs-site'] if has_namenode else None
default_fs = config['configurations']['core-site']['fs.defaultFS'] if has_namenode else None
hadoop_bin_dir = stack_select.get_hadoop_dir("bin") if has_namenode else None
hadoop_conf_dir = conf_select.get_hadoop_conf_dir() if has_namenode else None

local_component_list = default("/localComponents", [])
has_hdfs_client_on_node = 'HDFS_CLIENT' in local_component_list

import functools
#create partial functions with common arguments for every HdfsResource call
#to create/delete hdfs directory/file/copyfromlocal we need to call params.HdfsResource in code
HdfsResource = functools.partial(
  HdfsResource,
  user=hdfs_user,
  hdfs_resource_ignore_file = "/var/lib/ambari-agent/data/.hdfs_resource_ignore",
  security_enabled = security_enabled,
  keytab = hdfs_user_keytab,
  kinit_path_local = kinit_path_local,
  hadoop_bin_dir = hadoop_bin_dir,
  hadoop_conf_dir = hadoop_conf_dir,
  principal_name = hdfs_principal_name,
  hdfs_site = hdfs_site,
  default_fs = default_fs,
  immutable_paths = get_not_managed_resources()
)
