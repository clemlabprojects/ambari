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
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.3/services/POLARIS/package/scripts"


class TestConsoleNginx(unittest.TestCase):
  """
  The Console runs the nginx bundled in the Polaris tools package: no distribution nginx package is installed and no
  system service is touched; start and stop run the nginx program selected in params with its own prefix and config.
  """

  @classmethod
  def setUpClass(cls):
    sys.path.insert(0, str(SCRIPTS))
    sys.modules["polaris"] = types.SimpleNamespace(configure_polaris=lambda *a, **k: None,
                                                   verify_console_tls_at_start=lambda *a, **k: None)
    import polaris_console
    cls.m = polaris_console

  @classmethod
  def tearDownClass(cls):
    sys.path.remove(str(SCRIPTS))
    sys.modules.pop("polaris", None)
    sys.modules.pop("params", None)

  def run_command(self, command, nginx_bin, pid_file_exists=True):
    params = types.SimpleNamespace(
      polaris_console_nginx_bin=nginx_bin, polaris_console_nginx_prefix="/var/run/polaris/",
      polaris_console_nginx_conf="/etc/polaris/conf/nginx-console.conf", polaris_console_pid_file="/var/run/polaris/polaris-console.pid",
      polaris_console_start_command=None, polaris_console_stop_command=None, polaris_user="polaris", polaris_conf_dir="/etc/polaris/conf")
    sys.modules["params"] = params
    calls = []
    record = lambda kind: (lambda *a, **k: calls.append((kind, a, k)))
    env = types.SimpleNamespace(set_params=lambda p: None)
    console = self.m.PolarisConsole()
    with patch.object(self.m, "File", record("File")), patch.object(self.m, "Execute", record("Execute")), \
         patch.object(self.m, "format", lambda text: text.format(**vars(params))), \
         patch.object(self.m.os.path, "isfile", lambda p: pid_file_exists), \
         patch.object(console, "install_packages", record("install_packages")):
      getattr(console, command)(env)
    return calls

  def test_install_only_installs_the_polaris_packages(self):
    calls = self.run_command("install", "/usr/odp/current/polaris-tools/nginx/sbin/nginx")
    self.assertEqual(["install_packages"], [c[0] for c in calls])
    self.assertFalse(hasattr(self.m, "Package"), "the Console script must not install a distribution package itself")

  def test_start_runs_the_bundled_nginx_with_its_own_prefix_and_config(self):
    calls = self.run_command("start", "/usr/odp/current/polaris-tools/nginx/sbin/nginx")
    executes = [c for c in calls if c[0] == "Execute"]
    self.assertEqual(1, len(executes))
    self.assertEqual("/usr/odp/current/polaris-tools/nginx/sbin/nginx -p /var/run/polaris/ -c /etc/polaris/conf/nginx-console.conf",
                     executes[0][1][0])
    self.assertEqual("polaris", executes[0][2]["user"])

  def test_stop_asks_the_same_nginx_to_quit(self):
    calls = self.run_command("stop", "/usr/odp/current/polaris-tools/nginx/sbin/nginx")
    executes = [c for c in calls if c[0] == "Execute"]
    self.assertEqual(["/usr/odp/current/polaris-tools/nginx/sbin/nginx -p /var/run/polaris/ -c /etc/polaris/conf/nginx-console.conf -s quit"],
                     [c[1][0] for c in executes])

  def test_a_package_without_bundled_nginx_falls_back_to_the_nginx_of_the_host(self):
    calls = self.run_command("start", "nginx")
    self.assertEqual("nginx -p /var/run/polaris/ -c /etc/polaris/conf/nginx-console.conf",
                     [c for c in calls if c[0] == "Execute"][0][1][0])


if __name__ == "__main__":
  unittest.main()
