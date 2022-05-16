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

from resource_management import *
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
import functools

# server configurations
config = Script.get_config()
stack_root = Script.get_stack_root()
tmp_dir = Script.get_tmp_dir()
stack_name = default("/clusterLevelParams/stack_name", None)
stack_version_buildnum = default("/commandParams/version", None)
if stack_name == "HDP":
    # Override HDP stack root
    stack_root = "/usr/hdf"
    # Override HDP stack version
    stack_version_buildnum = get_component_version_with_stack_selector("/usr/bin/hdf-select", "nifi-registry")
elif not stack_version_buildnum and stack_name:
    stack_version_buildnum = get_component_version_from_symlink(stack_name, "nifi-registry")

service_name = 'nifi-registry'
version_for_stack_feature_checks = get_stack_feature_version(config)

script_dir = os.path.dirname(__file__)
toolkit_files_dir = os.path.realpath(os.path.join(os.path.dirname(script_dir), 'files'))
toolkit_tmp_dir = tmp_dir

# Version being upgraded/downgraded to
version = default("/commandParams/version", None)
#upgrade direction
upgrade_direction = default("/commandParams/upgrade_direction", None)

nifi_registry_install_dir = os.path.join(stack_root, "current", "nifi-registry")

# params from nifi-registry-ambari-config
nifi_registry_initial_mem = config['configurations']['nifi-registry-ambari-config']['nifi.registry.initial_mem']
nifi_registry_max_mem = config['configurations']['nifi-registry-ambari-config']['nifi.registry.max_mem']


# note: nifi.registry.port and nifi.registry.port.ssl must be defined in same xml file for quicklinks to work
nifi_registry_port = config['configurations']['nifi-registry-ambari-config']['nifi.registry.port']
nifi_registry_ssl_port = config['configurations']['nifi-registry-ambari-config']['nifi.registry.port.ssl']

nifi_registry_internal_dir=config['configurations']['nifi-registry-ambari-config']['nifi.registry.internal.dir']
nifi_registry_internal_config_dir=config['configurations']['nifi-registry-ambari-config']['nifi.registry.internal.config.dir']
nifi_registry_internal_config_dir = nifi_registry_internal_config_dir.replace('{nifi_registry_internal_dir}', nifi_registry_internal_dir)

nifi_registry_config_dir= config['configurations']['nifi-registry-ambari-config']['nifi.registry.config.dir']
nifi_registry_config_dir = nifi_registry_config_dir.replace('{nifi_registry_install_dir}', nifi_registry_install_dir)

nifi_registry_database_dir=config['configurations']['nifi-registry-ambari-config']['nifi.registry.database.dir']
nifi_registry_database_dir = nifi_registry_database_dir.replace('{nifi_registry_internal_dir}', nifi_registry_internal_dir)

# password for encrypted config
nifi_registry_security_encrypt_configuration_password = config['configurations']['nifi-registry-ambari-config']['nifi.registry.security.encrypt.configuration.password']

master_configs = config['clusterHostInfo']
nifi_registry_master_hosts = master_configs['nifi_registry_master_hosts']

#nifi registry bootstrap file location
nifi_registry_bootstrap_file = nifi_registry_config_dir + '/bootstrap.conf'

nifi_registry_dir=nifi_registry_install_dir
bin_dir = os.path.join(*[nifi_registry_dir,'bin'])
lib_dir = os.path.join(*[nifi_registry_dir,'lib'])
docs_dir = os.path.join(*[nifi_registry_dir,'docs'])

nifi_ca_host = None
if 'nifi_ca_hosts' in master_configs:
    nifi_ca_hosts = master_configs['nifi_ca_hosts']
    if len(nifi_ca_hosts) > 0:
        nifi_ca_host = nifi_ca_hosts[0]

# params from nifi-registry-ambari-ssl-config
nifi_registry_ssl_enabled = config['configurations']['nifi-registry-ambari-ssl-config']['nifi.registry.ssl.isenabled']
nifi_registry_keystore = config['configurations']['nifi-registry-ambari-ssl-config']['nifi.registry.security.keystore']
nifi_registry_keystoreType = config['configurations']['nifi-registry-ambari-ssl-config']['nifi.registry.security.keystoreType']
nifi_registry_keystorePasswd = config['configurations']['nifi-registry-ambari-ssl-config']['nifi.registry.security.keystorePasswd']
nifi_registry_keyPasswd = config['configurations']['nifi-registry-ambari-ssl-config']['nifi.registry.security.keyPasswd']
nifi_registry_truststore = config['configurations']['nifi-registry-ambari-ssl-config']['nifi.registry.security.truststore']
nifi_registry_truststoreType = config['configurations']['nifi-registry-ambari-ssl-config']['nifi.registry.security.truststoreType']
nifi_registry_truststorePasswd = config['configurations']['nifi-registry-ambari-ssl-config']['nifi.registry.security.truststorePasswd']
nifi_registry_needClientAuth = config['configurations']['nifi-registry-ambari-ssl-config']['nifi.registry.security.needClientAuth']
nifi_registry_initial_admin_id = config['configurations']['nifi-registry-ambari-ssl-config']['nifi.registry.initial.admin.identity']
nifi_registry_ssl_config_content = config['configurations']['nifi-registry-ambari-ssl-config']['content']

#default keystore/truststore type if empty
nifi_registry_keystoreType = 'jks' if len(nifi_registry_keystoreType) == 0 else nifi_registry_keystoreType
nifi_registry_truststoreType = 'jks' if len(nifi_registry_truststoreType) == 0 else nifi_registry_truststoreType

#property that is set to hostname regardless of whether SSL enabled
nifi_registry_host = socket.getfqdn()

nifi_registry_truststore = nifi_registry_truststore.replace('{nifi_registry_ssl_host}',nifi_registry_host)
nifi_registry_keystore = nifi_registry_keystore.replace('{nifi_registry_ssl_host}',nifi_registry_host)

#populate properties whose values depend on whether SSL enabled
nifi_registry_keystore = nifi_registry_keystore.replace('{{nifi_registry_config_dir}}',nifi_registry_config_dir)
nifi_registry_truststore = nifi_registry_truststore.replace('{{nifi_registry_config_dir}}',nifi_registry_config_dir)

if nifi_registry_ssl_enabled:
    nifi_registry_ssl_host = nifi_registry_host
    nifi_registry_port = ""
else:
    nifi_registry_nonssl_host = nifi_registry_host
    nifi_registry_ssl_port = ""

# wrap this in a check to see if we have a ca host b/c otherwise nifi-ambari-ssl-config won't exist
# we use nifi-ambari-ssl-config to get the values for the CA so that they aren't duplicated in nifi-registry
if nifi_ca_host:
    nifi_ca_parent_config = config['configurations']['nifi-ambari-ssl-config']
    nifi_use_ca = nifi_ca_parent_config['nifi.toolkit.tls.token']
    nifi_toolkit_tls_token = nifi_ca_parent_config['nifi.toolkit.tls.token']
    nifi_toolkit_tls_helper_days = nifi_ca_parent_config['nifi.toolkit.tls.helper.days']
    nifi_toolkit_tls_port = nifi_ca_parent_config['nifi.toolkit.tls.port']
    nifi_toolkit_dn_prefix = nifi_ca_parent_config['nifi.toolkit.dn.prefix']
    nifi_toolkit_dn_suffix = nifi_ca_parent_config['nifi.toolkit.dn.suffix']

    nifi_ca_log_file_stdout = config['configurations']['nifi-registry-env']['nifi_registry_log_dir'] + '/nifi-ca.stdout'
    nifi_ca_log_file_stderr = config['configurations']['nifi-registry-env']['nifi_registry_log_dir'] + '/nifi-ca.stderr'

    stack_support_tls_toolkit_san = check_stack_feature('tls_toolkit_san', version_for_stack_feature_checks)

    nifi_ca_client_config = {
        "days" : int(nifi_toolkit_tls_helper_days),
        "keyStore" : nifi_registry_keystore,
        "keyStoreType" : nifi_registry_keystoreType,
        "keyStorePassword" : nifi_registry_keystorePasswd,
        "keyPassword" : nifi_registry_keyPasswd,
        "token" : nifi_toolkit_tls_token,
        "dn" : nifi_toolkit_dn_prefix + nifi_registry_host + nifi_toolkit_dn_suffix,
        "port" : int(nifi_toolkit_tls_port),
        "caHostname" : nifi_ca_host,
        "trustStore" : nifi_registry_truststore,
        "trustStoreType" : nifi_registry_truststoreType,
        "trustStorePassword": nifi_registry_truststorePasswd
    }

    if stack_support_tls_toolkit_san:
        nifi_ca_client_config["domainAlternativeNames"] = nifi_registry_host

# this comes from the registry side since regenerate is a separate operation for nifi and nifi-registry
nifi_toolkit_tls_regenerate = config['configurations']['nifi-registry-ambari-ssl-config']['nifi.toolkit.tls.regenerate']

# params from nifi-registry-env
nifi_registry_user = config['configurations']['nifi-registry-env']['nifi_registry_user']
nifi_registry_group = config['configurations']['nifi-registry-env']['nifi_registry_group']

nifi_registry_log_dir = config['configurations']['nifi-registry-env']['nifi_registry_log_dir']
nifi_registry_log_file = os.path.join(nifi_registry_log_dir,'nifi-registry-setup.log')

# params from nifi-registry-boostrap
nifi_registry_env_content = config_utils.merge_env(config['configurations']['nifi-registry-env'])

# params from nifi-registry-logback
nifi_registry_logback_content = config['configurations']['nifi-registry-logback-env']['content']

# params from nifi-registry-properties-env
nifi_registry_master_properties_content = config['configurations']['nifi-registry-master-properties-env']['content']
nifi_registry_properties = config['configurations']['nifi-registry-properties'].copy()

#kerberos params
nifi_registry_kerberos_authentication_expiration = config['configurations']['nifi-registry-properties']['nifi.registry.kerberos.spnego.authentication.expiration']
nifi_registry_kerberos_realm = default("/configurations/kerberos-env/realm", None)

# params from nifi-registry-authorizers-env
nifi_registry_authorizers_content = config['configurations']['nifi-registry-authorizers-env']['content']
nifi_registry_authorizers_dict = config['configurations']['nifi-registry-authorizers-env']
# params from nifi-registry-identity-providers-env
nifi_registry_identity_providers_content = config['configurations']['nifi-registry-identity-providers-env']['content']
nifi_registry_identity_providers_dict = config['configurations']['nifi-registry-identity-providers-env']
# params from nifi-registry-providers-env
nifi_registry_providers_content = config['configurations']['nifi-registry-providers-env']['content']
nifi_registry_providers_dict = config['configurations']['nifi-registry-providers-env']
# params from nifi-registry-boostrap
nifi_registry_boostrap_content = config_utils.merge_env(config['configurations']['nifi-registry-bootstrap-env'])

# params from nifi-toolkit-env
nifi_toolkit_java_options = config['configurations']['nifi-toolkit-env']['nifi_toolkit_java_options'] if 'nifi-toolkit-env' in config['configurations'] else '-Xms128m -Xmx256m'

#autodetect jdk home
jdk64_home=config['ambariLevelParams']['java_home']

nifi_registry_authorizer = 'managed-authorizer'

nifi_registry_host_name = config['agentLevelParams']['hostname']

java_home = config['ambariLevelParams']['java_home']
security_enabled = config['configurations']['cluster-env']['security_enabled']
smokeuser = config['configurations']['cluster-env']['smokeuser']
smokeuser_principal = config['configurations']['cluster-env']['smokeuser_principal_name']
smoke_user_keytab = config['configurations']['cluster-env']['smokeuser_keytab']
kinit_path_local = get_kinit_path(default('/configurations/kerberos-env/executable_search_paths', None))

stack_support_toolkit_update = check_stack_feature('toolkit_config_update', version_for_stack_feature_checks)
stack_support_admin_toolkit = check_stack_feature('admin_toolkit_support', version_for_stack_feature_checks)
stack_support_nifi_toolkit_package = check_stack_feature('nifi_toolkit_package', version_for_stack_feature_checks)
#some released HDP stacks will not have this stack feature, manually check
if not stack_support_nifi_toolkit_package and stack_name == "HDP":
    marker_script = os.path.join(stack_root, "current/nifi-toolkit/bin/tls-toolkit.sh")
    if sudo.path_isfile(marker_script):
        stack_support_nifi_toolkit_package = True

if security_enabled:
    _hostname_lowercase = nifi_registry_host.lower()
    nifi_registry_properties['nifi.registry.kerberos.spnego.principal'] = nifi_registry_properties['nifi.registry.kerberos.spnego.principal'].replace('_HOST',_hostname_lowercase)
    if 'nifi.registry.kerberos.service.principal' in nifi_registry_properties:
        nifi_registry_properties['nifi.registry.kerberos.service.principal'] = nifi_registry_properties['nifi.registry.kerberos.service.principal'].replace('_HOST',_hostname_lowercase)

#setup ranger configuration

retryAble = default("/commandParams/command_retry_enabled", False)
version = default("/commandParams/version", None)
namenode_hosts = default("/clusterHostInfo/namenode_host", None)

if isinstance(namenode_hosts, list):
    namenode_host = namenode_hosts[0]
else:
    namenode_host = namenode_hosts



stack_version_unformatted = config['clusterLevelParams']['stack_version']
stack_version_formatted = format_stack_version(stack_version_unformatted)
stack_supports_ranger_kerberos = stack_version_formatted and check_stack_feature(StackFeature.RANGER_KERBEROS_SUPPORT, stack_version_formatted)
stack_supports_ranger_audit_db = stack_version_formatted and check_stack_feature(StackFeature.RANGER_AUDIT_DB_SUPPORT, stack_version_formatted)

ranger_admin_hosts = default("/clusterHostInfo/ranger_admin_hosts", [])
has_ranger_admin = not len(ranger_admin_hosts) == 0
xml_configurations_supported = config['configurations']['ranger-env']['xml_configurations_supported']

ambari_server_hostname = config['clusterHostInfo']['ambari_server_host'][0]

# ranger nifi registry properties
policymgr_mgr_url = config['configurations']['admin-properties']['policymgr_external_url']

if 'admin-properties' in config['configurations'] and 'policymgr_external_url' in config['configurations']['admin-properties'] and policymgr_mgr_url.endswith('/'):
    policymgr_mgr_url = policymgr_mgr_url.rstrip('/')

xa_audit_db_name = config['configurations']['admin-properties']['audit_db_name']
xa_audit_db_user = config['configurations']['admin-properties']['audit_db_user']
xa_db_host = config['configurations']['admin-properties']['db_host']
repo_name = str(config['clusterName']) + '_nifi_registry'

repo_config_username = config['configurations']['ranger-nifi-registry-plugin-properties']['REPOSITORY_CONFIG_USERNAME']

ranger_env = config['configurations']['ranger-env']
ranger_plugin_properties = config['configurations']['ranger-nifi-registry-plugin-properties']
policy_user = config['configurations']['ranger-nifi-registry-plugin-properties']['policy_user']

#For curl command in ranger plugin to get db connector
jdk_location = config['ambariLevelParams']['jdk_location']
java_share_dir = '/usr/share/java'

ranger_nifi_registry_plugin_is_available = 'ranger-nifi-registry-plugin-properties' in config['configurations']
if has_ranger_admin and ranger_nifi_registry_plugin_is_available:
    enable_ranger_nifi_registry = (config['configurations']['ranger-nifi-registry-plugin-properties']['ranger-nifi-registry-plugin-enabled'].lower() == 'yes')
    xa_audit_db_password = unicode(config['configurations']['admin-properties']['audit_db_password']) if stack_supports_ranger_audit_db else None
    repo_config_password = unicode(config['configurations']['ranger-nifi-registry-plugin-properties']['REPOSITORY_CONFIG_PASSWORD'])
    xa_audit_db_flavor = config['configurations']['admin-properties']['DB_FLAVOR'].lower()
    previous_jdbc_jar_name = None

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
            if colon_count in (0, 2):
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

    driver_curl_target = format("{stack_root}/current/nifi-registry/ext/{jdbc_jar_name}") if stack_supports_ranger_audit_db else None
    previous_jdbc_jar = format("{stack_root}/current/nifi-registry/ext/{previous_jdbc_jar_name}") if stack_supports_ranger_audit_db else None

    ssl_keystore_password = unicode(config['configurations']['ranger-nifi-registry-policymgr-ssl']['xasecure.policymgr.clientssl.keystore.password']) if xml_configurations_supported else None
    ssl_truststore_password = unicode(config['configurations']['ranger-nifi-registry-policymgr-ssl']['xasecure.policymgr.clientssl.truststore.password']) if xml_configurations_supported else None
    credential_file = format('/etc/ranger/{repo_name}/cred.jceks') if xml_configurations_supported else None

    ranger_admin_username = config['configurations']['ranger-env']['ranger_admin_username']
    ranger_admin_password = config['configurations']['ranger-env']['ranger_admin_password']

    #create ranger service's nifi registry client properties
    nifi_registry_authentication = config['configurations']['ranger-nifi-registry-plugin-properties']['nifi.registry.authentication']
    ranger_id_owner_for_certificate = config['configurations']['ranger-nifi-registry-plugin-properties']['owner.for.certificate']
    nifi_registry_id_owner_for_certificate = config['configurations']['ranger-nifi-registry-policymgr-ssl']['owner.for.certificate']
    regex = r"(CN)=([a-zA-Z0-9\.\-\*\[\]\|\:]*)"
    match = re.search(regex, nifi_registry_id_owner_for_certificate)
    common_name_for_certificate = match.group(2) if match else 'NONE'

    if nifi_registry_authentication == 'SSL':

        nifi_registry_ranger_plugin_config = {
            'nifi.registry.authentication': nifi_registry_authentication,
            'nifi.registry.url': format("https://{nifi_registry_host_name}:{nifi_registry_ssl_port}/nifi-registry-api/policies/resources"),
            'nifi.registry.ssl.keystore': config['configurations']['ranger-nifi-registry-plugin-properties']['nifi.registry.ssl.keystore'],
            'nifi.registry.ssl.keystoreType':config['configurations']['ranger-nifi-registry-plugin-properties']['nifi.registry.ssl.keystoreType'],
            'nifi.registry.ssl.keystorePassword': config['configurations']['ranger-nifi-registry-plugin-properties']['nifi.registry.ssl.keystorePassword'],
            'nifi.registry.ssl.truststore': config['configurations']['ranger-nifi-registry-plugin-properties']['nifi.registry.ssl.truststore'],
            'nifi.registry.ssl.truststoreType': config['configurations']['ranger-nifi-registry-plugin-properties']['nifi.registry.ssl.truststoreType'],
            'nifi.registry.ssl.truststorePassword': config['configurations']['ranger-nifi-registry-plugin-properties']['nifi.registry.ssl.truststorePassword'],
            'commonNameForCertificate': common_name_for_certificate
        }
    else:
        nifi_registry_ranger_plugin_config = {
            'nifi.registry.authentication': nifi_registry_authentication,
            'nifi.registry.url': format("https://{nifi_registry_host_name}:{nifi_registry_port}/nifi-registry-api/policies/resources"),
            'commonNameForCertificate': common_name_for_certificate
        }

    nifi_registry_ranger_plugin_repo = {
        'isActive': 'true',
        'config': json.dumps(nifi_registry_ranger_plugin_config),
        'description': 'nifi-registry repo',
        'name': repo_name,
        'repositoryType': 'nifi-registry',
        'assetType': '5'
    }

    # used in nifi authorizers
    ranger_admin_identity = ranger_id_owner_for_certificate

    if stack_supports_ranger_kerberos and security_enabled:
        nifi_registry_ranger_plugin_config['policy.download.auth.users'] = nifi_registry_user
        nifi_registry_ranger_plugin_config['tag.download.auth.users'] = nifi_registry_user
        if 'nifi.registry.kerberos.service.principal' in config['configurations']['nifi-registry-properties']:
            ranger_nifi_registry_principal = config['configurations']['nifi-registry-properties']['nifi.registry.kerberos.service.principal'].replace('_HOST',_hostname_lowercase)
        if 'nifi.registry.kerberos.service.keytab.location' in config['configurations']['nifi-registry-properties']:
            ranger_nifi_registry_keytab = config['configurations']['nifi-registry-properties']['nifi.registry.kerberos.service.keytab.location']

    if stack_supports_ranger_kerberos:
        nifi_registry_ranger_plugin_config['ambari.service.check.user'] = policy_user

        nifi_registry_ranger_plugin_repo = {
            'isEnabled': 'true',
            'configs': nifi_registry_ranger_plugin_config,
            'description': 'nifi-registry repo',
            'name': repo_name,
            'type': 'nifi-registry'
        }

    xa_audit_db_is_enabled = False
    ranger_audit_solr_urls = config['configurations']['ranger-admin-site']['ranger.audit.solr.urls']

    if xml_configurations_supported and stack_supports_ranger_audit_db:
        xa_audit_db_is_enabled = config['configurations']['ranger-nifi-registry-audit']['xasecure.audit.destination.db']

    xa_audit_hdfs_is_enabled = default('/configurations/ranger-nifi-registry-audit/xasecure.audit.destination.hdfs', False)


    #For SQLA explicitly disable audit to DB for Ranger
    if xa_audit_db_flavor == 'sqla':
        xa_audit_db_is_enabled = False

    if enable_ranger_nifi_registry:
        nifi_registry_authorizer = 'ranger-authorizer'

has_namenode = not namenode_host == None
hdfs_user = config['configurations']['hadoop-env']['hdfs_user'] if has_namenode else None
hdfs_user_keytab = config['configurations']['hadoop-env']['hdfs_user_keytab'] if has_namenode else None
hdfs_principal_name = config['configurations']['hadoop-env']['hdfs_principal_name'] if has_namenode else None
hdfs_site = config['configurations']['hdfs-site'] if has_namenode else None
default_fs = config['configurations']['core-site']['fs.defaultFS'] if has_namenode else None
hadoop_bin_dir = stack_select.get_hadoop_dir("bin") if has_namenode else None
hadoop_conf_dir = conf_select.get_hadoop_conf_dir() if has_namenode else None

local_component_list = default("/localComponents", [])
has_hdfs_client_on_node = 'HDFS_CLIENT' in local_component_list

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
