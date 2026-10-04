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

import ast
import importlib.util
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

from resource_management.core.environment import Environment
from resource_management.core.logger import Logger
from resource_management.core.source import InlineTemplate
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.stack_features import check_stack_feature, get_stack_feature_version
from resource_management.libraries.script.script import Script


STACKS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP"
SCRIPTS = STACKS / "1.2/services/SPARK3/package/scripts"


class TestSparkJavaRuntime(unittest.TestCase):
  def resolve(self, version, secondary="/jdk21", host_fallback=False,
              primary="/jdk8", direction=None, command="CUSTOM_COMMAND"):
    config = {
      "clusterLevelParams": {"stack_name": "ODP", "stack_version": "1.2"},
      "commandParams": {"version": version},
      "roleCommand": command,
      "ambariLevelParams": {"java_home": primary},
      "hostLevelParams": {},
      "configurations": {"cluster-env": {
        "stack_features": (STACKS / "1.3/properties/stack_features.json").read_text()}},
    }
    level = "hostLevelParams" if host_fallback else "ambariLevelParams"
    config[level]["secondary_java_home"] = secondary
    if direction:
      config["commandParams"]["upgrade_direction"] = direction
    names = {"version_for_stack_feature_checks", "java_home", "spark_java_home"}
    tree = ast.parse((SCRIPTS / "params.py").read_text())
    # Isolate the runtime selector from unrelated service configuration imports.
    tree.body = [node for node in tree.body if
                 (isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id in names
                                                       for target in node.targets)) or
                 (isinstance(node, ast.If) and "SECONDARY_JAVA_HOME_SUPPORT" in ast.dump(node.test))]
    namespace = dict(config=config, default=default, StackFeature=StackFeature,
                     check_stack_feature=check_stack_feature,
                     get_stack_feature_version=get_stack_feature_version)
    with patch.object(Script, "get_config", return_value=config), patch.object(Logger, "logger", Mock()):
      exec(compile(tree, str(SCRIPTS / "params.py"), "exec"), namespace)
    return namespace

  def test_older_stacks_keep_primary(self):
    for version in ("1.2.4.0-115", "1.3.1.0-304"):
      for primary in ("/jdk8", "/jdk17"):
        with self.subTest(version=version, primary=primary):
          result = self.resolve(version, primary=primary)
          self.assertEqual(primary, result["java_home"])
          self.assertEqual(primary, result["spark_java_home"])

  def test_target_selects_secondary_without_changing_primary(self):
    for version in ("1.3.2.0-36", "1.3.3.0-1"):
      with self.subTest(version=version):
        result = self.resolve(version, direction="upgrade")
        self.assertEqual("/jdk21", result["java_home"])
        self.assertEqual("/jdk21", result["spark_java_home"])
        self.assertEqual("/jdk8", result["config"]["ambariLevelParams"]["java_home"])

  def test_downgrade_restart_uses_old_runtime(self):
    result = self.resolve("1.3.1.0-304", direction="downgrade")
    self.assertEqual("/jdk8", result["java_home"])

  def test_downgrade_stop_uses_source_runtime(self):
    with patch("resource_management.libraries.functions.upgrade_summary.get_source_version",
               return_value="1.3.2.0-36"):
      result = self.resolve("1.3.1.0-304", direction="downgrade", command="STOP")
    self.assertEqual("/jdk21", result["java_home"])

  def test_host_fallback_and_missing_secondary(self):
    self.assertEqual("/jdk21", self.resolve("1.3.2.0-36", host_fallback=True)["java_home"])
    for secondary in (None, ""):
      self.assertEqual("/jdk8", self.resolve("1.3.2.0-36", secondary=secondary)["java_home"])

  def test_secondary_path_is_not_hardcoded(self):
    result = self.resolve("1.3.2.0-36", secondary="/opt/java/vendor-21")
    self.assertEqual("/opt/java/vendor-21", result["java_home"])

  def test_history_schematool_and_thrift_use_selected_runtime(self):
    spec = importlib.util.spec_from_file_location("history_java_spark_service", SCRIPTS / "spark_service.py")
    service = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(service)
    params = SimpleNamespace(
      java_home="/jdk21",
      version=None, stack_version_formatted=None, security_enabled=False,
      spark_user="spark", hive_user="hive", spark_log_dir="/log/spark3",
      spark_history_server_start="history-start", spark_history_server_stop="history-stop",
      spark_thrift_server_start="thrift-start", spark_thrift_server_stop="thrift-stop",
      spark_history_server_pid_file="/run/history.pid", spark_thrift_server_pid_file="/run/thrift.pid",
      spark_thrift_server_conf_file="/conf/thrift", spark_thrift_cmd_opts_properties="",
      hive_schematool_bin="/hive/bin", hive_metastore_db_type="postgres",
      default_metastore_catalog="spark", default_fs="hdfs://cluster", spark_warehouse_dir="/warehouse")
    for name in ("jobhistoryserver", "sparkthriftserver"):
      for action in ("start", "stop"):
        with self.subTest(name=name, action=action), Environment(test_mode=True) as env, \
             patch.dict(sys.modules, {"params": params}), patch.object(service, "Execute") as execute, \
             patch.object(service, "File"), patch.object(service, "as_sudo", return_value="true"):
          env.set_params(params)
          service.spark_service(name, action=action)
          for call in execute.call_args_list:
            self.assertEqual("/jdk21", call.kwargs["environment"]["JAVA_HOME"])

  def test_livy_uses_selected_runtime(self):
    spec = importlib.util.spec_from_file_location("java_livy_service", SCRIPTS / "livy2_service.py")
    service = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(service)
    params = SimpleNamespace(java_home="/jdk21", livy2_user="livy", sudo="sudo",
                             livy2_server_pid_file="/run/livy.pid",
                             livy2_server_start="livy-start", livy2_server_stop="livy-stop")
    for action in ("start", "stop"):
      with self.subTest(action=action), Environment(test_mode=True) as env, \
           patch.dict(sys.modules, {"params": params}), patch.object(service, "Execute") as execute, \
           patch.object(service.get_user_call_output, "get_user_call_output", return_value=(0, "123", "")):
        env.set_params(params)
        service.livy2_service("livyserver", action=action)
        self.assertEqual("/jdk21", execute.call_args.kwargs["environment"]["JAVA_HOME"])

  def test_component_metadata_includes_current_livy_role(self):
    metadata = ET.parse(STACKS / "1.3/services/SPARK3/metainfo.xml")
    selectors = {c.findtext("name"): c.findtext("javaHomeSelector")
                 for c in metadata.findall(".//component")}
    for component in ("SPARK3_CLIENT", "SPARK3_JOBHISTORYSERVER",
                      "SPARK3_THRIFTSERVER", "SPARK3_LIVY2_SERVER"):
      self.assertEqual("secondary_java_home", selectors[component])

  def test_spark_and_livy_env_templates_use_selected_runtime(self):
    for version, expected in (("1.2.4.0-115", "/jdk8"), ("1.3.1.0-304", "/jdk8"),
                              ("1.3.2.0-36", "/jdk21")):
      params = self.resolve(version)
      with Environment(test_mode=True) as env:
        env.set_params(params)
        for site in ("spark3-env", "spark3-livy2-env"):
          tree = ET.parse(STACKS / "1.2/services/SPARK3/configuration" / (site + ".xml"))
          content = tree.find(".//property[name='content']/value").text
          line = next(line.strip() for line in content.splitlines() if line.strip().startswith("export JAVA_HOME="))
          self.assertEqual("export JAVA_HOME=" + expected, InlineTemplate(line).get_content())
        self.assertEqual(expected, InlineTemplate("{{spark_java_home}}").get_content())

  def test_container_defaults_render_and_allow_explicit_paths(self):
    from resource_management.libraries.providers.properties_file import PropertiesFileProvider
    for site in ("spark3-defaults", "spark3-thrift-sparkconf"):
      tree = ET.parse(STACKS / "1.3/services/SPARK3/configuration" / (site + ".xml"))
      properties = {p.findtext("name"): p.findtext("value") for p in tree.findall("property")}
      for version, expected in (("1.3.1.0-304", "/jdk8"), ("1.3.2.0-36", "/jdk21")):
        for override in (None, "/custom/jdk21"):
          configured = dict(properties)
          if override:
            configured["spark.executorEnv.JAVA_HOME"] = override
          with self.subTest(site=site, version=version, override=override), Environment(test_mode=True) as env, \
               patch("resource_management.libraries.providers.properties_file.File") as output:
            env.set_params(self.resolve(version))
            resource = SimpleNamespace(filename="/conf/spark.conf", dir=None, properties=configured,
                                       key_value_delimiter=" ", owner="spark", group="hadoop", mode=0o644,
                                       encoding="UTF-8")
            PropertiesFileProvider(resource).action_create()
            content = output.call_args.kwargs["content"].get_content()
            self.assertIn("spark.yarn.appMasterEnv.JAVA_HOME " + expected, content)
            self.assertIn("spark.executorEnv.JAVA_HOME " + (override or expected), content)

  def test_upgrade_defaults_precede_service_restart_and_preserve_overrides(self):
    for stack in ("1.2", "1.3"):
      definitions = ET.parse(STACKS / stack / "upgrades/config-upgrade.xml")
      for mode in ("upgrade", "nonrolling-upgrade"):
        pack = ET.parse(STACKS / stack / ("upgrades/" + mode + "-odp-1.3.xml"))
        groups = pack.findall("./order/group")
        names = [g.get("name") for g in groups]
        self.assertLess(names.index("SPARK3_JAVA_CONFIG"), names.index("SPARK3"))
        group = groups[names.index("SPARK3_JAVA_CONFIG")]
        self.assertEqual("UPGRADE", group.findtext("direction"))
        for stage in group.findall("execute-stage"):
          task_id = stage.find("task").get("id")
          definition = definitions.find(".//service[@name='SPARK3']/component[@name='SPARK3_CLIENT']/changes/definition[@id='" + task_id + "']")
          self.assertIsNotNone(definition)
          self.assertEqual(2, len(definition.findall("set")))
          for setting in definition.findall("set"):
            self.assertEqual("{{java_home}}", setting.get("value"))
            self.assertEqual(setting.get("key"), setting.get("if-key"))
            self.assertEqual("absent", setting.get("if-key-state"))


if __name__ == "__main__":
  unittest.main()
