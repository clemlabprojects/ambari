#!/usr/bin/env python3
"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

The Express upgrade steps of the Ozone Manager and SCM (prepare, finalize), against the commands of the
Apache Ozone non-rolling upgrade documentation.
"""

import importlib.util
import sys
import types
from pathlib import Path
import unittest

from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger

Logger.initialize_logger()

SCRIPTS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.0/services/OZONE/package/scripts"
sys.path.insert(0, str(SCRIPTS))


def load(script, **values):
  params = types.ModuleType("params")
  params.java_home = "/usr/lib/jvm/java-17"
  params.ozone_base_conf_dir = "/etc/ozone/conf/"
  params.ROLE_NAME_MAP_CONF = {"ozone-manager": "ozone.om", "ozone-scm": "ozone.scm"}
  params.hadoop_ozone_bin_dir = "/usr/odp/current/ozone-client/bin"
  params.ozone_user = "ozone"
  params.kinit_path_local = "/usr/bin/kinit"
  params.security_enabled = False
  params.ozone_om_ha_is_enabled = True
  params.ozone_om_ha_current_cluster_nameservice = "omservice"
  params.upgrade_type = "nonrolling"
  for key, value in values.items():
    setattr(params, key, value)
  sys.modules["params"] = params
  spec = importlib.util.spec_from_file_location("odp_" + script, SCRIPTS / (script + ".py"))
  module = importlib.util.module_from_spec(spec)
  spec.loader.exec_module(module)
  module.commands = []

  def execute(command, **kwargs):
    module.commands.append(command)
    if getattr(module, "fail_on", None) and module.fail_on in command:
      raise Fail("exit 1")
  module.Execute = execute
  return module


class FakeEnv(object):
  def set_params(self, params):
    pass


class TestOzoneManagerUpgradeSteps(unittest.TestCase):

  def test_prepare_with_om_ha(self):
    om = load("ozone_manager")
    om.prepare_om_server_for_upgrade()
    self.assertEqual(["ozone --config /etc/ozone/conf/ozone.om admin om prepare -id=omservice"], om.commands)

  def test_prepare_failure_fails_the_step(self):
    om = load("ozone_manager")
    om.fail_on = "om prepare"
    with self.assertRaises(Fail):
      om.prepare_om_server_for_upgrade()

  def test_finalize_with_om_ha_names_the_service(self):
    om = load("ozone_manager")
    om.finalize_ozone_manager_upgrade()
    self.assertEqual(["ozone --config /etc/ozone/conf/ozone.om admin om finalizeupgrade -id=omservice"], om.commands)

  def test_finalize_without_om_ha_has_no_service_id(self):
    om = load("ozone_manager", ozone_om_ha_is_enabled=False, ozone_om_ha_current_cluster_nameservice=None)
    om.finalize_ozone_manager_upgrade()
    self.assertEqual(["ozone --config /etc/ozone/conf/ozone.om admin om finalizeupgrade"], om.commands)

  def test_finalize_on_kerberos_logs_in_as_the_om(self):
    om = load("ozone_manager", security_enabled=True, ozone_om_user_keytab="/etc/security/keytabs/ozone.om.service.keytab",
              ozone_om_principal_name="om/master11@REALM")
    om.fail_on = "finalizeupgrade"
    with self.assertRaises(Fail):
      om.finalize_ozone_manager_upgrade()
    self.assertEqual("/usr/bin/kinit -kt /etc/security/keytabs/ozone.om.service.keytab om/master11@REALM", om.commands[0])


class TestStorageContainerManagerUpgradeSteps(unittest.TestCase):

  def test_finalize_runs_the_admin_command(self):
    scm = load("ozone_scm_server")
    scm.OzoneStorageContainerDefault().finalize_non_rolling_upgrade(FakeEnv())
    self.assertEqual(["ozone --config /etc/ozone/conf/ozone.scm admin scm finalizeupgrade"], scm.commands)

  def test_finalize_on_kerberos_logs_in_as_the_scm(self):
    scm = load("ozone_scm_server", security_enabled=True,
               ozone_scm_user_keytab="/etc/security/keytabs/ozone.scm.service.keytab",
               ozone_scm_principal_name="scm/master12@REALM")
    scm.fail_on = "finalizeupgrade"
    with self.assertRaises(Fail):
      scm.OzoneStorageContainerDefault().finalize_non_rolling_upgrade(FakeEnv())
    self.assertEqual("/usr/bin/kinit -kt /etc/security/keytabs/ozone.scm.service.keytab scm/master12@REALM", scm.commands[0])


if __name__ == "__main__":
  unittest.main()
