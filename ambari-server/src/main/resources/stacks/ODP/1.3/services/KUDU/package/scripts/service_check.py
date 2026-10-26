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

import json
import os
import re

from resource_management.core import shell as ambari_shell
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Execute
from resource_management.libraries.functions.format import format
from resource_management.libraries.script.script import Script


class KuduServiceCheck(Script):
    _LOADGEN_ROWS = 1000

    def _resolve_kudu_cli(self, params):
        # the check can run on any Kudu host: every component link points to the same kudu package
        for candidate in ('/usr/odp/current/kudu-client/bin/kudu', params.kudu_cli_bin):
            if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
                return candidate
        raise Fail('Unable to locate the kudu command line tool on this host')

    def _run(self, params, kudu_cli, args):
        cmd = ' '.join([kudu_cli] + args)
        Logger.info('Running: {0}'.format(cmd))
        rc, output = ambari_shell.call(cmd, user=params.smokeuser, quiet=True)
        output = output or ''
        Logger.info(output)
        if rc != 0:
            raise Fail('kudu command failed (rc={0}): {1}\n{2}'.format(rc, cmd, output))
        return output

    def _json_rows(self, output, what):
        # the JSON list is the last line, after any client warnings
        for line in reversed(output.strip().splitlines()):
            line = line.strip()
            if line.startswith('['):
                return json.loads(line)
        raise Fail('Unexpected output from kudu {0} list:\n{1}'.format(what, output))

    def service_check(self, env):
        import params
        env.set_params(params)

        if params.security_enabled:
            Execute(format('{kinit_path_local} -kt {smoke_user_keytab} {smokeuser_principal}'),
                    user=params.smokeuser)

        kudu_cli = self._resolve_kudu_cli(params)
        masters = params.master_addresses

        # 1. every master answers and one of them is the leader
        master_rows = self._json_rows(self._run(params, kudu_cli, ['master', 'list', masters, '--format=json']),
                                      'master')
        if not any(row.get('role') == 'LEADER' for row in master_rows):
            raise Fail('No Kudu master is LEADER: {0}'.format(master_rows))
        if len(master_rows) != len(params.kudu_master_hosts):
            raise Fail('{0} Kudu masters configured, {1} in the master quorum: {2}'.format(
                len(params.kudu_master_hosts), len(master_rows), master_rows))

        # 2. tablet servers are registered with the leader master
        tserver_rows = self._json_rows(self._run(params, kudu_cli, ['tserver', 'list', masters, '--format=json']),
                                       'tserver')
        expected_tservers = len(params.kudu_tserver_hosts)
        if not tserver_rows:
            raise Fail('No Kudu tablet server is registered with the masters')
        if len(tserver_rows) < expected_tservers:
            Logger.warning('{0} of {1} Kudu tablet servers are registered with the masters'.format(
                len(tserver_rows), expected_tservers))

        # 3. create a table, write rows, scan them back and drop the table
        replicas = 3 if len(tserver_rows) >= 3 else 1
        output = self._run(params, kudu_cli, [
            'perf', 'loadgen', masters,
            '--num_threads=1',
            '--num_rows_per_thread={0}'.format(self._LOADGEN_ROWS),
            '--table_num_replicas={0}'.format(replicas),
            '--table_num_hash_partitions=2',
            '--run_scan',
        ])
        expected = re.search(r'expected rows:\s*(\d+)', output)
        actual = re.search(r'actual rows\s*:\s*(\d+)', output)
        if not expected or not actual or expected.group(1) != actual.group(1) or \
                int(actual.group(1)) != self._LOADGEN_ROWS:
            raise Fail('Kudu read/write check did not scan back the {0} rows written:\n{1}'.format(
                self._LOADGEN_ROWS, output))
        Logger.info('Kudu service check passed: {0} master(s), {1} tablet server(s), {2} rows written and read '
                    'back with {3} replica(s)'.format(len(master_rows), len(tserver_rows), self._LOADGEN_ROWS,
                                                     replicas))


if __name__ == '__main__':
    KuduServiceCheck().execute()
