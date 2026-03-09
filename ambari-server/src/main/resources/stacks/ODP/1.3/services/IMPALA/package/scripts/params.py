#!/usr/bin/env ambari-python-wrap
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

from resource_management.libraries.script.script import Script
from resource_management.libraries.functions import conf_select
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions import get_kinit_path
from resource_management.libraries.functions.expect import expect
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.is_empty import is_empty
from resource_management.libraries.resources.hdfs_resource import HdfsResource
from resource_management.libraries.functions.setup_ranger_plugin_xml import generate_ranger_service_config
from resource_management.libraries.functions.get_not_managed_resources import get_not_managed_resources
import os, socket

script_dir = os.path.dirname(os.path.realpath(__file__))
files_dir = os.path.join(os.path.dirname(script_dir), 'files')


def _as_bool(value):
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() == "true"


config = Script.get_config()
tmp_dir = Script.get_tmp_dir()
stack_root = Script.get_stack_root()

java_home = config['ambariLevelParams']['java_home']
java_version = expect("/ambariLevelParams/java_version", int)
ambari_java_home = config['ambariLevelParams']['ambari_java_home']
ambari_java_exec = format("{ambari_java_home}/bin/java")
impala_home_statestore = os.path.join(stack_root,  "current", "impala-state-store")
impala_home_catalog = os.path.join(stack_root,  "current", "impala-catalog-service")
impala_home_daemon = os.path.join(stack_root,  "current", "impala-daemon")

def _host_aliases(host):
    aliases = set()
    if host:
        lowered = host.lower()
        aliases.add(lowered)
        aliases.add(lowered.split('.')[0])
    return aliases


def _resolve_current_host(hosts, *candidates):
    for candidate in candidates:
        candidate_aliases = _host_aliases(candidate)
        if not candidate_aliases:
            continue
        for host in hosts:
            if candidate_aliases & _host_aliases(host):
                return host
    return hosts[0] if hosts else None


def _peer_host(hosts, current_host):
    if len(hosts) < 2:
        return current_host
    for host in hosts:
        if host != current_host:
            return host
    return hosts[0]


# server configurations
config = Script.get_config()
tmp_dir = Script.get_tmp_dir()
stack_root = Script.get_stack_root()
stack_name = default("/hostLevelParams/stack_name", None)
impala_env = config['configurations']['impala-env']
impala_user = config['configurations']['impala-env']['impala_user']
impala_group = config['configurations']['impala-env']['impala_group']
impala_log_dir = config['configurations']['impala-env']['impala_log_dir']
impala_scratch_dir = config['configurations']['impala-env']['impala_scratch_dir']
mem_limit = config['configurations']['impala-env']['mem_limit']
impala_run_init_lib = default("/configurations/impala-env/impala_run_init_lib", False)
impala_log_file = os.path.join(impala_log_dir,'impala-setup.log')
hostname = default("/agentLevelParams/hostname", socket.getfqdn())
socket_host_name = socket.getfqdn()
socket_short_host_name = socket.gethostname()
# Keep HA host selection stable across runs by deriving peers from sorted host lists.
impala_catalog_hosts = sorted(default("/clusterHostInfo/impala_catalog_service_hosts", []))
impala_state_store_hosts = sorted(default("/clusterHostInfo/impala_state_store_hosts", []))
impala_daemon_hosts = sorted(default("/clusterHostInfo/impala_daemon_hosts", []))
impala_catalog_host = impala_catalog_hosts[0] if impala_catalog_hosts else hostname
impala_state_store_host = impala_state_store_hosts[0] if impala_state_store_hosts else hostname
impala_secondary_state_store_host = impala_state_store_hosts[1] if len(impala_state_store_hosts) > 1 else impala_state_store_host
current_host_name = _resolve_current_host(
    impala_catalog_hosts + impala_state_store_hosts + impala_daemon_hosts,
    hostname,
    socket_host_name,
    socket_short_host_name,
) or socket_host_name
current_statestore_host = _resolve_current_host(
    impala_state_store_hosts,
    hostname,
    socket_host_name,
    socket_short_host_name,
)
impala_state_store_peer_host = _peer_host(impala_state_store_hosts, current_statestore_host) if current_statestore_host else impala_state_store_host
enable_catalogd_ha = len(impala_catalog_hosts) == 2
enable_statestored_ha = len(impala_state_store_hosts) == 2
enable_ranger = _as_bool(impala_env['enable_ranger'])
enable_trusted_subnets = _as_bool(impala_env['enable_trusted_subnets'])
enable_ldap_tls = _as_bool(impala_env['ldap_tls'])
enable_admission_control = _as_bool(impala_env['enable_admission_control'])
ldap_search_bind_authentication = _as_bool(impala_env['ldap_search_bind_authentication'])
ldap_baseDN = impala_env['ldap_baseDN']
ldap_domain = impala_env['impala_ldap_domain']
ldap_bind_pattern = impala_env['ldap_bind_pattern']
ldap_allow_anonymous_binds = _as_bool(impala_env['ldap_allow_anonymous_binds'])
impala_ldap_bind_password = impala_env['impala_ldap_bind_password']
llama_site_content = config['configurations']['llama-site']['content']
fair_scheduler_content = config['configurations']['fair-scheduler']['content']
enable_ldap_auth = _as_bool(impala_env['enable_ldap_auth'])
client_services_ssl_enabled = _as_bool(impala_env['client_services_ssl_enabled'])
enable_load_balancer = _as_bool(impala_env['enable_load_balancer'])
impala_load_balancer_host = impala_env['impala_load_balancer_host']
impala_log4j_properties = config['configurations']['impala-log4j-properties']['content']
impala_log4j_properties = impala_log4j_properties.replace("${impala.log.dir}",impala_log_dir)
impala_defaults = config['configurations']['impala-env']['impala_defaults']
impala_state_store_template = config['configurations']['impala-env']['impala_state_store_args']
impala_catalog_template = config['configurations']['impala-env']['impala_catalog_content']
impala_server_template = config['configurations']['impala-env']['impala_server_args']
local_library_dir=config['configurations']['impala-env']['local_library_dir']
enable_audit_event_log=_as_bool(impala_env['enable_audit_event_log'])
enable_core_dumps=_as_bool(impala_env['enable_core_dumps'])
audit_event_log_dir=impala_env['audit_event_log_dir']
new_catalog_items = dict()
new_statestore_items = dict()
new_server_items = dict()
for key, value in impala_env.items():
    if key.startswith("icatalog"):
        new_catalog_items[key[9:]] = value
    elif key.startswith("isstore"):
        new_statestore_items[key[8:]] = value
    elif key.startswith("iserver"):
        new_server_items[key[8:]] = value
    else:
        continue


impala_template = impala_defaults + impala_catalog_template + impala_state_store_template + impala_server_template

security_enabled = _as_bool(config['configurations']['cluster-env']['security_enabled'])

hdfs_host = default("/clusterHostInfo/namenode_hosts", [''])[0]
if not hdfs_host:
    hdfs_host = default("/clusterHostInfo/namenode_host", [''])[0]
hive_host = default("/clusterHostInfo/hive_metastore_hosts", [''])[0]
impala_conf_dir = "/etc/impala/conf"
impala_env_sh = os.path.join(impala_conf_dir, "impala-env.sh")
catalogd_flags_path = os.path.join(impala_conf_dir, "catalogd_flags")
statestored_flags_path = os.path.join(impala_conf_dir, "statestored_flags")
impalad_flags_path = os.path.join(impala_conf_dir, "impalad_flags")
impala_credential_alias = "impala_ldap_bind_password"
impala_credential_store_file = "/etc/security/credential/impala.jceks"
impala_credential_provider_path = "jceks://file/etc/security/credential/impala.jceks"
impala_credential_lib_dir = os.path.join(impala_conf_dir, "cred", "lib")
impala_credential_classpath = os.path.join(impala_credential_lib_dir, "*")
ldap_bind_password_cmd = os.path.join(impala_conf_dir, "init_ldap_creds.sh")
'''
scp_conf_from = {
    "hive": {
        "host": hive_host,
        "files": ["/etc/hive/conf/hive-site.xml"]},
    "hdfs": {
        "host": hdfs_host,
        "files": [
            "/etc/hadoop/conf/core-site.xml",
            "/etc/hadoop/conf/hdfs-site.xml"]},
}
'''
# Hive Ranger params
#site xml configurations
hive_site_config = dict(config['configurations']['hive-site'])
core_site_config = dict(config['configurations']['core-site'])
hdfs_site_config = dict(config['configurations']['hdfs-site'])
java64_home = config['ambariLevelParams']['java_home']
jdk_location = default("/ambariLevelParams/jdk_location", None)
ldap_credential_cmd = "\"{0}/bin/java\" -cp \"{1}\" org.apache.ambari.server.credentialapi.CredentialUtil get {2} -provider {3} 2>/dev/null | tail -n 1".format(
    java64_home,
    impala_credential_classpath,
    impala_credential_alias,
    impala_credential_provider_path,
)
java_version = expect("/ambariLevelParams/java_version", int)
version = default("/commandParams/version", None)
retryAble = default("/commandParams/command_retry_enabled", False)
stack_supports_ranger_kerberos = True

# users
hive_user = config['configurations']['hive-env']['hive_user']
user_group = config['configurations']['cluster-env']['user_group']

#current hosts and hive server list
hive_metastore_hosts = default('/clusterHostInfo/hive_metastore_hosts', [])
hive_server_hosts = default("/clusterHostInfo/hive_server_hosts", [])
hive_interactive_hosts = default('/clusterHostInfo/hive_server_interactive_hosts', [])
hive_client_hosts = default('/clusterHostInfo/hive_client_hosts', [])
setup_client_option = hostname not in ( hive_interactive_hosts + hive_metastore_hosts + hive_server_hosts + hive_client_hosts)
has_hive_interactive = len(hive_interactive_hosts) > 0
hive_server_interactive_ha = True if len(hive_interactive_hosts) > 1 else False
hive_transport_mode = config['configurations']['hive-site']['hive.server2.transport.mode']

if hive_transport_mode.lower() == "http":
    hive_server_port = config['configurations']['hive-site']['hive.server2.thrift.http.port']
else:
    hive_server_port = default('/configurations/hive-site/hive.server2.thrift.port',"10000")

hive_url = format("jdbc:hive2://{hive_server_host}:{hive_server_port}")
hive_jdbc_url = format("jdbc:hive2://{hive_zookeeper_quorum}/;serviceDiscoveryMode=zooKeeper;zooKeeperNamespace={hive_server2_zookeeper_namespace}")
if has_hive_interactive:
    if hive_server_interactive_ha:
        hsi_zookeeper_namespace = config['configurations']['hive-interactive-site']['hive.server2.active.passive.ha.registry.namespace']
        hsi_jdbc_url = format("jdbc:hive2://{hive_zookeeper_quorum}/;serviceDiscoveryMode=zooKeeperHA;zooKeeperNamespace={hsi_zookeeper_namespace}")
    else:
        hsi_zookeeper_namespace = config['configurations']['hive-interactive-site']['hive.server2.zookeeper.namespace']
        hsi_jdbc_url = format("jdbc:hive2://{hive_zookeeper_quorum}/;serviceDiscoveryMode=zooKeeper;zooKeeperNamespace={hsi_zookeeper_namespace}")

#Kerberos principal Hive Ranger
if security_enabled:
    hive_server_principal = config['configurations']['hive-site']['hive.server2.authentication.kerberos.principal']
    hive_principal = hive_server_principal.replace('_HOST', hostname.lower())
    hive_keytab = config['configurations']['hive-site']['hive.server2.authentication.kerberos.keytab']

smokeuser = config['configurations']['cluster-env']['smokeuser']
hive_metastore_warehouse_external_dir = config['configurations']['hive-site']["hive.metastore.warehouse.external.dir"]
hive_hook_proto_base_directory = format(config['configurations']['hive-site']["hive.hook.proto.base-directory"])

#Prepare hive-site xml
hive_site_config["hive.execution.engine"] = "tez"
hive_metastore_db_type = config['configurations']['hive-env']['hive_database_type']
hive_site_config["hive.metastore.db.type"] = hive_metastore_db_type.upper()
hive_site_config["hive.hook.proto.base-directory"] = hive_hook_proto_base_directory

hive_server_host = config['clusterHostInfo']['hive_server_hosts'][0]
impala_disable_kudu = _as_bool(config["configurations"]["impala-env"]["impala_disable_kudu"])
kudu_master_port = default("/configurations/kudu-master-env/rpc_bind_addresses", "0.0.0.0:7051").split(":")[1]
kudu_master_hosts = (":" + kudu_master_port + ",").join(default("/clusterHostInfo/kudu_master_hosts", [])) + ":" + kudu_master_port


# ranger hive plugin section start
#for create_hdfs_directory
namenode_hosts = default("/clusterHostInfo/namenode_hosts", None)
if type(namenode_hosts) is list:
    namenode_host = namenode_hosts[0]
else:
    namenode_host = namenode_hosts

has_namenode = not namenode_host == None
hdfs_user_keytab = config['configurations']['hadoop-env']['hdfs_user_keytab']
hdfs_principal_name = default('/configurations/hadoop-env/hdfs_principal_name', 'missing_principal').replace("_HOST", hostname)
kinit_path_local = get_kinit_path(default('/configurations/kerberos-env/executable_search_paths', None))
hdfs_user = config['configurations']['hadoop-env']['hdfs_user'] if has_namenode else None
hdfs_user_keytab = config['configurations']['hadoop-env']['hdfs_user_keytab'] if has_namenode else None
hdfs_principal_name = config['configurations']['hadoop-env']['hdfs_principal_name'] if has_namenode else None
hdfs_site = config['configurations']['hdfs-site'] if has_namenode else None
default_fs = config['configurations']['core-site']['fs.defaultFS'] if has_namenode else None
hadoop_bin_dir = stack_select.get_hadoop_dir("bin") if has_namenode else None
hadoop_conf_dir = conf_select.get_hadoop_conf_dir() if has_namenode else '/etc/hadoop/conf'
hadoop_home = stack_select.get_hadoop_dir("home") if has_namenode else '/usr/odp/current/hadoop-client'
has_hadoop_conf_dir = bool(hadoop_conf_dir and os.path.isdir(hadoop_conf_dir))
has_hadoop_home = bool(hadoop_home and os.path.isdir(hadoop_home))
mount_table_xml_inclusion_file_full_path = None
mount_table_content = None
if 'viewfs-mount-table' in config['configurations']:
    xml_inclusion_file_name = 'viewfs-mount-table.xml'
    mount_table = config['configurations']['viewfs-mount-table']

    if 'content' in mount_table and mount_table['content'].strip():
        mount_table_xml_inclusion_file_full_path = os.path.join(hadoop_conf_dir, xml_inclusion_file_name)
        mount_table_content = mount_table['content']
dfs_type = default("/clusterLevelParams/dfs_type", "")

import functools
#create partial functions with common arguments for every HdfsResource call
#to create hdfs directory we need to call params.HdfsResource in code
HdfsResource = functools.partial(
    HdfsResource,
    user = hdfs_user,
    hdfs_resource_ignore_file = "/var/lib/ambari-agent/data/.hdfs_resource_ignore",
    security_enabled = security_enabled,
    keytab = hdfs_user_keytab,
    kinit_path_local = kinit_path_local,
    hadoop_bin_dir = hadoop_bin_dir,
    hadoop_conf_dir = hadoop_conf_dir,
    principal_name = hdfs_principal_name,
    hdfs_site = hdfs_site,
    default_fs = default_fs,
    immutable_paths = get_not_managed_resources(),
    dfs_type = dfs_type
)

# ranger host
ranger_admin_hosts = default("/clusterHostInfo/ranger_admin_hosts", [])
has_ranger_admin = not len(ranger_admin_hosts) == 0
xml_configurations_supported = config['configurations']['ranger-env']['xml_configurations_supported']
# ranger hive plugin enabled property
hive_conf_dir = "/etc/hive/conf"
enable_ranger_hive = config['configurations']['hive-env']['hive_security_authorization'].lower() == 'ranger'
doAs = config["configurations"]["hive-site"]["hive.server2.enable.doAs"]

# get ranger hive properties if enable_ranger_hive is True
if enable_ranger_hive:
    # Access the audit config section safely
    audit_config = dict(config['configurations']['ranger-hive-audit'])
    audit_config_attributes = config['configurationAttributes']['ranger-hive-audit']
    # get ranger policy url
    policymgr_mgr_url = config['configurations']['admin-properties']['policymgr_external_url']
    if xml_configurations_supported:
        policymgr_mgr_url = config['configurations']['ranger-hive-security']['ranger.plugin.hive.policy.rest.url']

    if not is_empty(policymgr_mgr_url) and policymgr_mgr_url.endswith('/'):
        policymgr_mgr_url = policymgr_mgr_url.rstrip('/')

    # ranger audit db user
    xa_audit_db_user = default('/configurations/admin-properties/audit_db_user', 'rangerlogger')

    # ranger hive service name
    repo_name = str(config['clusterName']) + '_hive'
    repo_name_value = config['configurations']['ranger-hive-security']['ranger.plugin.hive.service.name']
    if not is_empty(repo_name_value) and repo_name_value != "{{repo_name}}":
        repo_name = repo_name_value

    jdbc_driver_class_name = config['configurations']['ranger-hive-plugin-properties']['jdbc.driverClassName']
    common_name_for_certificate = config['configurations']['ranger-hive-plugin-properties']['common.name.for.certificate']
    repo_config_username = config['configurations']['ranger-hive-plugin-properties']['REPOSITORY_CONFIG_USERNAME']

    # ranger-env config
    ranger_env = config['configurations']['ranger-env']

    impala_hosts = default("/clusterHostInfo/impala_daemon_hosts", []) + default("/clusterHostInfo/impala_catalog_service_hosts", []) + default("/clusterHostInfo/impala_state_store_hosts", [])
    if impala_hosts:
        impala_host = impala_hosts[0]
    else:
        impala_host = None

    if not impala_host :
        cache_service_list=['hiveServer2','impala']
    else:
        cache_service_list=['hiveServer2']

    # create ranger-env config having external ranger credential properties
    if not has_ranger_admin and enable_ranger_hive:
        external_admin_username = default('/configurations/ranger-hive-plugin-properties/external_admin_username', 'admin')
        external_admin_password = default('/configurations/ranger-hive-plugin-properties/external_admin_password', 'admin')
        external_ranger_admin_username = default('/configurations/ranger-hive-plugin-properties/external_ranger_admin_username', 'amb_ranger_admin')
        external_ranger_admin_password = default('/configurations/ranger-hive-plugin-properties/external_ranger_admin_password', 'amb_ranger_admin')
        ranger_env = {}
        ranger_env['admin_username'] = external_admin_username
        ranger_env['admin_password'] = external_admin_password
        ranger_env['ranger_admin_username'] = external_ranger_admin_username
        ranger_env['ranger_admin_password'] = external_ranger_admin_password

    ranger_plugin_properties = config['configurations']['ranger-hive-plugin-properties']
    policy_user = config['configurations']['ranger-hive-plugin-properties']['policy_user']
    repo_config_password = config['configurations']['ranger-hive-plugin-properties']['REPOSITORY_CONFIG_PASSWORD']

    ranger_downloaded_custom_connector = None
    ranger_previous_jdbc_jar_name = None
    ranger_driver_curl_source = None
    ranger_driver_curl_target = None
    ranger_previous_jdbc_jar = None

    #ranger_hive_url = format("{hive_url}/default;principal={hive_principal}") if security_enabled else hive_url
    ranger_hive_url = hive_jdbc_url if len(hive_server_hosts) > 0 else hsi_jdbc_url

    hive_ranger_plugin_config = {
        'username': repo_config_username,
        'password': repo_config_password,
        'jdbc.driverClassName': jdbc_driver_class_name,
        'jdbc.url': ranger_hive_url,
        'commonNameForCertificate': common_name_for_certificate,
        'ambari.service.check.user': smokeuser
    }

    hive_ranger_plugin_config['enable.hive.metastore.lookup'] = "TRUE"
    hive_ranger_plugin_config['hive.site.file.path'] = os.path.join(hive_conf_dir,"hive-site.xml")

    if security_enabled:
        hive_ranger_plugin_config['policy.download.auth.users'] = hive_user + ',' + impala_user
        hive_ranger_plugin_config['tag.download.auth.users'] = hive_user + ',' + impala_user
        hive_ranger_plugin_config['policy.grantrevoke.auth.users'] = hive_user + ',' + impala_user

    custom_ranger_service_config = generate_ranger_service_config(ranger_plugin_properties)
    if len(custom_ranger_service_config) > 0:
        hive_ranger_plugin_config.update(custom_ranger_service_config)

    hive_ranger_plugin_repo = {
        'isEnabled': 'true',
        'configs': hive_ranger_plugin_config,
        'description': 'hive repo',
        'name': repo_name,
        'type': 'hive'
    }

    xa_audit_db_password = ''
    xa_audit_db_is_enabled = False
    xa_audit_hdfs_is_enabled = config['configurations']['ranger-hive-audit']['xasecure.audit.destination.hdfs'] if xml_configurations_supported else False
    ssl_keystore_password = config['configurations']['ranger-hive-policymgr-ssl']['xasecure.policymgr.clientssl.keystore.password'] if xml_configurations_supported else None
    ssl_truststore_password = config['configurations']['ranger-hive-policymgr-ssl']['xasecure.policymgr.clientssl.truststore.password'] if xml_configurations_supported else None
    credential_file = '/etc/ranger/{}/cred.jceks'.format(repo_name)

# ranger hive plugin section end
