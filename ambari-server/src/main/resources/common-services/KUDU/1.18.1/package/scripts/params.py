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

import socket

from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.get_kinit_path import get_kinit_path
from resource_management.libraries.functions.format import format


config = Script.get_config()
hostname = config['agentLevelParams']['hostname']

kudu_env = config['configurations']['kudu-env']
kudu_master_env = config['configurations']['kudu-master-env']
kudu_tserver_env = config['configurations']['kudu-tserver-env']

kudu_user = kudu_env['kudu_user']
kudu_group = kudu_env['kudu_group']
kudu_conf_dir = kudu_env['kudu_conf_dir']
kudu_log_dir = kudu_env['kudu_log_dir']
kudu_run_dir = kudu_env['kudu_run_dir']

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

kudu_master_pid_file = format('{kudu_run_dir}/kudu-master.pid')
kudu_tserver_pid_file = format('{kudu_run_dir}/kudu-tserver.pid')
