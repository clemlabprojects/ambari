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

import functools
import glob
import os
import socket

from resource_management.libraries.script.script import Script
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.get_not_managed_resources import get_not_managed_resources
from resource_management.libraries.functions.get_kinit_path import get_kinit_path
from resource_management.libraries.functions.is_empty import is_empty
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.setup_ranger_plugin_xml import generate_ranger_service_config
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.resources.hdfs_resource import HdfsResource as HdfsResourceBase


config = Script.get_config()
hostname = config['agentLevelParams']['hostname']
java_home = config['ambariLevelParams']['java_home']

kudu_env = config['configurations']['kudu-env']
kudu_master_env = config['configurations']['kudu-master-env']
kudu_tserver_env = config['configurations']['kudu-tserver-env']

kudu_user = kudu_env['kudu_user']
kudu_group = kudu_env['kudu_group']
kudu_conf_dir = kudu_env['kudu_conf_dir']
kudu_log_dir = kudu_env['kudu_log_dir']
kudu_run_dir = kudu_env['kudu_run_dir']
ranger_kudu_receiver_fifo_dir = default('/configurations/kudu-env/ranger_kudu_receiver_fifo_dir', kudu_run_dir)
kudu_enable_tls = str(default('/configurations/kudu-env/kudu_enable_tls', 'false')).lower() == 'true'
kudu_tls_cert_file = default('/configurations/kudu-env/kudu_tls_cert_file', '/etc/kudu/conf/tls/kudu-server.crt')
kudu_tls_private_key_file = default('/configurations/kudu-env/kudu_tls_private_key_file', '/etc/kudu/conf/tls/kudu-server.key')
kudu_tls_ca_cert_file = default('/configurations/kudu-env/kudu_tls_ca_cert_file', '')

security_enabled = config['configurations']['cluster-env']['security_enabled']
kinit_path_local = get_kinit_path(default('/configurations/kerberos-env/executable_search_paths', None))
kudu_keytab = default('/configurations/kudu-env/kudu_keytab', '')
kudu_principal_name = default('/configurations/kudu-env/kudu_principal_name', '')
if security_enabled and kudu_principal_name:
    kudu_principal_name = kudu_principal_name.replace('_HOST', hostname.lower())


def _extract_port(bind_addresses, default_port):
    if not bind_addresses:
        return default_port
    if ':' in bind_addresses:
        return bind_addresses.rsplit(':', 1)[1]
    return bind_addresses


kudu_master_hosts = default('/clusterHostInfo/kudu_master_hosts', [])
if not kudu_master_hosts:
    kudu_master_hosts = [socket.getfqdn()]

master_rpc_bind_addresses = default('/configurations/kudu-master-env/rpc_bind_addresses', '0.0.0.0:7051')
master_webserver_interface = default('/configurations/kudu-master-env/webserver_interface', '0.0.0.0')
master_webserver_port = default('/configurations/kudu-master-env/webserver_port', '8051')
master_wal_dir = default('/configurations/kudu-master-env/fs_wal_dir', '/var/lib/kudu/master')
master_data_dirs = default('/configurations/kudu-master-env/fs_data_dirs', '/var/lib/kudu/master')
master_additional_flags = default('/configurations/kudu-master-env/master_additional_flags', '')

master_rpc_port = _extract_port(master_rpc_bind_addresses, '7051')
recommended_master_addresses = ','.join(
    ['{0}:{1}'.format(host, master_rpc_port) for host in kudu_master_hosts]
)

master_addresses = default('/configurations/kudu-master-env/master_addresses', '')
if master_addresses:
    master_addresses = master_addresses.strip()
if not master_addresses:
    master_addresses = recommended_master_addresses


tserver_rpc_bind_addresses = default('/configurations/kudu-tserver-env/rpc_bind_addresses', '0.0.0.0:7050')
tserver_webserver_interface = default('/configurations/kudu-tserver-env/webserver_interface', '0.0.0.0')
tserver_webserver_port = default('/configurations/kudu-tserver-env/webserver_port', '8050')
tserver_wal_dir = default('/configurations/kudu-tserver-env/fs_wal_dir', '/var/lib/kudu/tserver')
tserver_data_dirs = default('/configurations/kudu-tserver-env/fs_data_dirs', '/var/lib/kudu/tserver')
tserver_additional_flags = default('/configurations/kudu-tserver-env/tserver_additional_flags', '')

tserver_master_addrs = default('/configurations/kudu-tserver-env/tserver_master_addrs', '')
if tserver_master_addrs:
    tserver_master_addrs = tserver_master_addrs.strip()
if not tserver_master_addrs:
    tserver_master_addrs = master_addresses


kudu_master_bin = '/usr/odp/current/kudu-master/bin/kudu.sh'
kudu_tserver_bin = '/usr/odp/current/kudu-tserver/bin/kudu.sh'
_kudu_cli_candidates = [
    '/usr/odp/current/kudu-master/bin/kudu',
    '/usr/odp/current/kudu/bin/kudu',
    '/usr/bin/kudu',
]
kudu_cli_bin = _kudu_cli_candidates[0]
for _kudu_cli in _kudu_cli_candidates:
    if os.path.isfile(_kudu_cli):
        kudu_cli_bin = _kudu_cli
        break

ranger_kudu_config_path = kudu_conf_dir
_candidate_java_path = format('{java_home}/bin/java') if java_home else '/usr/bin/java'
ranger_kudu_java_path = _candidate_java_path if os.path.exists(_candidate_java_path) else '/usr/bin/java'
_role_name = str(config.get('role', '')).upper()
_role_to_kudu_package = {
    'KUDU_MASTER': 'kudu-master',
    'KUDU_TSERVER': 'kudu-tserver',
}
_default_kudu_package = _role_to_kudu_package.get(_role_name, 'kudu-master')
try:
    _current_kudu_package = stack_select.get_package_name(default_package=_default_kudu_package)
except Exception:
    _current_kudu_package = _default_kudu_package
_ranger_kudu_jar_dirs = [
    '/usr/odp/current/{0}/lib/ranger-kudu-plugin-impl'.format(_current_kudu_package),
    '/usr/odp/current/kudu-master/lib/ranger-kudu-plugin-impl',
    '/usr/odp/current/kudu-tserver/lib/ranger-kudu-plugin-impl',
    '/usr/odp/current/kudu/lib/ranger-kudu-plugin-impl',
    '/usr/odp/current/ranger-kudu-plugin/lib/ranger-kudu-plugin-impl',
    '/usr/odp/current/ranger-kudu-plugin/lib/ranger-hive-plugin-impl'
]
ranger_kudu_jar_path = '{0}/*'.format(_ranger_kudu_jar_dirs[0])
for _jar_dir in _ranger_kudu_jar_dirs:
    if os.path.isdir(_jar_dir):
        _jar_files = sorted(glob.glob(os.path.join(_jar_dir, '*.jar')))
        if _jar_files:
            ranger_kudu_jar_path = ':'.join(_jar_files)
        else:
            ranger_kudu_jar_path = '{0}/*'.format(_jar_dir)
        break

kudu_master_pid_file = format('{kudu_run_dir}/kudu-master.pid')
kudu_tserver_pid_file = format('{kudu_run_dir}/kudu-tserver.pid')


# Ranger Kudu plugin integration
version_for_stack_feature_checks = default('/commandParams/version', None)
stack_supports_ranger_kerberos = check_stack_feature(
    StackFeature.RANGER_KERBEROS_SUPPORT,
    version_for_stack_feature_checks
)
retry_enabled = default('/commandParams/ranger_plugin_hook_retry_enabled', 'False').lower() == 'true'
enable_ranger_kudu = str(
    default('/configurations/ranger-kudu-plugin-properties/ranger-kudu-plugin-enabled', 'No')
).lower() == 'yes'

ranger_policy_config = {}
xa_audit_hdfs_is_enabled = False
xa_audit_db_is_enabled = False
credential_file = None
ssl_keystore_password = None
ssl_truststore_password = None
policy_user = None
policymgr_mgr_url = None
ranger_env = {}
ranger_plugin_properties = {}
ranger_kudu_audit = {}
ranger_kudu_audit_attrs = {}
ranger_kudu_security = {}
ranger_kudu_security_attrs = {}
ranger_kudu_policymgr_ssl = {}
ranger_kudu_policymgr_ssl_attrs = {}
ranger_admin_username = None
ranger_admin_password = None
kudu_ranger_plugin_repo = {}
ranger_kudu_principal = kudu_principal_name
ranger_kudu_keytab = kudu_keytab
repo_name = None

previous_jdbc_jar = None
downloaded_custom_connector = None
driver_curl_source = None
driver_curl_target = None

ranger_admin_hosts = default('/clusterHostInfo/ranger_admin_hosts', [])
has_ranger_admin = len(ranger_admin_hosts) > 0

namenode_host = default('/clusterHostInfo/namenode_host', None)
has_namenode = namenode_host is not None

if has_namenode:
    hadoop_bin_dir = stack_select.get_hadoop_dir('bin')
    hadoop_conf_dir = os.environ.get('HADOOP_CONF_DIR', '/etc/hadoop/conf')
    hdfs_user = config['configurations']['hadoop-env']['hdfs_user']
    hdfs_user_keytab = config['configurations']['hadoop-env']['hdfs_user_keytab']
    hdfs_principal_name = config['configurations']['hadoop-env']['hdfs_principal_name']
    hdfs_site = config['configurations']['hdfs-site']
    default_fs = config['configurations']['core-site']['fs.defaultFS']
    dfs_type = default('/clusterLevelParams/dfs_type', '')

    HdfsResource = functools.partial(
        HdfsResourceBase,
        user=hdfs_user,
        hdfs_resource_ignore_file='/var/lib/ambari-agent/data/.hdfs_resource_ignore',
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

if enable_ranger_kudu:
    cluster_name = config['clusterName']
    repo_name = '{0}_kudu'.format(cluster_name)

    ranger_kudu_security_site = config['configurations'].get('ranger-kudu-security', {})
    repo_name_value = ranger_kudu_security_site.get('ranger.plugin.kudu.service.name', '')
    if not is_empty(repo_name_value) and '{{' not in repo_name_value and '}}' not in repo_name_value:
        repo_name = repo_name_value

    ranger_kudu_policymgr_ssl_site = config['configurations'].get('ranger-kudu-policymgr-ssl', {})
    ssl_keystore_password = ranger_kudu_policymgr_ssl_site.get(
        'xasecure.policymgr.clientssl.keystore.password',
        'myKeyFilePassword'
    )
    ssl_truststore_password = ranger_kudu_policymgr_ssl_site.get(
        'xasecure.policymgr.clientssl.truststore.password',
        'changeit'
    )
    credential_file = format('/etc/ranger/{repo_name}/cred.jceks')
    xa_audit_hdfs_is_enabled = default('/configurations/ranger-kudu-audit/xasecure.audit.destination.hdfs', False)

    policymgr_mgr_url = ranger_kudu_security_site.get('ranger.plugin.kudu.policy.rest.url', None)
    if is_empty(policymgr_mgr_url) or policymgr_mgr_url == '{{policymgr_mgr_url}}':
        policymgr_mgr_url = default('/configurations/admin-properties/policymgr_external_url', None)
        if is_empty(policymgr_mgr_url):
            policymgr_mgr_url = default('/configurations/ranger-admin-site/ranger.service.http.url', None)
    if policymgr_mgr_url and policymgr_mgr_url.endswith('/'):
        policymgr_mgr_url = policymgr_mgr_url.rstrip('/')

    ranger_env = config['configurations']['ranger-env']
    if not has_ranger_admin:
        external_admin_username = default('/configurations/ranger-kudu-plugin-properties/external_admin_username', 'admin')
        external_admin_password = default('/configurations/ranger-kudu-plugin-properties/external_admin_password', 'admin')
        external_ranger_admin_username = default('/configurations/ranger-kudu-plugin-properties/external_ranger_admin_username', 'amb_ranger_admin')
        external_ranger_admin_password = default('/configurations/ranger-kudu-plugin-properties/external_ranger_admin_password', 'amb_ranger_admin')
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

    ranger_plugin_properties = config['configurations']['ranger-kudu-plugin-properties']
    ranger_kudu_audit = config['configurations']['ranger-kudu-audit']
    ranger_kudu_audit_attrs = config['configurationAttributes']['ranger-kudu-audit']
    ranger_kudu_security = config['configurations']['ranger-kudu-security']
    ranger_kudu_security_attrs = config['configurationAttributes']['ranger-kudu-security']
    ranger_kudu_policymgr_ssl = config['configurations']['ranger-kudu-policymgr-ssl']
    ranger_kudu_policymgr_ssl_attrs = config['configurationAttributes']['ranger-kudu-policymgr-ssl']

    policy_user = ranger_plugin_properties['policy_user']

    kudu_repository_configuration = {
        'username': ranger_plugin_properties['REPOSITORY_CONFIG_USERNAME'],
        'password': ranger_plugin_properties['REPOSITORY_CONFIG_PASSWORD'],
        'commonNameForCertificate': ranger_plugin_properties['common.name.for.certificate'],
        'ambari.service.check.user': policy_user,
        'policy.download.auth.users': kudu_user,
        'tag.download.auth.users': kudu_user,
    }
    custom_ranger_service_config = generate_ranger_service_config(ranger_plugin_properties)
    if len(custom_ranger_service_config) > 0:
        kudu_repository_configuration.update(custom_ranger_service_config)

    kudu_ranger_plugin_repo = {
        'isEnabled': 'true',
        'configs': kudu_repository_configuration,
        'description': 'kudu repo',
        'name': repo_name,
        'type': 'kudu',
    }
