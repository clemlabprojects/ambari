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
"""

import importlib.util
from pathlib import Path
import runpy
import subprocess
import sys
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

from ambari_commons import OSCheck
from resource_management.core.environment import Environment
from resource_management.core.logger import Logger
from resource_management.core.source import InlineTemplate
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.stack_features import check_stack_feature, get_stack_feature_version
from resource_management.libraries.script.script import Script


STACKS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP"
SERVICE = STACKS / "1.0/services/OOZIE"
SCRIPTS = SERVICE / "package/scripts"


class TestOozieSecondaryJava(unittest.TestCase):
  def resolve(self, version="1.3.2.0-36", secondary="/jdk21", host_secondary=None,
              windows=False, direction="upgrade"):
    config = {
      "clusterLevelParams": {"stack_name": "ODP", "stack_version": "1.2"},
      "commandParams": {"version": version, "upgrade_direction": direction},
      "roleCommand": "CUSTOM_COMMAND",
      "ambariLevelParams": {"java_home": "/jdk8", "java_version": "8",
                            "secondary_java_home": secondary, "ambari_java_home": "/ambari-jdk"},
      "hostLevelParams": {"secondary_java_home": host_secondary},
      "configurations": {"cluster-env": {
        "stack_features": (STACKS / "1.3/properties/stack_features.json").read_text()}},
    }
    platform_params = ModuleType("params_windows" if windows else "params_linux")
    platform_params.config = config
    platform_params.java64_home = "/jdk8"
    platform_params.ambari_java_home = "/ambari-jdk"
    platform_params.StackFeature = StackFeature
    platform_params.check_stack_feature = check_stack_feature
    with patch.object(Script, "get_config", return_value=config), patch.object(Logger, "logger", Mock()), \
         patch.object(OSCheck, "is_windows_family", return_value=windows), \
         patch.dict(sys.modules, {platform_params.__name__: platform_params}):
      platform_params.version_for_stack_feature_checks = get_stack_feature_version(config)
      result = runpy.run_path(str(SCRIPTS / "params.py"))
    self.assertEqual("/jdk8", config["ambariLevelParams"]["java_home"])
    self.assertEqual("8", config["ambariLevelParams"]["java_version"])
    self.assertEqual("/ambari-jdk", result["ambari_java_home"])
    return result

  def test_older_stacks_keep_primary(self):
    for version in ("1.2.4.0-115", "1.3.1.0-304"):
      with self.subTest(version=version):
        result = self.resolve(version=version)
        self.assertEqual(("/jdk8", "/jdk8", 8),
                         (result["java_home"], result["java64_home"], result["java_version"]))

  def test_upgrade_target_selects_secondary_for_template_and_launcher(self):
    result = self.resolve()
    self.assertEqual(("/jdk21", "/jdk21", 21),
                     (result["java_home"], result["java64_home"], result["java_version"]))

  def test_downgrade_target_restores_primary(self):
    result = self.resolve(version="1.3.1.0-304", direction="downgrade")
    self.assertEqual("/jdk8", result["java_home"])

  def test_host_fallback_and_missing_secondary(self):
    for secondary in (None, ""):
      with self.subTest(secondary=secondary):
        result = self.resolve(secondary=secondary, host_secondary="/host-jdk21")
        self.assertEqual("/host-jdk21", result["java_home"])
        result = self.resolve(secondary=secondary)
        self.assertEqual(("/jdk8", "/jdk8", 8),
                         (result["java_home"], result["java64_home"], result["java_version"]))

  def test_ambari_secondary_precedes_host_fallback(self):
    result = self.resolve(host_secondary="/host-jdk21")
    self.assertEqual("/jdk21", result["java_home"])

  def test_windows_selection_is_unchanged(self):
    result = self.resolve(windows=True)
    self.assertEqual("/jdk8", result["java_home"])

  def test_existing_template_aligns_java_and_jre_and_modern_module_options(self):
    template = next(prop.findtext("value") for prop in ET.parse(SERVICE / "configuration/oozie-env.xml").getroot()
                    if prop.findtext("name") == "content")
    for version, expected in (("1.2.4.0-115", "/jdk8"), ("1.3.1.0-304", "/jdk8"),
                              ("1.3.2.0-36", "/jdk21")):
      with self.subTest(version=version):
        result = self.resolve(version=version)
        with Environment(test_mode=True):
          rendered = InlineTemplate(template, **result).get_content()
        output = subprocess.check_output(
          ["bash", "-c", rendered + '\nprintf "%s\\n" "$JAVA_HOME" "$JRE_HOME" "$JETTY_OPTS"'],
          env={"PATH": "/usr/bin:/bin"}, text=True).splitlines()
        self.assertEqual([expected, expected], output[:2])
        self.assertEqual(expected == "/jdk21", "--add-opens java.base/java.lang=ALL-UNNAMED" in output[2])

  def test_start_and_stop_use_selected_java(self):
    with patch.dict(sys.modules, {"oozie": SimpleNamespace(copy_atlas_hive_hook_to_dfs_share_lib=Mock())}):
      spec = importlib.util.spec_from_file_location("secondary_java_oozie_service", SCRIPTS / "oozie_service.py")
      service = importlib.util.module_from_spec(spec)
      spec.loader.exec_module(service)
    for version, expected in (("1.2.4.0-115", "/jdk8"), ("1.3.2.0-36", "/jdk21")):
      result = self.resolve(version=version)
      params = SimpleNamespace(**{name: value for name, value in result.items() if not name.startswith("__")})
      params.oozie_home = "/oozie"
      params.conf_dir = "/oozie/conf"
      params.security_enabled = False
      params.oozie_user = "oozie"
      params.pid_file = "/run/oozie.pid"
      params.oozie_tmp_dir = "/tmp/oozie"
      params.target = "/jdbc.jar"
      params.jdbc_driver_name = "test.Driver"
      params.upgrade_direction = "upgrade"
      for action in ("start", "stop"):
        with self.subTest(version=version, action=action), Environment(test_mode=True) as env, \
             patch.dict(sys.modules, {"params": params}), patch.object(service, "Execute") as execute, \
             patch.object(service, "Directory"), patch.object(service, "File"), \
             patch.object(service, "as_user", return_value="true"):
          env.set_params(params)
          service.oozie_service(action, upgrade_type="NON_ROLLING")
          self.assertEqual(expected, execute.call_args.kwargs["environment"]["JAVA_HOME"])


if __name__ == "__main__":
  unittest.main()
