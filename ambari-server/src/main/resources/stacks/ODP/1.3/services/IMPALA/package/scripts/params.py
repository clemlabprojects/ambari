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
impala_conf_dir = "/etc/impala/conf"

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

## Common properties:
impala_idle_query_timeout = default("/configurations/impala-env/impala_idle_query_timeout", 3600)
impala_idle_session_timeout = default("/configurations/impala-env/impala_idle_session_timeout", 3600)
enable_legacy_avx_support = _as_bool(default("/configurations/impala-env/enable_legacy_avx_support", False))
enable_insert_events = _as_bool(default("/configurations/impala-env/enable_insert_events", False))

## configure default catalogd flags
max_log_files = default("/configurations/impala-env/max_log_files", 10)
impala_catalog_webserver_port = default("/configurations/impala-env/impala_catalog_webserver_port", 25020)

#The estimated stats size is calculated as 400 bytes * # columns * # partitions. The option prevents you from computing incremental stats on tables with too many columns and partitions (it guards against the scenario where memory usage from incremental stats creeps up and up as tables get larger, eventually causing an outage).
# So you probably want to set it based on the expected size of the largest table that you will be using incremental stats on (that would help prevent someone accidentally computing incremental stats on an even larger table).
# A few other comments.
# Generally non-incremental stats will be more robust but we understand that it's sometimes challenging or less practical to do a full compute stats on all tables. So if the calculation above spits out a huge number, you might want to reconsider that.
# You need to be careful with bumping *only* the catalog heap size. On versions prior to CDH5.16, you need all coordinator impala daemons to have a heap size as large as the catalogd, since the catalog cache is replicated. That was addressed for incremental stats specifically in CDH5.16 by *not* replicating the incremental stats (all other state is still replicated).
# In CDH5.16 the memory consumption was improved substantially as well (the incremental stats use ~5x less memory). The estimated stats size is actually reduce to 200 bytes * # columns * #partitions.
inc_stats_size_limit_bytes = default("/configurations/impala-env/inc_stats_size_limit_bytes", 100 * 1024 * 1024)
# automatic refresh metadata interval in seconds. If set to a positive value, Impala will automatically refresh metadata for tables in the catalog that have not been refreshed in the last N seconds. This is disabled by default (0 or negative value).
hms_event_polling_interval_s = default("/configurations/impala-env/hms_event_polling_interval_s", 60)
#  determines how much parallelism Impala devotes to loading metadata in the background. The default is 16. You might increase this value for systems with huge numbers of databases, tables, or partitions. You might lower this value for busy systems that are CPU-constrained due to jobs from components other than Impala.  
# https://impala.apache.org/docs/build/html/topics/impala_new_features.html
num_metadata_loading_threads = default("/configurations/impala-env/num_metadata_loading_threads", 4)
# reference https://issues.apache.org/jira/browse/IMPALA-6512
datastream_sender_timeout_ms = default("/configurations/impala-env/datastream_sender_timeout_ms", 30000)
# Improved scalability for highly concurrent loads by reducing the possibility of TCP/IP timeouts. A configuration setting, accepted_cnxn_queue_depth, can be adjusted upwards to avoid this type of timeout on large clusters.  Impala 2.8
accepted_cnxn_queue_depth = default("/configurations/impala-env/accepted_cnxn_queue_depth", 128)
minidump_path = default("/configurations/impala-env/minidump_path", "/var/lib/impala/minidumps")
max_minidumps = default("/configurations/impala-env/max_minidumps", 5)
default_query_options = default("/configurations/impala-env/default_query_options", "")
idle_client_poll_period_s = default("/configurations/impala-env/idle_client_poll_period_s", 60)
statestore_subscriber_timeout_s = default("/configurations/impala-env/statestore_subscriber_timeout_s", 90)
load_catalog_in_background = _as_bool(default("/configurations/impala-env/load_catalog_in_background", True))
load_auth_to_local_rules = default("/configurations/impala-env/load_auth_to_local_rules", "")
redaction_rules_file = default("/configurations/impala-env/redaction_rules_file", os.path.join(impala_conf_dir, "redaction-rules.json"))


## configure default statestored flags
max_statestore_log_files = default("/configurations/impala-env/max_statestore_log_files", 10)
impala_statestore_webserver_port = default("/configurations/impala-env/impala_statestore_webserver_port", 25011)
impala_state_store_port = default("/configurations/impala-env/impala_state_store_port", 25010)
statestore_update_frequency_ms = default("/configurations/impala-env/statestore_update_frequency_ms", 1000)
statestore_num_update_threads = default("/configurations/impala-env/statestore_num_update_threads", 4)
inc_stats_size_limit_bytes = default("/configurations/impala-env/inc_stats_size_limit_bytes", 100 * 1024 * 1024)
statestore_minidump_path = default("/configurations/impala-env/statestore_minidump_path", "/var/lib/impala/statestore_minidumps")
statestore_max_minidumps = default("/configurations/impala-env/statestore_max_minidumps", 5)
statestore_default_query_options = default("/configurations/impala-env/statestore_default_query_options", "")
statestore_heartbeat_frequency_ms = default("/configurations/impala-env/statestore_heartbeat_frequency_ms", 5000)
statestore_heartbeat_tcp_timeout_seconds = default("/configurations/impala-env/statestore_heartbeat_tcp_timeout_seconds", 60)
statestore_max_missed_heartbeats = default("/configurations/impala-env/statestore_max_missed_heartbeats", 12)
statestore_num_heartbeat_threads = default("/configurations/impala-env/statestore_num_heartbeat_threads", 4)
statestore_ha_preemption_wait_period_ms = default("/configurations/impala-env/statestore_ha_preemption_wait_period_ms", 30000)
impala_state_store_peer_host = _peer_host(impala_state_store_hosts, current_statestore_host) if current_statestore_host else impala_state_store_host
enable_catalogd_ha = len(impala_catalog_hosts) == 2
catalogd_ha_preemption_wait_period_ms = default("/configurations/impala-env/catalogd_ha_preemption_wait_period_ms", 30000)
enable_statestored_ha = len(impala_state_store_hosts) == 2
state_store_ha_port = default("/configurations/impala-env/state_store_ha_port", 25012)

## configure default impalad flags
impala_backend_port = default("/configurations/impala-env/impala_backend_port", 22000)
impala_daemon_webserver_port = default("/configurations/impala-env/impala_daemon_webserver_port", 25000)
impala_scratch_dir = default("/configurations/impala-env/impala_scratch_dir", "/tmp")
_default_state_store_host = impala_state_store_hosts[0] if impala_state_store_hosts else hostname
_default_catalog_host = impala_catalog_hosts[0] if impala_catalog_hosts else hostname
impala_state_store_host = _default_state_store_host
impalad_mem_limit = default("/configurations/impala-env/impalad_mem_limit", "20gb")
impala_catalog_host = _default_catalog_host
enable_state_store_ha = len(impala_state_store_hosts) == 2
if enable_state_store_ha:
    impala_secondary_state_store_host = impala_state_store_hosts[1]
impalad_max_log_files = default("/configurations/impala-env/impalad_max_log_files", 10)
fe_service_threads = default("/configurations/impala-env/fe_service_threads", 16)
impalad_max_profile_log_file_size = default("/configurations/impala-env/impalad_max_profile_log_file_size", 100 * 1024 * 1024)
impalad_max_profile_log_files = default("/configurations/impala-env/impalad_max_profile_log_files", 10)
impalad_profile_log_dir = default("/configurations/impala-env/impalad_profile_log_dir", os.path.join(impala_log_dir, "profiles"))
disable_pool_max_requests = _as_bool(default("/configurations/impala-env/disable_pool_max_requests", False))
disable_pool_mem_limits = _as_bool(default("/configurations/impala-env/disable_pool_mem_limits", False))
default_pool_max_queued = default("/configurations/impala-env/default_pool_max_queued", 1000)
default_pool_max_requests = default("/configurations/impala-env/default_pool_max_requests", 0)
default_pool_mem_limit = default("/configurations/impala-env/default_pool_mem_limit", 0)
queue_wait_timeout_ms = default("/configurations/impala-env/queue_wait_timeout_ms", 300000)
impalad_minidump_path = default("/configurations/impala-env/impalad_minidump_path", "/var/lib/impala/impalad_minidumps")
impalad_max_minidumps = default("/configurations/impala-env/impalad_max_minidumps", 5)
impalad_default_query_options = default("/configurations/impala-env/impalad_default_query_options", "")
max_result_cache_size = default("/configurations/impala-env/max_result_cache_size", "1GB")
unused_file_handle_timeout_sec = default("/configurations/impala-env/unused_file_handle_timeout_sec", 60)
max_cached_file_handles = default("/configurations/impala-env/max_cached_file_handles", 1000)
max_audit_event_log_file_size = default("/configurations/impala-env/max_audit_event_log_file_size", 100 * 1024 * 1024)
max_audit_event_log_files = default("/configurations/impala-env/max_audit_event_log_files", 10)
impala_use_local_tz_for_unix_timestamp_conversions = _as_bool(default("/configurations/impala-env/impala_use_local_tz_for_unix_timestamp_conversions", False))
impala_convert_legacy_hive_parquet_utc_timestamps = _as_bool(default("/configurations/impala-env/impala_convert_legacy_hive_parquet_utc_timestamps", False))
use_local_catalog =  _as_bool(default("/configurations/impala-env/use_local_catalog", False))
disk_spill_encryption = default("/configurations/impala-env/disk_spill_encryption", "none")
abort_on_failed_audit_event = _as_bool(default("/configurations/impala-env/abort_on_failed_audit_event", False))
lineage_event_log_dir = default("/configurations/impala-env/lineage_event_log_dir", os.path.join(impala_log_dir, "lineage"))
max_lineage_log_file_size = default("/configurations/impala-env/max_lineage_log_file_size", 100 * 1024 * 1024)
is_coordinator = current_host_name in impala_catalog_hosts
is_executor = current_host_name in impala_daemon_hosts
local_library_dir = default("/configurations/impala-env/local_library_dir", "/usr/lib/impala/lib")
# 
enable_ranger = config['configurations']['impala-env']['enable_ranger'] if 'enable_ranger' in config['configurations']['impala-env'] else False
if enable_ranger:
    impala_ranger_service_type = default("/configurations/impala-env/impala_ranger_service_type", "ranger")
    impala_ranger_app_id = default("/configurations/impala-env/impala_ranger_app_id", "impala")
    impala_authorization_provider = default("/configurations/impala-env/impala_authorization_provider", "ranger")
    impala_proxy_user_config = default("/configurations/impala-env/impala_proxy_user_config", "")
else:
    impala_ranger_service_type = default("/configurations/impala-env/impala_ranger_service_type", "impala")
    impala_ranger_app_id = default("/configurations/impala-env/impala_ranger_app_id", "impala")
    impala_authorization_provider = default("/configurations/impala-env/impala_authorization_provider", "legacy")
    impala_proxy_user_config = default("/configurations/impala-env/impala_proxy_user_config", "")

enable_trusted_subnets = _as_bool(impala_env['enable_trusted_subnets'])
if enable_trusted_subnets:
    trusted_subnets = impala_env['trusted_subnets']
enable_ldap_tls = _as_bool(default("/configurations/impala-env/ldap_tls", False))
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
enable_load_balancer = _as_bool(impala_env['enable_load_balancer'])
if enable_load_balancer:
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

security_enabled = config['configurations']['cluster-env']['security_enabled']

# Kerberos Principal for Impala daemons
if security_enabled:
    kerberos_reinit_interval = default("/configurations/impala-env/kerberos_reinit_interval", 24*3600)
    impala_keytab = config['configurations']['impala-env']['impala_keytab']
    impala_principal = config['configurations']['impala-env']['impala_principal_name'].replace('_HOST', hostname.lower())
    realm_name = impala_principal.split('@')[1] if '@' in impala_principal else None
# SSL and TLS settings
client_services_ssl_enabled = _as_bool(impala_env['client_services_ssl_enabled'])
if client_services_ssl_enabled:
    ssl_server_certificate = default("/configurations/impala-env/ssl_server_certificate", "")
    ssl_private_key = default("/configurations/impala-env/ssl_private_key", "")
    ssl_private_key_password_cmd = default("/configurations/impala-env/ssl_private_key_password_cmd", "")
    ssl_client_ca_certificate = default("/configurations/impala-env/ssl_client_ca_certificate", "")
    webserver_certificate_file = default("/configurations/impala-env/webserver_certificate_file", "")
    webserver_private_key_file = default("/configurations/impala-env/webserver_private_key_file", "")
    webserver_private_key_password_cmd = default("/configurations/impala-env/webserver_private_key_password_cmd", "")

# LDAP
if enable_ldap_auth:
    impala_ldap_uri = config['configurations']['impala-env']['impala_ldap_uri']
    impala_ldap_passwords_in_clear_ok = _as_bool(config['configurations']['impala-env']['impala_ldap_passwords_in_clear_ok'])
    ldap_allow_anonymous_binds = _as_bool(config['configurations']['impala-env']['ldap_allow_anonymous_binds'])
    ldap_bind_dn = config['configurations']['impala-env']['ldap_bind_dn']
    impala_ldap_domain = config['configurations']['impala-env']['impala_ldap_domain']
    ldap_baseDN = config['configurations']['impala-env']['ldap_baseDN']
    ldap_bind_pattern = config['configurations']['impala-env']['ldap_bind_pattern']
    ldap_search_bind_authentication = _as_bool(config['configurations']['impala-env']['ldap_search_bind_authentication'])
    allow_custom_ldap_filters_with_kerberos_auth = _as_bool(config['configurations']['impala-env']['allow_custom_ldap_filters_with_kerberos_auth'])
    ldap_user_search_basedn = config['configurations']['impala-env']['ldap_user_search_basedn']
    ldap_group_search_basedn = config['configurations']['impala-env']['ldap_group_search_basedn']
    ldap_user_filter = config['configurations']['impala-env']['ldap_user_filter']
    ldap_group_filter = config['configurations']['impala-env']['ldap_group_filter']
    ldap_user_filter = config['configurations']['impala-env']['ldap_user_filter']
    ldap_group_filter = config['configurations']['impala-env']['ldap_group_filter']
    ldap_group_dn_pattern = config['configurations']['impala-env']['ldap_group_dn_pattern']
    ldap_group_membership_key = config['configurations']['impala-env']['ldap_group_membership_key']
    ldap_group_class_key = config['configurations']['impala-env']['ldap_group_class_key']
    enable_ldap_tls = _as_bool(default("/configurations/impala-env/ldap_tls", False))
    ldap_ca_certificate = config['configurations']['impala-env']['ldap_ca_certificate']
    if ldap_ca_certificate == "" and enable_ldap_tls and client_services_ssl_enabled:
        ldap_ca_certificate = ssl_client_ca_certificate

# Hadoop settings
hdfs_host = default("/clusterHostInfo/namenode_hosts", [''])[0]
if not hdfs_host:
    hdfs_host = default("/clusterHostInfo/namenode_host", [''])[0]
hive_host = default("/clusterHostInfo/hive_metastore_hosts", [''])[0]
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
hivemetastore_site_config = dict(default('/configurations/hivemetastore-site', {}))
core_site_config = dict(config['configurations']['core-site'])
hdfs_site_config = dict(default('/configurations/hdfs-site', {}))
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

# Hive 4 deployments may keep HMS client settings in hivemetastore-site using
# metastore.* keys. Impala reads hive-site.xml, so mirror critical HMS settings.
def _first_non_empty_prop(configs, keys):
    for key in keys:
        value = configs.get(key)
        if value is not None and str(value).strip() != "":
            return str(value).strip()
    return None

hms_uris = _first_non_empty_prop(
    hive_site_config,
    ["hive.metastore.uris", "metastore.uris", "metastore.thrift.uris"]
)
if hms_uris is None:
    hms_uris = _first_non_empty_prop(
        hivemetastore_site_config,
        ["hive.metastore.uris", "metastore.uris", "metastore.thrift.uris"]
    )
if hms_uris:
    hive_site_config["hive.metastore.uris"] = hms_uris

hms_principal = _first_non_empty_prop(
    hive_site_config,
    ["hive.metastore.kerberos.principal", "metastore.kerberos.principal"]
)
if hms_principal is None:
    hms_principal = _first_non_empty_prop(
        hivemetastore_site_config,
        ["hive.metastore.kerberos.principal", "metastore.kerberos.principal"]
    )
if hms_principal:
    hive_site_config["hive.metastore.kerberos.principal"] = hms_principal

hms_sasl = _first_non_empty_prop(
    hive_site_config,
    ["hive.metastore.sasl.enabled", "metastore.sasl.enabled", "metastore.thrift.sasl.enabled"]
)
if hms_sasl is None:
    hms_sasl = _first_non_empty_prop(
        hivemetastore_site_config,
        ["hive.metastore.sasl.enabled", "metastore.sasl.enabled", "metastore.thrift.sasl.enabled"]
    )

if security_enabled and str(hms_sasl).strip().lower() != "false":
    hive_site_config["hive.metastore.sasl.enabled"] = "true"

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
