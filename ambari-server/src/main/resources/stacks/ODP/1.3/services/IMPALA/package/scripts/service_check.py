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
import subprocess
import time

from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Execute
from resource_management.libraries.functions.format import format
from resource_management.libraries.script.script import Script


class ImpalaServiceCheck(Script):
    _HS2_WAIT_TIMEOUT_SECONDS = 120
    _HS2_WAIT_POLL_SECONDS = 5

    def _resolve_impala_shell(self, params):
        candidates = [
            params.impala_shell_path,
            os.path.join(params.impala_home_client, "bin", "impala-shell"),
            os.path.join(params.impala_home_client, "impala-shell"),
        ]

        for candidate in candidates:
            if candidate and os.path.isfile(candidate) and os.access(candidate, os.X_OK):
                return candidate

        try:
            shell_path = subprocess.check_output(
                ["bash", "-lc", "command -v impala-shell"],
                stderr=subprocess.STDOUT
            ).decode("utf-8").strip()
            if shell_path:
                return shell_path
        except Exception:
            pass

        raise Fail(
            "Unable to locate impala-shell. Checked: {0}".format(
                ", ".join(candidates + ["PATH"])
            )
        )

    def _build_check_command(self, params, impala_shell, host):
        command = [
            impala_shell,
            "--protocol=hs2",
            "-i", "{0}:{1}".format(host, params.impala_hs2_port),
            "-q", "select 1",
            "-B",
            "--quiet",
            "--kerberos_service_name=impala",
            "--client_connect_timeout_ms=10000",
        ]

        if params.security_enabled:
            command.append("-k")

        if params.client_services_ssl_enabled:
            command.append("--ssl")
            if params.ssl_client_ca_certificate:
                command.extend(["--ca_cert", params.ssl_client_ca_certificate])

        return " ".join(["'{0}'".format(arg.replace("'", "'\"'\"'")) for arg in command])

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

    def _wait_for_hs2_listener(self, host, port):
        deadline = time.time() + self._HS2_WAIT_TIMEOUT_SECONDS
        while time.time() < deadline:
            if self._is_reachable(host, port):
                return True
            Logger.info(
                "Impala HS2 listener is not ready on {0}:{1}. Waiting {2} seconds.".format(
                    host, port, self._HS2_WAIT_POLL_SECONDS
                )
            )
            time.sleep(self._HS2_WAIT_POLL_SECONDS)
        return False

    def service_check(self, env):
        import params
        env.set_params(params)

        if not params.impala_service_check_hosts:
            raise Fail("No IMPALA_DAEMON hosts are available for the Impala service check.")

        impala_shell = self._resolve_impala_shell(params)
        Logger.info("Using impala-shell at {0}".format(impala_shell))

        if params.security_enabled:
            Execute(
                format("{kinit_path_local} -kt {smoke_user_keytab} {smokeuser_principal}"),
                user=params.smokeuser
            )

        last_error = None
        for host in params.impala_service_check_hosts:
            Logger.info(
                "Running impala-shell service check against {0}:{1}".format(
                    host, params.impala_hs2_port
                )
            )
            try:
                if not self._wait_for_hs2_listener(host, params.impala_hs2_port):
                    raise Fail(
                        "Impala HS2 listener on {0}:{1} did not become reachable within {2} seconds.".format(
                            host, params.impala_hs2_port, self._HS2_WAIT_TIMEOUT_SECONDS
                        )
                    )
                Execute(
                    self._build_check_command(params, impala_shell, host),
                    user=params.smokeuser,
                    logoutput=True,
                    tries=3,
                    try_sleep=5
                )
                Logger.info("Impala service check succeeded on {0}:{1}".format(host, params.impala_hs2_port))
                return
            except Exception as err:
                last_error = err
                Logger.info("Impala service check failed on {0}:{1}".format(host, params.impala_hs2_port))

        raise Fail(
            "Impala service check failed for all targets ({0}). Last error: {1}".format(
                ", ".join(["{0}:{1}".format(host, params.impala_hs2_port) for host in params.impala_service_check_hosts]),
                last_error
            )
        )


if __name__ == "__main__":
    ImpalaServiceCheck().execute()
