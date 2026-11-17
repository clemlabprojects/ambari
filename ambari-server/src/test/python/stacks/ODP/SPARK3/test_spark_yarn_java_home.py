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

SCRIPTS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.2/services/SPARK3/package/scripts"

AM, EXECUTOR = 'spark.yarn.appMasterEnv.JAVA_HOME', 'spark.executorEnv.JAVA_HOME'
JDK17, JDK21 = '/usr/lib/jvm/java-17', '/usr/lib/jvm/java-21'


class TestSparkYarnJavaHome(unittest.TestCase):
  """ODP 1.3 uses the ODP 1.2 SPARK3 scripts: YARN containers must run on the JDK of the Spark tier."""

  @classmethod
  def setUpClass(cls):
    sys.path.insert(0, str(SCRIPTS))
    sys.modules["params"] = types.SimpleNamespace()
    import setup_spark
    cls.m = setup_spark

  @classmethod
  def tearDownClass(cls):
    sys.path.remove(str(SCRIPTS))
    sys.modules.pop("params", None)

  def pin(self, conf, spark_java_home=JDK21, primary_java_home=JDK17):
    sys.modules["params"] = types.SimpleNamespace(spark_java_home=spark_java_home, primary_java_home=primary_java_home)
    self.m.pin_yarn_java_home(conf)
    return conf

  def test_secondary_jdk_is_pinned_for_the_application_master_and_executors(self):
    self.assertEqual({'spark.master': 'yarn', AM: JDK21, EXECUTOR: JDK21}, self.pin({'spark.master': 'yarn'}))

  def test_stack_default_placeholders_are_replaced(self):
    self.assertEqual({AM: JDK21, EXECUTOR: JDK21}, self.pin({AM: '{{java_home}}', EXECUTOR: ' '}))

  def test_a_path_set_by_the_administrator_is_kept(self):
    self.assertEqual({AM: '/opt/jdk-21', EXECUTOR: JDK21}, self.pin({AM: '/opt/jdk-21'}))

  def test_nothing_is_added_when_spark_runs_on_the_primary_jdk(self):
    self.assertEqual({}, self.pin({}, spark_java_home=JDK17))


if __name__ == '__main__':
  unittest.main()
