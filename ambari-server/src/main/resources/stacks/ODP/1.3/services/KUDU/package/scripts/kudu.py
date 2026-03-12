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

import os
import socket

from resource_management.libraries.script.script import Script
from resource_management.core.resources.system import Directory, Execute, File
from resource_management.core.source import Template
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions.format import format


class Kudu(Script):
    def installKudu(self, env):
        self.install_packages(env)
        self.configure(env)

    def _split_dirs(self, dirs_value):
        if not dirs_value:
            return []
        return [item.strip() for item in dirs_value.split(',') if item.strip()]

    def _component_meta(self, role, params):
        if role == 'master':
            return {
                'binary': params.kudu_master_bin,
                'pid_file': params.kudu_master_pid_file,
                'role_arg': 'master'
            }
        if role == 'tserver':
            return {
                'binary': params.kudu_tserver_bin,
                'pid_file': params.kudu_tserver_pid_file,
                'role_arg': 'tserver'
            }
        raise Fail('Unsupported Kudu role: {0}'.format(role))

    def _kinit_if_needed(self, params):
        if not params.security_enabled:
            return
        if not params.kinit_path_local:
            raise Fail('Kerberos is enabled but kinit binary path could not be determined')
        if not params.kudu_principal_name or not params.kudu_keytab:
            raise Fail('Kerberos is enabled but Kudu principal/keytab is not configured')
        Execute(
            format('{kinit_path_local} -kt {kudu_keytab} {kudu_principal_name}'),
            user=params.kudu_user
        )

    def _normalize_host(self, host):
        if host is None:
            return ''
        return str(host).strip().lower().rstrip('.')

    def _host_aliases(self, host):
        normalized = self._normalize_host(host)
        aliases = set()
        if normalized:
            aliases.add(normalized)
            aliases.add(normalized.split('.', 1)[0])
        return aliases

    def _hosts_match(self, left, right):
        return len(self._host_aliases(left).intersection(self._host_aliases(right))) > 0

    def _local_host_aliases(self, params):
        aliases = set()
        for value in [params.hostname, socket.gethostname(), socket.getfqdn()]:
            aliases.update(self._host_aliases(value))
        return aliases

    def _parse_master_address(self, address, default_port):
        token = str(address).strip()
        if not token:
            return None
        if ':' in token:
            host, port = token.rsplit(':', 1)
            host = host.strip()
            port = port.strip() or default_port
        else:
            host = token
            port = default_port
        if not host:
            return None
        normalized = self._normalize_host(host)
        return {
            'host': normalized,
            'port': str(port),
            'address': '{0}:{1}'.format(normalized, port)
        }

    def _parse_master_addresses(self, master_addresses, default_port):
        parsed = []
        seen = set()
        for raw in str(master_addresses or '').split(','):
            entry = self._parse_master_address(raw, default_port)
            if not entry:
                continue
            key = entry['address']
            if key in seen:
                continue
            seen.add(key)
            parsed.append(entry)
        return parsed

    def _is_reachable(self, host, port, timeout_sec=2.0):
        sock = None
        try:
            sock = socket.create_connection((host, int(port)), timeout_sec)
            return True
        except Exception:
            return False
        finally:
            if sock is not None:
                try:
                    sock.close()
                except Exception:
                    pass

    def _is_dir_empty_for_bootstrap(self, path):
        if not os.path.exists(path):
            return True
        if not os.path.isdir(path):
            return False
        entries = [name for name in os.listdir(path) if name != 'lost+found']
        return len(entries) == 0

    def _assert_master_dirs_bootstrap_ready(self, params):
        not_empty = []
        for data_dir in self._split_dirs(params.master_wal_dir) + self._split_dirs(params.master_data_dirs):
            if not self._is_dir_empty_for_bootstrap(data_dir):
                not_empty.append(data_dir)
        if not_empty:
            raise Fail(
                "ADD_KUDU_MASTER requires empty master storage directories on the target host. "
                "Non-empty paths: {0}".format(', '.join(not_empty))
            )

    def add_kudu_master_to_cluster(self, env):
        import params
        env.set_params(params)

        self.configure(env)
        self._kinit_if_needed(params)
        self._assert_master_dirs_bootstrap_ready(params)

        default_port = str(params.master_rpc_port) if params.master_rpc_port else '7051'
        local_aliases = self._local_host_aliases(params)

        configured_masters = self._parse_master_addresses(params.master_addresses, default_port)
        local_entry = None
        existing_entries = []
        for entry in configured_masters:
            if entry['host'] in local_aliases:
                if local_entry is None:
                    local_entry = entry
                continue
            existing_entries.append(entry)

        if local_entry is None:
            local_entry = self._parse_master_address('{0}:{1}'.format(params.hostname, default_port), default_port)
            configured_masters.append(local_entry)
            Logger.info(
                "Local host was not present in kudu-master-env/master_addresses; using {0} as target master address."
                .format(local_entry['address'])
            )

        if not existing_entries:
            raise Fail(
                "No existing masters found in kudu-master-env/master_addresses after excluding local host "
                "({0}). Add the current leader first.".format(local_entry['host'])
            )

        reachable_existing = []
        unreachable_existing = []
        for entry in existing_entries:
            if self._is_reachable(entry['host'], entry['port']):
                reachable_existing.append(entry)
            else:
                unreachable_existing.append(entry['address'])

        if not reachable_existing:
            raise Fail(
                "Unable to reach any existing master from configured addresses: {0}".format(
                    ', '.join([entry['address'] for entry in existing_entries])
                )
            )

        if unreachable_existing:
            Logger.warning(
                "Ignoring unreachable configured masters for ADD_KUDU_MASTER: {0}".format(
                    ', '.join(unreachable_existing)
                )
            )

        seed_master_addrs = ','.join([entry['address'] for entry in reachable_existing])

        command_master_addrs = []
        seen = set()
        for entry in reachable_existing + [local_entry]:
            if entry['address'] in seen:
                continue
            seen.add(entry['address'])
            command_master_addrs.append(entry['address'])

        runtime_env = os.environ.copy()
        runtime_env['KUDU_CONF_DIR'] = params.kudu_conf_dir
        runtime_env['KUDU_LOG_DIR'] = params.kudu_log_dir
        runtime_env['KUDU_RUN_DIR'] = params.kudu_run_dir

        cmd = [
            params.kudu_cli_bin,
            'master',
            'add',
            seed_master_addrs,
            local_entry['address'],
            '--fs_wal_dir={0}'.format(params.master_wal_dir),
            '--fs_data_dirs={0}'.format(params.master_data_dirs),
            '--rpc_bind_addresses={0}'.format(params.master_rpc_bind_addresses),
            '--webserver_interface={0}'.format(params.master_webserver_interface),
            '--webserver_port={0}'.format(params.master_webserver_port),
            '--master_addresses={0}'.format(','.join(command_master_addrs)),
        ]

        if params.security_enabled:
            cmd.extend([
                '--rpc_authentication=required',
                '--rpc_encryption=required',
                '--keytab_file={0}'.format(params.kudu_keytab),
                '--principal={0}'.format(params.kudu_principal_name),
            ])

        if params.kudu_enable_tls and params.kudu_tls_cert_file and params.kudu_tls_private_key_file:
            cmd.extend([
                '--rpc_certificate_file={0}'.format(params.kudu_tls_cert_file),
                '--rpc_private_key_file={0}'.format(params.kudu_tls_private_key_file),
                '--webserver_certificate_file={0}'.format(params.kudu_tls_cert_file),
                '--webserver_private_key_file={0}'.format(params.kudu_tls_private_key_file),
            ])
            if params.kudu_tls_ca_cert_file:
                cmd.append('--rpc_ca_certificate_file={0}'.format(params.kudu_tls_ca_cert_file))

        for raw_flag in str(params.master_additional_flags or '').splitlines():
            flag = raw_flag.strip()
            if not flag or flag.startswith('#'):
                continue
            cmd.append(flag)

        Logger.info(
            "Running ADD_KUDU_MASTER on host {0} with seed master(s) [{1}] and target [{2}]".format(
                params.hostname,
                seed_master_addrs,
                local_entry['address']
            )
        )
        Execute(cmd, user=params.kudu_user, environment=runtime_env, logoutput=True)
        Logger.info(
            "ADD_KUDU_MASTER completed. Next steps: update master_addresses/tserver_master_addrs for all hosts, "
            "restart existing masters one-by-one, then start this new master and restart tservers."
        )

    def configure(self, env):
        import params
        env.set_params(params)

        Directory(
            params.kudu_conf_dir,
            owner='root',
            group='root',
            create_parents=True,
            mode=0o755
        )
        Directory(
            params.kudu_log_dir,
            owner=params.kudu_user,
            group=params.kudu_group,
            create_parents=True,
            mode=0o755
        )
        Directory(
            params.kudu_run_dir,
            owner=params.kudu_user,
            group=params.kudu_group,
            create_parents=True,
            mode=0o755
        )
        if params.enable_ranger_kudu and params.ranger_kudu_receiver_fifo_dir:
            Directory(
                params.ranger_kudu_receiver_fifo_dir,
                owner=params.kudu_user,
                group=params.kudu_group,
                create_parents=True,
                mode=0o755
            )

        if params.kudu_enable_tls:
            tls_paths = [params.kudu_tls_cert_file, params.kudu_tls_private_key_file, params.kudu_tls_ca_cert_file]
            for tls_path in tls_paths:
                if not tls_path:
                    continue
                tls_parent = os.path.dirname(tls_path)
                if not tls_parent:
                    continue
                Directory(
                    tls_parent,
                    owner='root',
                    group='root',
                    create_parents=True,
                    mode=0o755
                )

        for data_dir in self._split_dirs(params.master_wal_dir):
            Directory(data_dir, owner=params.kudu_user, group=params.kudu_group, create_parents=True, mode=0o755)
        for data_dir in self._split_dirs(params.master_data_dirs):
            Directory(data_dir, owner=params.kudu_user, group=params.kudu_group, create_parents=True, mode=0o755)
        for data_dir in self._split_dirs(params.tserver_wal_dir):
            Directory(data_dir, owner=params.kudu_user, group=params.kudu_group, create_parents=True, mode=0o755)
        for data_dir in self._split_dirs(params.tserver_data_dirs):
            Directory(data_dir, owner=params.kudu_user, group=params.kudu_group, create_parents=True, mode=0o755)

        File(
            format('{kudu_conf_dir}/master.gflagfile'),
            owner='root',
            group='root',
            content=Template('master.gflagfile.j2'),
            mode=0o644
        )

        File(
            format('{kudu_conf_dir}/tserver.gflagfile'),
            owner='root',
            group='root',
            content=Template('tserver.gflagfile.j2'),
            mode=0o644
        )

    def start_kudu(self, role):
        import params
        meta = self._component_meta(role, params)
        self._kinit_if_needed(params)

        runtime_env = os.environ.copy()
        runtime_env['KUDU_CONF_DIR'] = params.kudu_conf_dir
        runtime_env['KUDU_LOG_DIR'] = params.kudu_log_dir
        runtime_env['KUDU_RUN_DIR'] = params.kudu_run_dir

        command = '{0} start {1}'.format(meta['binary'], meta['role_arg'])
        Execute(command, user=params.kudu_user, environment=runtime_env, logoutput=True)

    def stop_kudu(self, role):
        import params
        meta = self._component_meta(role, params)

        runtime_env = os.environ.copy()
        runtime_env['KUDU_CONF_DIR'] = params.kudu_conf_dir
        runtime_env['KUDU_LOG_DIR'] = params.kudu_log_dir
        runtime_env['KUDU_RUN_DIR'] = params.kudu_run_dir

        command = '{0} stop {1}'.format(meta['binary'], meta['role_arg'])
        Execute(command, user=params.kudu_user, environment=runtime_env, logoutput=True)

    def status_kudu(self, role):
        import params
        meta = self._component_meta(role, params)
        check_process_status(meta['pid_file'])
