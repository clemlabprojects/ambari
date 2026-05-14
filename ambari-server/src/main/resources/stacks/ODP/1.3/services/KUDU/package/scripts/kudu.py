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
import time

from resource_management.libraries.script.script import Script
from resource_management.core.resources.system import Directory, Execute, File
from resource_management.core.source import Template
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.core import shell as ambari_shell
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions.format import format


# Kudu's sys-catalog tablet has a fixed, hard-coded all-zeros id; the presence of
# tablet-meta/<this id> under the master's wal dir is the on-disk witness that
# the host has been onboarded into the Raft quorum.
SYS_CATALOG_TABLET_ID = '0' * 32


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

    # ------------------------------------------------------------------
    # Multi-master bootstrap helpers
    # ------------------------------------------------------------------
    def _sys_catalog_path(self, params):
        wal_dirs = self._split_dirs(params.master_wal_dir)
        if not wal_dirs:
            return None
        return os.path.join(wal_dirs[0], 'tablet-meta', SYS_CATALOG_TABLET_ID)

    def _has_local_sys_catalog(self, params):
        path = self._sys_catalog_path(params)
        if not path:
            return False
        # The Kudu master WAL dir is mode 0700 owned by kudu:kudu, so a plain
        # os.path.exists() under the ambari-agent's effective user (often
        # 'ambari' with NOPASSWD sudo, sometimes root) silently returns False
        # because the agent user can't traverse the parent dirs.
        #
        # `shell.call(..., user=<kudu_user>)` is the Ambari-idiomatic wrapper
        # that handles both deployments transparently: when the agent runs as
        # root it becomes a sudo no-op; when it runs as a non-root user it
        # wraps with `sudo -u <kudu_user>` (requires passwordless sudo, which
        # is standard for Ambari-managed hosts).
        try:
            rc, _ = ambari_shell.call(
                "test -f '{0}'".format(path),
                user=params.kudu_user,
                quiet=True,
            )
            return rc == 0
        except Exception as exc:
            Logger.warning(
                "Kudu sys-catalog stat failed for {0} (as {1}): {2}".format(
                    path, params.kudu_user, exc
                )
            )
            return False

    def _configured_master_entries(self, params):
        default_port = str(params.master_rpc_port) if params.master_rpc_port else '7051'
        return self._parse_master_addresses(params.master_addresses, default_port)

    def _is_multi_master(self, params):
        return len(self._configured_master_entries(params)) >= 2

    def _bootstrap_leader_host(self, params):
        override = getattr(params, 'bootstrap_leader_override', '') or ''
        override = override.strip()
        if override:
            return self._normalize_host(override)
        entries = self._configured_master_entries(params)
        hosts = sorted(entry['host'] for entry in entries)
        return hosts[0] if hosts else None

    def _i_am_bootstrap_leader(self, params):
        leader = self._bootstrap_leader_host(params)
        return bool(leader) and leader in self._local_host_aliases(params)

    def _local_master_entry(self, params):
        default_port = str(params.master_rpc_port) if params.master_rpc_port else '7051'
        local_aliases = self._local_host_aliases(params)
        for entry in self._configured_master_entries(params):
            if entry['host'] in local_aliases:
                return entry
        return self._parse_master_address(
            '{0}:{1}'.format(params.hostname, default_port), default_port
        )

    def _preceding_master_entries(self, params):
        """Masters lexicographically before me — they must already be quorum
        voters before I attempt `kudu master add`."""
        local_aliases = self._local_host_aliases(params)
        entries = sorted(self._configured_master_entries(params), key=lambda e: e['host'])
        preceding = []
        for entry in entries:
            if entry['host'] in local_aliases:
                break
            preceding.append(entry)
        return preceding

    def _runtime_env(self, params):
        runtime_env = os.environ.copy()
        runtime_env['KUDU_CONF_DIR'] = params.kudu_conf_dir
        runtime_env['KUDU_LOG_DIR'] = params.kudu_log_dir
        runtime_env['KUDU_RUN_DIR'] = params.kudu_run_dir
        return runtime_env

    def _run_kudu_cli(self, params, args, check=True):
        """Run `kudu ...` as the kudu user and capture combined output.

        Goes through `ambari_shell.call(..., user=<kudu_user>)` so the
        invocation works whether the ambari-agent runs as root or as a
        non-root user with passwordless sudo (the standard Ambari setup).

        Deliberately does NOT propagate the ambari-agent's `os.environ` into
        the kudu shell: the agent's env carries vars (e.g., HOME, PYTHONPATH,
        sourced via the `ambari` user) that, when re-exported inside a
        `su kudu -l` shell, can mis-direct the Kerberos client (KRB5CCNAME
        lookup) and cause `kudu master list` to fail with rc=1 and no output.
        The kudu CLI only needs the keytab/principal (resolved via login
        shell's own env after `su -l kudu`); KUDU_CONF_DIR etc. are not
        required for read-only CLI calls."""
        cmd = ' '.join([params.kudu_cli_bin] + [str(a) for a in args])
        try:
            rc, output = ambari_shell.call(
                cmd,
                user=params.kudu_user,
                quiet=True,
            )
        except Exception as exc:
            if check:
                raise Fail("kudu CLI failed to launch: {0}: {1}".format(cmd, exc))
            return 1, '', str(exc)
        if check and rc != 0:
            raise Fail("kudu CLI failed (rc={0}): {1}\n{2}".format(rc, cmd, output))
        return rc, output or '', ''

    def _quorum_voters(self, params, seed_addrs):
        """Return the set of normalized host strings currently registered as voters
        in the sys-catalog Raft config. Empty set on any failure (caller treats
        as "not ready yet")."""
        if not seed_addrs:
            return set()
        try:
            rc, out, err = self._run_kudu_cli(
                params,
                ['master', 'list', seed_addrs, '-format=tsv', '-columns=rpc-addresses'],
                check=False
            )
        except Exception as exc:
            Logger.info("Kudu `master list` raised: {0}".format(exc))
            return set()
        if rc != 0:
            Logger.info(
                "Kudu `master list {0}` returned rc={1}, output={2!r}".format(seed_addrs, rc, out)
            )
            return set()
        voters = set()
        for line in (out or '').splitlines():
            token = line.strip()
            if not token:
                continue
            # Skip any noise (warnings, log lines) that doesn't look like a master address.
            # Output may be "host:port" or "host:port,host:port" depending on Kudu version.
            for piece in token.split(','):
                entry = self._parse_master_address(piece.strip(), str(params.master_rpc_port) if params.master_rpc_port else '7051')
                if entry and '.' in entry['host']:
                    voters.add(entry['host'])
        if not voters:
            Logger.info(
                "Kudu `master list {0}` returned rc=0 but no voters parsed; raw output: {1!r}"
                .format(seed_addrs, out)
            )
        return voters

    def _wait_until_preceding_masters_are_voters(self, params, preceding, timeout_seconds):
        """Block until every preceding master appears in the Raft voter set
        (queried via any reachable preceding master as seed). Returns the list
        of reachable seeds on success; raises Fail on timeout."""
        expected_hosts = {entry['host'] for entry in preceding}
        deadline = time.time() + max(60, int(timeout_seconds))
        last_status = ''
        while time.time() < deadline:
            reachable_seeds = [e for e in preceding if self._is_reachable(e['host'], e['port'])]
            if reachable_seeds:
                seed_addrs = ','.join(e['address'] for e in reachable_seeds)
                voters = self._quorum_voters(params, seed_addrs)
                if expected_hosts.issubset(voters):
                    return reachable_seeds
                last_status = 'voters={0} expected={1}'.format(sorted(voters), sorted(expected_hosts))
            else:
                last_status = 'no preceding masters reachable yet'
            time.sleep(5)
        raise Fail(
            "Timed out after {0}s waiting for preceding Kudu masters to be quorum voters. "
            "Last status: {1}".format(timeout_seconds, last_status)
        )

    def _run_single_master_bootstrap(self, params):
        """Bootstrap-leader path. Idempotent: noop if local sys catalog exists.

        Starts the kudu-master daemon with --master_addresses overridden to just
        this host so the daemon will create the sys-catalog tablet, waits for
        the on-disk witness to appear, then stops the daemon. The persistent
        master.gflagfile is untouched; the subsequent normal start picks up the
        full multi-master config (initial Raft config = 1 voter, expanded later
        by followers via `kudu master add`)."""
        if self._has_local_sys_catalog(params):
            return

        default_port = str(params.master_rpc_port) if params.master_rpc_port else '7051'
        local_addr = '{0}:{1}'.format(self._normalize_host(params.hostname), default_port)
        Logger.info(
            "KUDU bootstrap-leader path: creating sys-catalog via single-master daemon "
            "(--master_addresses={0})".format(local_addr)
        )

        # --flush_threshold_secs=10 overrides Kudu's 120s MemRowSet flush
        # threshold so the freshly-created sys-catalog tablet metadata is
        # persisted to disk within ~10s instead of ~2min; without this, the
        # bootstrap daemon "succeeds" in memory (wins election, opens log) but
        # tablet-meta/<id> stays unwritten until the periodic flush kicks in.
        runtime_env = self._runtime_env(params)
        runtime_env['KUDU_MASTER_FLAGS'] = (
            '--master_addresses={0} --flush_threshold_secs=10'.format(local_addr)
        )

        start_cmd = '{0} start master'.format(params.kudu_master_bin)
        Execute(start_cmd, user=params.kudu_user, environment=runtime_env, logoutput=True)

        try:
            deadline = time.time() + max(30, int(params.bootstrap_timeout_seconds))
            while time.time() < deadline:
                if self._has_local_sys_catalog(params):
                    Logger.info("KUDU bootstrap-leader path: sys-catalog tablet metadata created.")
                    break
                time.sleep(2)
            else:
                raise Fail(
                    "Kudu bootstrap-leader daemon did not create sys-catalog within {0}s; "
                    "check /var/log/kudu/kudu-master.{{INFO,ERROR}} on this host."
                    .format(params.bootstrap_timeout_seconds)
                )
        finally:
            stop_cmd = '{0} stop master'.format(params.kudu_master_bin)
            Execute(stop_cmd, user=params.kudu_user, environment=runtime_env, logoutput=True,
                    ignore_failures=True)

    def _execute_master_add(self, params, seed_entries, local_entry):
        """Run `kudu master add` so the local host joins an existing quorum.

        Shared by the start-time follower path and the ADD_KUDU_MASTER custom
        command. Idempotent guard left to callers (they check sys-catalog
        presence beforehand)."""
        seed_master_addrs = ','.join(entry['address'] for entry in seed_entries)
        command_master_addrs = []
        seen = set()
        for entry in seed_entries + [local_entry]:
            if entry['address'] in seen:
                continue
            seen.add(entry['address'])
            command_master_addrs.append(entry['address'])

        runtime_env = self._runtime_env(params)
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
            # The rpc_*_file / webserver_*_file flags are marked experimental
            # in Kudu and refuse to take effect unless --unlock_experimental_flags
            # is also passed. The daemon's master.gflagfile already includes this,
            # but the CLI command needs it explicitly.
            cmd.extend([
                '--unlock_experimental_flags',
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
            "Running `kudu master add` on host {0}: seed=[{1}] new=[{2}]".format(
                params.hostname, seed_master_addrs, local_entry['address']
            )
        )
        # Pass cmd as a tuple, NOT a list: the Execute resource treats a list
        # as a sequence of separate commands to run (one per element), so a
        # list would have run `kudu` with no args (top-level help, rc=1)
        # before the real `kudu master add ...` invocation. Tuples are treated
        # as a single argv vector.
        Execute(tuple(cmd), user=params.kudu_user, environment=runtime_env, logoutput=True)

    def _run_follower_add(self, params):
        """Follower path at start time. Idempotent: noop if local sys catalog exists.

        Waits for every alphabetically-preceding master to be a quorum voter
        before issuing `kudu master add`. This serializes concurrent expansions
        and avoids racing Raft reconfigurations."""
        if self._has_local_sys_catalog(params):
            return

        self._assert_master_dirs_bootstrap_ready(params)
        self._kinit_if_needed(params)

        preceding = self._preceding_master_entries(params)
        if not preceding:
            raise Fail(
                "Follower path entered but no preceding master exists in master_addresses. "
                "Check bootstrap_leader_override and master_addresses configuration."
            )
        Logger.info(
            "KUDU follower path on {0}: waiting for preceding masters [{1}] to be quorum voters."
            .format(params.hostname, ', '.join(e['address'] for e in preceding))
        )
        reachable_seeds = self._wait_until_preceding_masters_are_voters(
            params, preceding, params.follower_wait_timeout_seconds
        )

        local_entry = self._local_master_entry(params)
        self._execute_master_add(params, reachable_seeds, local_entry)

    def add_kudu_master_to_cluster(self, env):
        """ADD_KUDU_MASTER custom command. Used when expanding an existing
        running cluster from a UI / API call (not from start). The start-time
        follower path now covers fresh installs and ansible-driven expansion
        automatically; this entry point remains for operator-driven adds."""
        import params
        env.set_params(params)

        self.configure(env)
        self._kinit_if_needed(params)
        self._assert_master_dirs_bootstrap_ready(params)

        default_port = str(params.master_rpc_port) if params.master_rpc_port else '7051'
        local_aliases = self._local_host_aliases(params)

        configured_masters = self._configured_master_entries(params)
        local_entry = None
        existing_entries = []
        for entry in configured_masters:
            if entry['host'] in local_aliases:
                if local_entry is None:
                    local_entry = entry
                continue
            existing_entries.append(entry)

        if local_entry is None:
            local_entry = self._parse_master_address(
                '{0}:{1}'.format(params.hostname, default_port), default_port
            )
            Logger.info(
                "Local host not present in master_addresses; using {0} as target."
                .format(local_entry['address'])
            )

        if not existing_entries:
            raise Fail(
                "No existing masters found in master_addresses after excluding local host "
                "({0}).".format(local_entry['host'])
            )

        reachable_existing = [e for e in existing_entries if self._is_reachable(e['host'], e['port'])]
        unreachable = [e['address'] for e in existing_entries if e not in reachable_existing]
        if not reachable_existing:
            raise Fail(
                "Unable to reach any existing master: {0}".format(
                    ', '.join(e['address'] for e in existing_entries)
                )
            )
        if unreachable:
            Logger.warning("Ignoring unreachable masters: {0}".format(', '.join(unreachable)))

        self._execute_master_add(params, reachable_existing, local_entry)
        Logger.info(
            "ADD_KUDU_MASTER completed. Update master_addresses/tserver_master_addrs everywhere, "
            "restart existing masters one-by-one, start this new master, restart tservers."
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

    def _ensure_master_onboarded(self, params):
        """For role='master' only: guarantee the local FS has the sys-catalog
        tablet before the normal `kudu.sh start master` runs.

        - Single-master cluster, or local catalog already present: noop.
        - Bootstrap leader, no catalog: single-master bootstrap.
        - Follower, no catalog: wait for preceding masters in quorum + `kudu master add`.
        """
        if self._has_local_sys_catalog(params):
            return
        if not self._is_multi_master(params):
            # Single-master: kudu-master auto-creates the sys catalog on first start.
            return
        if self._i_am_bootstrap_leader(params):
            self._run_single_master_bootstrap(params)
        else:
            self._run_follower_add(params)

    def start_kudu(self, role):
        import params
        meta = self._component_meta(role, params)
        self._kinit_if_needed(params)

        if role == 'master':
            self._ensure_master_onboarded(params)

        runtime_env = self._runtime_env(params)
        command = '{0} start {1}'.format(meta['binary'], meta['role_arg'])
        Execute(command, user=params.kudu_user, environment=runtime_env, logoutput=True)

    def stop_kudu(self, role):
        import params
        meta = self._component_meta(role, params)
        runtime_env = self._runtime_env(params)
        command = '{0} stop {1}'.format(meta['binary'], meta['role_arg'])
        Execute(command, user=params.kudu_user, environment=runtime_env, logoutput=True)

    def status_kudu(self, role):
        import params
        meta = self._component_meta(role, params)
        check_process_status(meta['pid_file'])
