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
from resource_management.core.logger import Logger
from resource_management.core.exceptions import Fail
import ambari_simplejson as json # simplejson is much faster comparing to Python 2.6 json module and has the same functions set

import config_utils

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
smokeuser = config['configurations']['cluster-env']['smokeuser']
smokeuser_principal = config['configurations']['cluster-env']['smokeuser_principal_name']
smoke_user_keytab = config['configurations']['cluster-env']['smokeuser_keytab']

# get the correct version to use for checking stack features
version_for_stack_feature_checks = get_stack_feature_version(config)

# get rewrite stack support
stack_support_rewrite_uri = check_stack_feature('registry_rewriteuri_filter_support', version_for_stack_feature_checks)
stack_support_allowed_resources = check_stack_feature('registry_allowed_resources_support', version_for_stack_feature_checks)
stack_support_remove_rootpath = check_stack_feature('registry_remove_rootpath', version_for_stack_feature_checks)
stack_registry_support_schema_migrate = check_stack_feature('registry_support_schema_migrate', version_for_stack_feature_checks)
stack_registry_support_db_user_creation = check_stack_feature('registry_support_db_user_creation', version_for_stack_feature_checks)

# When downgrading the 'version' and 'current_version' are both pointing to the downgrade-target version
# downgrade_from_version provides the source-version the downgrade is happening from
downgrade_from_version = default("/commandParams/downgrade_from_version", None)

hostname = config['agentLevelParams']['hostname']

# default registry parameters
registry_home = os.path.join(stack_root, "current", "registry")
registry_bin = os.path.join(registry_home, "bin", "registry")

registry_managed_log_dir = os.path.join(registry_home, "logs")
conf_dir = os.path.join(registry_home, "conf")

limits_conf_dir = "/etc/security/limits.d"

registry_user_nofile_limit = default('/configurations/registry-env/registry_user_nofile_limit', 65536)
registry_user_nproc_limit = default('/configurations/registry-env/registry_user_nproc_limit', 65536)

registry_user = config['configurations']['registry-env']['registry_user']
registry_log_dir = config['configurations']['registry-env']['registry_log_dir']

# This is hardcoded on the registry bash process lifecycle on which we have no control over
registry_managed_pid_dir = "/var/run/registry"

user_group = config['configurations']['cluster-env']['user_group']
java64_home = config['ambariLevelParams']['java_home']
registry_env_sh_template = config_utils.merge_env(config['configurations']['registry-env'])

if security_enabled:
  _hostname_lowercase = config['agentLevelParams']['hostname'].lower()
  registry_ui_keytab_path = config['configurations']['registry-env']['registry_ui_keytab']
  _registry_ui_jaas_principal_name = config['configurations']['registry-env']['registry_ui_principal_name']
  registry_ui_jaas_principal = _registry_ui_jaas_principal_name.replace('_HOST',_hostname_lowercase)
  registry_kerberos_params = "-Djava.security.auth.login.config="+ conf_dir +"/registry_jaas.conf"
  registry_servlet_filter = config['configurations']['registry-common']['servlet.filter']
  registry_servlet_kerberos_name_rules = config['configurations']['registry-common']['kerberos.name.rules']
  #registry_servlet_token_validity = (config['configurations']['registry-common']['token.validity'])
  registry_servlet_token_validity = 36000
  if 'registry.kerberos.non-browser.user-agents' in config['configurations']['registry-common']:
    registry_kerberos_non_browser_user_agents = config['configurations']['registry-common']['registry.kerberos.non-browser.user-agents']
  else:
    registry_kerberos_non_browser_user_agents = None

#SSL related configs
if 'registry-ssl-config' in config['configurations']:
  registry_ssl_enabled = config['configurations']['registry-ssl-config']['registry.ssl.isenabled']
  registry_ssl_port = config['configurations']['registry-common']['registry.ssl.port']
  registry_ssl_adminPort = config['configurations']['registry-common']['registry.ssl.adminPort']
  registry_keyStorePath = config['configurations']['registry-ssl-config']['registry.keyStorePath']
  registry_keyStorePassword = config['configurations']['registry-ssl-config']['registry.keyStorePassword']
  registry_keyStoreType = config['configurations']['registry-ssl-config']['registry.keyStoreType']
  registry_trustStorePath = config['configurations']['registry-ssl-config']['registry.trustStorePath']
  registry_trustStorePassword = config['configurations']['registry-ssl-config']['registry.trustStorePassword']
  registry_trustStoreType = config['configurations']['registry-ssl-config']['registry.trustStoreType']
  registry_validateCerts = config['configurations']['registry-ssl-config']['registry.validateCerts']
  registry_validatePeers = config['configurations']['registry-ssl-config']['registry.validatePeers']
else:
  registry_ssl_enabled = False

#SSO related configs
if 'registry-sso-config' in config['configurations']:
  registry_sso_enabled = config['configurations']['registry-sso-config']['registry.sso.enabled']
  registry_authentication_provider_url = config['configurations']['registry-sso-config']['registry.authentication.provider.url']
  registry_public_key_pem = config['configurations']['registry-sso-config']['registry.public.key.pem']
  registry_authentication_sso_token_validity = config['configurations']['registry-sso-config']['registry.token.validity']
  registry_servlet_filter = "com.hortonworks.registries.auth.server.AuthenticationFilter"

  #JWT cookie params
  registry_sso_expected_jwt_audiences = config['configurations']['registry-sso-config']['registry.expected.jwt.audiences']
  registry_sso_jwt_cookie_name = config['configurations']['registry-sso-config']['registry.jwt.cookie.name']
  registry_sso_cookie_path = config['configurations']['registry-sso-config']['registry.cookie.path']
  registry_sso_cookie_domain = config['configurations']['registry-sso-config']['registry.cookie.domain']
  registry_sso_non_brower_user_agents = config['configurations']['registry-sso-config']['registry.sso.non-browser.user-agents']
else:
  registry_sso_enabled = False

registry_log_dir = config['configurations']['registry-env']['registry_log_dir']
registry_log_maxbackupindex = config['configurations']['registry-log4j']['registry_log_maxbackupindex']
registry_log_maxfilesize = config['configurations']['registry-log4j']['registry_log_maxfilesize']
registry_log_template = config['configurations']['registry-log4j']['content']
registry_log_template = registry_log_template.replace('{{registry_log_dir}}', registry_log_dir)
registry_log_template = registry_log_template.replace('{{registry_log_maxbackupindex}}', registry_log_maxbackupindex)
registry_log_template = registry_log_template.replace('{{registry_log_maxfilesize}}', ("%sMB" % registry_log_maxfilesize))

# flatten registry configs
jar_storage = config['configurations']['registry-common']['jar.storage']
registry_storage_database = str(config['configurations']['registry-common']['database_name'])
registry_storage_type = str(config['configurations']['registry-common']['registry.storage.type']).lower()
registry_storage_connector_connectorURI = config['configurations']['registry-common']['registry.storage.connector.connectURI']
registry_storage_connector_user = config['configurations']['registry-common']['registry.storage.connector.user']
registry_storage_connector_password = config['configurations']['registry-common']['registry.storage.connector.password']
registry_storage_query_timeout = config['configurations']['registry-common']['registry.storage.query.timeout']
registry_storage_java_class = "com.mysql.jdbc.jdbc2.optional.MysqlDataSource"

# database admin properties.
if stack_registry_support_db_user_creation:
  database_create_db_dbuser = str(config['configurations']['registry-env']['create_db_dbuser']).lower()
  database_admin_user_name = config['configurations']['registry-common']['db_root_user']
  database_admin_password = config['configurations']['registry-common']['db_root_password']
  database_admin_jdbc_url = config['configurations']['registry-common']['db_root_jdbc_url']

jar_storage_type = config['configurations']['registry-common']['jar.storage.type']
jar_storage_hdfs_url = config['configurations']['registry-common']['jar.storage.hdfs.url']
jar_storage = config['configurations']['registry-common']['jar.storage']
jar_storage_class = "com.hortonworks.registries.common.util.LocalFileSystemStorage"
jar_remote_storage_enabled  = False


if jar_storage_type != None and jar_storage_type == "hdfs":
  jar_storage_class = "com.hortonworks.registries.common.util.HdfsFileStorage"
  jar_remote_storage_enabled = True


if registry_storage_type == "postgresql":
  registry_storage_java_class = "org.postgresql.ds.PGSimpleDataSource"
elif registry_storage_type == "oracle":
  registry_storage_java_class = "oracle.jdbc.pool.OracleDataSource"
else:
  registry_storage_java_class = "com.mysql.jdbc.jdbc2.optional.MysqlDataSource"


registry_port = config['configurations']['registry-common']['port']
registry_admin_port = config['configurations']['registry-common']['adminPort']

#Http Proxy Configs
if 'httpProxyServer' in config['configurations']['registry-common']:
  http_proxy_server = config['configurations']['registry-common']['httpProxyServer']
else:
  http_proxy_server = None

if 'httpProxyUsername' in config['configurations']['registry-common']:
  http_proxy_username = config['configurations']['registry-common']['httpProxyUsername']
else:
  http_proxy_username = None

if 'httpProxyPassword' in config['configurations']['registry-common']:
  http_proxy_password = config['configurations']['registry-common']['httpProxyPassword']
else:
  http_proxy_password = None


registry_schema_cache_size = config['configurations']['registry-common']['registry.schema.cache.size']
registry_schema_cache_expiry_interval = config['configurations']['registry-common']['registry.schema.cache.expiry.interval']


# mysql jar
jdk_location = config['ambariLevelParams']['jdk_location']
if 'mysql' == registry_storage_type:
  jdbc_driver_jar = default("/ambariLevelParams/custom_mysql_jdbc_name", None)
  if jdbc_driver_jar == None:
    Logger.error("Failed to find mysql-java-connector jar. Make sure you followed the steps to register mysql driver")
    Logger.info("Users should register the mysql java driver jar.")
    Logger.info("yum install mysql-connector-java*")
    Logger.info("sudo ambari-server setup --jdbc-db=mysql --jdbc-driver=/usr/share/java/mysql-connector-java.jar")
    raise Fail('Unable to establish jdbc connection to your ' + registry_storage_type + ' instance.')

if 'oracle' == registry_storage_type:
  jdbc_driver_jar = default("/ambariLevelParams/custom_oracle_jdbc_name", None)
  if jdbc_driver_jar == None:
    Logger.error("Failed to find ojdbc jar. Please download and sure you followed the steps to register oracle driver")
    Logger.info("Users should register the oracle java driver jar.")
    Logger.info("Create a symlink e.g. ln -s /usr/share/java/ojdbc6.jar /usr/share/java/ojdbc.jar")
    Logger.info("sudo ambari-server setup --jdbc-db=oracle --jdbc-driver=/usr/share/java/ojdbc.jar")
    raise Fail('Unable to establish jdbc connection to your ' + registry_storage_type + ' instance.')

if 'mysql' == registry_storage_type or 'oracle' == registry_storage_type:
  connector_curl_source = format("{jdk_location}/{jdbc_driver_jar}")
  connector_download_dir=format("{registry_home}/libs")
  connector_bootstrap_download_dir=format("{registry_home}/bootstrap/lib")
  downloaded_custom_connector = format("{tmp_dir}/{jdbc_driver_jar}")
  
check_db_connection_jar_name = "DBConnectionVerification.jar"
check_db_connection_jar = format("/usr/lib/ambari-agent/{check_db_connection_jar_name}")

# bootstrap commands
jdk64_home=config['ambariLevelParams']['java_home']
bootstrap_storage_command = os.path.join(registry_home, "bootstrap", "bootstrap-storage.sh")
bootstrap_storage_run_cmd = format('export JAVA_HOME={jdk64_home} ; source {conf_dir}/registry-env.sh ; {bootstrap_storage_command}')

bootstrap_storage_intienv_command = os.path.join(registry_home, "bootstrap", "bootstrap-storage-initenv.sh")
bootstrap_storage_initevn_run_cmd = format('export JAVA_HOME={jdk64_home} ; source {conf_dir}/registry-env.sh ; {bootstrap_storage_intienv_command}')

registry_agent_dir = "/var/lib/ambari-agent/data/registry"