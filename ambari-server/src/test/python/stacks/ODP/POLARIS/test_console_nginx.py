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
  """The Console only needs the nginx program: a fresh deb install must not start or enable the system nginx."""

  @classmethod
  def setUpClass(cls):
    sys.path.insert(0, str(SCRIPTS))
    sys.modules["polaris"] = types.SimpleNamespace(configure_polaris=lambda *a, **k: None)
    import polaris_console
    cls.m = polaris_console

  @classmethod
  def tearDownClass(cls):
    sys.path.remove(str(SCRIPTS))
    sys.modules.pop("polaris", None)

  def run_install(self, ubuntu, dpkg_status=(1, ""), policy_exists=False):
    calls = []
    record = lambda kind: (lambda *a, **k: calls.append((kind, a, k)))
    with patch.object(self.m, "Package", record("Package")), patch.object(self.m, "File", record("File")), \
         patch.object(self.m, "Execute", record("Execute")), \
         patch.object(self.m.shell, "call", lambda *a, **k: dpkg_status), \
         patch.object(self.m.OSCheck, "is_ubuntu_family", staticmethod(lambda: ubuntu)), \
         patch.object(self.m.os.path, "exists", lambda p: policy_exists):
      self.m.install_console_nginx()
    return calls

  def test_fresh_deb_install_denies_service_starts_then_disables_the_system_nginx(self):
    calls = self.run_install(ubuntu=True)
    self.assertEqual(["File", "Package", "File", "Execute"], [c[0] for c in calls])
    self.assertEqual("/usr/sbin/policy-rc.d", calls[0][1][0])
    self.assertIn("exit 101", calls[0][2]["content"])
    self.assertEqual(("nginx",), calls[1][1])
    self.assertEqual("delete", calls[2][2]["action"])
    self.assertEqual("systemctl disable nginx.service", calls[3][1][0])

  def test_an_existing_policy_rc_d_is_kept(self):
    calls = self.run_install(ubuntu=True, policy_exists=True)
    self.assertEqual(["Package", "Execute"], [c[0] for c in calls])

  def test_an_nginx_already_installed_is_left_alone(self):
    self.assertEqual([], self.run_install(ubuntu=True, dpkg_status=(0, "install ok installed")))

  def test_rpm_install_only_installs_the_package(self):
    calls = self.run_install(ubuntu=False)
    self.assertEqual([("Package", ("nginx",), {})], calls)


if __name__ == "__main__":
  unittest.main()
