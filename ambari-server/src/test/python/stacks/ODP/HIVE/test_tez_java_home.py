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
import sys
from types import ModuleType
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.0/services/HIVE/package/scripts"
TEZ_ENV = "LD_LIBRARY_PATH=/usr/odp/${odp.version}/hadoop/lib/native"


class TestHiveServer2TezJavaHome(unittest.TestCase):

  def setUp(self):
    sys.path.insert(0, str(SCRIPTS))
    self.params = ModuleType("params")
    self.params.hive_tez_java_home = "/usr/lib/jvm/java-21"
    self.params.tez_site = {"tez.am.launch.env": TEZ_ENV, "tez.task.launch.env": TEZ_ENV}
    sys.modules["params"] = self.params
    spec = importlib.util.spec_from_file_location("odp_hive_script", SCRIPTS / "hive.py")
    self.hive = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(self.hive)

  def tearDown(self):
    sys.modules.pop("params", None)
    sys.path.remove(str(SCRIPTS))

  def hiveserver2_site(self, configured):
    with patch.object(self.hive, "get_config", return_value=configured):
      return self.hive.hiveserver2_site()

  def test_tez_containers_get_the_hive_jdk(self):
    site = self.hiveserver2_site({"hive.metastore.metrics.enabled": "true"})
    self.assertEqual(TEZ_ENV + ",JAVA_HOME=/usr/lib/jvm/java-21", site["tez.am.launch.env"])
    self.assertEqual(TEZ_ENV + ",JAVA_HOME=/usr/lib/jvm/java-21", site["tez.task.launch.env"])
    self.assertEqual("true", site["hive.metastore.metrics.enabled"])

  def test_a_configured_launch_env_is_kept_and_its_java_home_replaced(self):
    site = self.hiveserver2_site({"tez.am.launch.env": "A=1,JAVA_HOME=/old"})
    self.assertEqual("A=1,JAVA_HOME=/usr/lib/jvm/java-21", site["tez.am.launch.env"])

  def test_nothing_is_added_when_hive_runs_on_the_stack_jdk(self):
    self.params.hive_tez_java_home = None
    self.assertEqual({"x": "1"}, self.hiveserver2_site({"x": "1"}))


if __name__ == "__main__":
  unittest.main()
