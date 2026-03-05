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

from resource_management.libraries.script.script import Script
from resource_management.core.resources.system import Directory, Execute, File
from resource_management.core.source import Template
from resource_management.core.exceptions import Fail
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
