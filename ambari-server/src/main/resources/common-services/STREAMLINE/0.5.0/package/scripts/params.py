#!/usr/bin/python2
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
from resource_management.libraries.functions import format
from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.version import format_stack_version
from resource_management.libraries.functions import StackFeature
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.stack_features import get_stack_feature_version
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.get_stack_version import get_stack_version
from resource_management.libraries.functions.is_empty import is_empty
from resource_management.libraries.resources.hdfs_resource import HdfsResource
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions import conf_select
from resource_management.libraries.functions import get_kinit_path
from resource_management.libraries.functions.get_not_managed_resources import get_not_managed_resources
from resource_management.libraries.functions.setup_ranger_plugin_xml import get_audit_configs
from resource_management.core.source import InlineTemplate
from resource_management.core.logger import Logger
from resource_management.core.exceptions import Fail
from utils import get_bare_principal

import config_utils
import ambari_simplejson as json # simplejson is much faster comparing to Python 2.6 json module and has the same functions set

# server configurations
config = Script.get_config()
tmp_dir = Script.get_tmp_dir()
stack_root = Script.get_stack_root()
stack_name = default("/clusterLevelParams/stack_name", None)
if stack_name == "HDP":
  # Override HDP stack root
  stack_root = "/usr/hdf"
retryAble = default("/commandParams/command_retry_enabled", False)

# Version being upgraded/downgraded to
version = default("/commandParams/version", None)

stack_version_unformatted = config['clusterLevelParams']['stack_version']
stack_version_formatted = format_stack_version(stack_version_unformatted)
upgrade_direction = default("/commandParams/upgrade_direction", None)
security_enabled = config['configurations']['cluster-env']['security_enabled']
kinit_path_local = get_kinit_path(default('/configurations/kerberos-env/executable_search_paths', None))

# get the correct version to use for checking stack features
version_for_stack_feature_checks = get_stack_feature_version(config)

# When downgrading the 'version' and 'current_version' are both pointing to the downgrade-target version
# downgrade_from_version provides the source-version the downgrade is happening from
downgrade_from_version = default("/commandParams/downgrade_from_version", None)

hostname = config['agentLevelParams']['hostname']

# default streamline parameters
streamline_home = os.path.join(stack_root, "current", "streamline")
streamline_bin = os.path.join(streamline_home, "bin", "streamline")
streamline_bootstrap_dir = os.path.join(streamline_home, "bootstrap")
streamline_libs = os.path.join(streamline_home, "libs")
streamline_managed_log_dir = os.path.join(streamline_home, "logs")
conf_dir = os.path.join(streamline_home, "conf")

limits_conf_dir = "/etc/security/limits.d"

streamline_user_nofile_limit = default('/configurations/streamline-env/streamline_user_nofile_limit', 65536)
streamline_user_nproc_limit = default('/configurations/streamline-env/streamline_user_nproc_limit', 65536)

streamline_user = config['configurations']['streamline-env']['streamline_user']
streamline_log_dir = config['configurations']['streamline-env']['streamline_log_dir']
streamline_log_maxbackupindex = config['configurations']['streamline-log4j']['streamline_log_maxbackupindex']
streamline_log_maxfilesize = config['configurations']['streamline-log4j']['streamline_log_maxfilesize']
streamline_log_template = config['configurations']['streamline-log4j']['content']
streamline_log_template = streamline_log_template.replace('{{streamline_log_dir}}', streamline_log_dir)
streamline_log_template = streamline_log_template.replace('{{streamline_log_maxbackupindex}}', streamline_log_maxbackupindex)
streamline_log_template = streamline_log_template.replace('{{streamline_log_maxfilesize}}', ("%sMB" % streamline_log_maxfilesize))

streamline_port = config['configurations']['streamline-common']['port']
streamline_admin_port = config['configurations']['streamline-common']['adminPort']

# This is hardcoded on the streamline bash process lifecycle on which we have no control over
streamline_managed_pid_dir = "/var/run/streamline"
streamine_managed_log_dir = "/var/log/streamline"

user_group = config['configurations']['cluster-env']['user_group']
java64_home = config['ambariLevelParams']['java_home']
streamline_env_sh_template = config_utils.merge_env(config['configurations']['streamline-env'])
streamline_jaas_conf_template = default("/configurations/streamline_jaas_conf/content", None)

if security_enabled:
  smokeuser = config['configurations']['cluster-env']['smokeuser']
  smokeuser_principal = config['configurations']['cluster-env']['smokeuser_principal_name']
  smoke_user_keytab = config['configurations']['cluster-env']['smokeuser_keytab']
  _hostname_lowercase = config['agentLevelParams']['hostname'].lower()
  _streamline_principal_name = config['configurations']['streamline-env']['streamline_principal_name']
  streamline_jaas_principal = _streamline_principal_name.replace('_HOST',_hostname_lowercase)
  streamline_bare_principal = get_bare_principal(streamline_jaas_principal)
  streamline_keytab_path = config['configurations']['streamline-env']['streamline_keytab']
  streamline_ui_keytab_path = config['configurations']['streamline-env']['streamline_ui_keytab']
  _streamline_ui_jaas_principal_name = config['configurations']['streamline-env']['streamline_ui_principal_name']
  streamline_ui_jaas_principal = _streamline_ui_jaas_principal_name.replace('_HOST',_hostname_lowercase)
  streamline_kerberos_params = "-Djava.security.auth.login.config="+ conf_dir +"/streamline_jaas.conf"
  streamline_servlet_filter = config['configurations']['streamline-common']['servlet.filter']
  streamline_servlet_kerberos_name_rules = config['configurations']['streamline-common']['kerberos.name.rules']
  streamline_servlet_token_validity = config['configurations']['streamline-common']['token.validity']
  streamline_kerberos_params = "-Djava.security.auth.login.config="+ conf_dir +"/streamline_jaas.conf"
  streamline_authorizer_class = config['configurations']['streamline-common']['authorizer.class.name']
  streamline_admin_principals = config['configurations']['streamline-common']['admin.principals'].replace("{{streamline_bare_principal}}", streamline_bare_principal)
  streamline_ticket_renew_window_factor = config['configurations']['streamline-common']['ticket.renew.window.factor']
  streamline_ticket_renew_jitter = config['configurations']['streamline-common']['ticket.renew.jitter']
  streamline_min_time_before_login = config['configurations']['streamline-common']['min.time.before.login']
else:
  streamline_kerberos_params = ''

#SSO related configs
if 'streamline-sso-config' in config['configurations']:
  streamline_sso_enabled = config['configurations']['streamline-sso-config']['streamline.sso.enabled']
  streamline_authentication_provider_url = config['configurations']['streamline-sso-config']['streamline.authentication.provider.url']
  streamline_public_key_pem = config['configurations']['streamline-sso-config']['streamline.public.key.pem']
  streamline_authentication_sso_token_validity = config['configurations']['streamline-sso-config']['streamline.token.validity']
else:
  streamline_sso_enabled = False

#SSL related configs
if 'streamline-ssl-config' in config['configurations']:
  streamline_ssl_enabled = config['configurations']['streamline-ssl-config']['streamline.ssl.isenabled']
  streamline_ssl_port = config['configurations']['streamline-common']['streamline.ssl.port']
  streamline_ssl_adminPort = config['configurations']['streamline-common']['streamline.ssl.adminPort']
  streamline_keyStorePath = config['configurations']['streamline-ssl-config']['streamline.keyStorePath']
  streamline_keyStorePassword = config['configurations']['streamline-ssl-config']['streamline.keyStorePassword']
  streamline_keyStoreType = config['configurations']['streamline-ssl-config']['streamline.keyStoreType']
  streamline_trustStorePath = config['configurations']['streamline-ssl-config']['streamline.trustStorePath']
  streamline_trustStorePassword = config['configurations']['streamline-ssl-config']['streamline.trustStorePassword']
  streamline_trustStoreType = config['configurations']['streamline-ssl-config']['streamline.trustStoreType']
  streamline_validateCerts = config['configurations']['streamline-ssl-config']['streamline.validateCerts']
  streamline_validatePeers = config['configurations']['streamline-ssl-config']['streamline.validatePeers']
else:
  streamline_ssl_enabled = False

if streamline_ssl_enabled:
  streamline_catalog_root_url = 'https://{0}:{1}/api/v1/catalog'.format(hostname, streamline_ssl_port)
else:
  streamline_catalog_root_url = 'http://{0}:{1}/api/v1/catalog'.format(hostname,streamline_port)

enable_atlas_hook = default('/configurations/storm-env/storm.atlas.hook', False)

# flatten streamline configs
storm_client_home = config['configurations']['streamline-common']['storm.client.home']
registry_url = config['configurations']['streamline-common']['registry.url']
maven_repo_url = config['configurations']['streamline-common']['maven.repo.url']
jar_storage_type = config['configurations']['streamline-common']['jar.storage.type']
jar_storage_hdfs_url = config['configurations']['streamline-common']['jar.storage.hdfs.url']
jar_storage = config['configurations']['streamline-common']['jar.storage']
jar_remote_storage_enabled  = False
jar_db_storage_enabled = False

stack_support_sam_storage_core_in_registry = check_stack_feature('sam_storage_core_in_registry', version_for_stack_feature_checks)
stack_support_sam_db_file_storage = check_stack_feature('sam_db_file_storage', version_for_stack_feature_checks)
stack_streamline_support_db_user_creation = check_stack_feature('streamline_support_db_user_creation', version_for_stack_feature_checks)

if stack_support_sam_storage_core_in_registry:
  jar_storage_class = "com.hortonworks.registries.common.util.LocalFileSystemStorage"
else:
  jar_storage_class = "com.hortonworks.streamline.common.util.LocalFileSystemStorage"

if jar_storage_type is not None and jar_storage_type == "hdfs":
  if stack_support_sam_storage_core_in_registry:
    jar_storage_class = "com.hortonworks.registries.common.util.HdfsFileStorage"
  else:
    jar_storage_class = "com.hortonworks.streamline.common.util.HdfsFileStorage"
  jar_remote_storage_enabled = True
elif jar_storage_type is not None and stack_support_sam_db_file_storage and jar_storage_type == "database":
  jar_storage_class = "com.hortonworks.registries.storage.filestorage.DbFileStorage"
  jar_db_storage_enabled = True

if 'topology.test.results.dir' in config['configurations']['streamline-common']:
  topology_test_results = config['configurations']['streamline-common']['topology.test.results.dir']
else:
  topology_test_results = "/hdf/streamline/topology_test_results"

streamline_dashboard_url = config['configurations']['streamline-common']['streamline.dashboard.url']

#Check if superset is installed. If yes, use superset webserver address and port number or use default values.
try:
  superset_server_hostname_list = config['clusterHostInfo']['superset_hosts']
  superset_server_hostname = superset_server_hostname_list[0]
  streamline_dashboard_url = streamline_dashboard_url.replace("{{superset_host}}", superset_server_hostname).replace("{{superset_port}}", config['configurations']['superset']['SUPERSET_WEBSERVER_PORT'])
except Exception, e:
  Logger.info("Superset is not installed")
  streamline_dashboard_url = streamline_dashboard_url.replace("{{superset_host}}", "localhost").replace("{{superset_port}}", "9088")

#Check if registry is installed. If yes, use registry hostname and port number or use default values.
try:
  registry_server_hostname_list = config['clusterHostInfo']['registry_server_hosts']
  #In case of HA, for now, we would take first hostname
  registry_server_hostname = registry_server_hostname_list[0]
  registry_url = registry_url.replace("{{registry_host}}", registry_server_hostname).replace("{{registry_port}}" , config['configurations']['registry-common']['port'])
except Exception, e:
  Logger.info("Registry is not installed")
  registry_url = registry_url.replace("{{registry_host}}", "localhost").replace("{{registry_port}}", "7788")

streamline_storage_type = str(config['configurations']['streamline-common']['streamline.storage.type']).lower()
streamline_storage_database = str(config['configurations']['streamline-common']['database_name'])
streamline_storage_connector_connectorURI = config['configurations']['streamline-common']['streamline.storage.connector.connectURI']
streamline_storage_connector_user = config['configurations']['streamline-common']['streamline.storage.connector.user']
streamline_storage_connector_password = config['configurations']['streamline-common']['streamline.storage.connector.password']
streamline_storage_query_timeout = config['configurations']['streamline-common']['streamline.storage.query.timeout']
streamline_storage_java_class = "com.mysql.jdbc.jdbc2.optional.MysqlDataSource"

# database admin properties.
if stack_streamline_support_db_user_creation:
  database_create_db_dbuser = str(config['configurations']['streamline-env']['create_db_dbuser']).lower()
  database_admin_user_name = config['configurations']['streamline-common']['db_root_user']
  database_admin_password = config['configurations']['streamline-common']['db_root_password']
  database_admin_jdbc_url = config['configurations']['streamline-common']['db_root_jdbc_url']

if streamline_storage_type == "postgresql":
  streamline_storage_java_class = "org.postgresql.ds.PGSimpleDataSource"
elif streamline_storage_type == "oracle":
  streamline_storage_java_class = "oracle.jdbc.pool.OracleDataSource"
else:
  streamline_storage_java_class = "com.mysql.jdbc.jdbc2.optional.MysqlDataSource"


#Http Proxy Configs for HDF-3.2 onwards
stack_sam_support_httpProxy = check_stack_feature('sam_support_httpProxy', version_for_stack_feature_checks)
if stack_sam_support_httpProxy:
  if 'httpProxyServer' in config['configurations']['streamline-common']:
    http_proxy_server = config['configurations']['streamline-common']['httpProxyServer']
  else:
    http_proxy_server = None

  if 'httpProxyUsername' in config['configurations']['streamline-common']:
    http_proxy_username = config['configurations']['streamline-common']['httpProxyUsername']
  else:
    http_proxy_username = None

  if 'httpProxyPassword' in config['configurations']['streamline-common']:
    http_proxy_password = config['configurations']['streamline-common']['httpProxyPassword']
  else:
    http_proxy_password = None
else:
  http_proxy_server = None
  http_proxy_username = None
  http_proxy_password = None

# mysql jar
jdk_location = config['ambariLevelParams']['jdk_location']
if 'mysql' == streamline_storage_type:
  jdbc_driver_jar = default("/ambariLevelParams/custom_mysql_jdbc_name", None)
  if jdbc_driver_jar == None:
    Logger.error("Failed to find mysql-java-connector jar. Make sure you followed the steps to register mysql driver")
    Logger.info("Users should register the mysql java driver jar.")
    Logger.info("yum install mysql-connector-java*")
    Logger.info("sudo ambari-server setup --jdbc-db=mysql --jdbc-driver=/usr/share/java/mysql-connector-java.jar")
    raise Fail('Unable to establish jdbc connection to your ' + streamline_storage_type + ' instance.')

if 'oracle' == streamline_storage_type:
  jdbc_driver_jar = default("/ambariLevelParams/custom_oracle_jdbc_name", None)
  if jdbc_driver_jar == None:
    Logger.error("Failed to find ojdbc jar. Please download and make sure you followed the steps to register oracle driver")
    Logger.info("Users should register the oracle ojdbc driver jar.")
    Logger.info("Create a symlink e.g. ln -s /usr/share/java/ojdbc6.jar /usr/share/java/ojdbc.jar")
    Logger.info("sudo ambari-server setup --jdbc-db=oracle --jdbc-driver=/usr/share/java/ojdbc.jar")
    raise Fail('Unable to establish jdbc connection to your ' + streamline_storage_type + ' instance.')

if 'mysql' == streamline_storage_type or 'oracle' == streamline_storage_type:
  connector_curl_source = format("{jdk_location}/{jdbc_driver_jar}")
  connector_download_dir=format("{streamline_home}/libs")
  connector_bootstrap_download_dir=format("{streamline_home}/bootstrap/lib")
  downloaded_custom_connector = format("{tmp_dir}/{jdbc_driver_jar}")


check_db_connection_jar_name = "DBConnectionVerification.jar"
check_db_connection_jar = format("/usr/lib/ambari-agent/{check_db_connection_jar_name}")

# bootstrap commands
jdk64_home=config['ambariLevelParams']['java_home']
bootstrap_storage_command = os.path.join(streamline_home, "bootstrap", "bootstrap-storage.sh")
bootstrap_storage_run_cmd = format('export JAVA_HOME={jdk64_home} ; source {conf_dir}/streamline-env.sh ; {bootstrap_storage_command}')

bootstrap_command = os.path.join(streamline_home, "bootstrap", "bootstrap.sh")
bootstrap_run_cmd = format('export JAVA_HOME={jdk64_home} ; source {conf_dir}/streamline-env.sh ; {bootstrap_command}')

bootstrap_storage_intienv_command = os.path.join(streamline_home, "bootstrap", "bootstrap-storage-initenv.sh")
bootstrap_storage_initevn_run_cmd = format('export JAVA_HOME={jdk64_home} ; source {conf_dir}/streamline-env.sh ; {bootstrap_storage_intienv_command}')

bootstrap_file = "/var/lib/ambari-agent/data/streamline/bootstrap_done"
streamline_agent_dir = "/var/lib/ambari-agent/data/streamline"

stack_support_sam_storage_core_in_registry = check_stack_feature('sam_storage_core_in_registry', version_for_stack_feature_checks)
stack_sam_support_schema_migrate = check_stack_feature('sam_support_schema_migrate', version_for_stack_feature_checks)


if stack_support_sam_storage_core_in_registry:
  storage_provider_class = "com.hortonworks.registries.storage.impl.jdbc.JdbcStorageManager"
else:
  storage_provider_class = "com.hortonworks.streamline.storage.impl.jdbc.JdbcStorageManager"