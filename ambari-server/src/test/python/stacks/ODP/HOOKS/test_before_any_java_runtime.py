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

from resource_management.core.environment import Environment
from resource_management.core.exceptions import Fail


HOOK_SCRIPTS = Path(__file__).resolve().parents[5] / "main/resources/stack-hooks/before-ANY/scripts"

JAVA_VERSIONS = {
  "/jdk8": 'openjdk version "1.8.0_462"',
  "/jdk17": 'openjdk version "17.0.16" 2025-07-15',
  "/jdk21": 'openjdk version "21.0.8" 2025-07-15',
}


def java_version(command, **kwargs):
  return 0, JAVA_VERSIONS[command[0][:-len("/bin/java")]]


class TestBeforeAnyJavaRuntime(unittest.TestCase):

  def setUp(self):
    self.params = ModuleType("params")
    self.params.stack_version_unformatted = "1.3"
    self.params.java_requirement_stack_version = "1.3.2.0"
    self.params.required_primary_java_major = 17
    self.params.required_secondary_java_major = 21
    self.params.java_home = "/jdk17"
    self.params.secondary_java_home = "/jdk21"
    self.params.java_home_selector = "secondary_java_home"
    self.params.enforce_java_runtime = True
    sys.modules["params"] = self.params

    spec = importlib.util.spec_from_file_location("odp_before_any_shared_initialization",
                                                  HOOK_SCRIPTS / "shared_initialization.py")
    self.shared = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(self.shared)

  def tearDown(self):
    sys.modules.pop("params", None)

  def check(self):
    with Environment("/") as env, patch.object(self.shared.shell, "call", side_effect=java_version):
      env.set_params(self.params)
      self.shared.check_java_runtime()

  def test_required_jdks_pass(self):
    self.check()

  def test_jdk8_primary_fails_with_the_setup_to_fix(self):
    self.params.java_home = "/jdk8"
    with self.assertRaises(Fail) as error:
      self.check()
    self.assertIn("ODP 1.3.2.0 requires JDK 17 as the primary Java home, but /jdk8 is JDK 8", str(error.exception))
    self.assertIn("--java-home", str(error.exception))

  def test_stop_only_warns_on_a_wrong_jdk(self):
    self.params.java_home = "/jdk8"
    self.params.enforce_java_runtime = False
    with patch.object(self.shared.Logger, "warning") as warning:
      self.check()
    self.assertIn("but /jdk8 is JDK 8", warning.call_args[0][0])

  def test_missing_secondary_fails_for_secondary_components(self):
    self.params.secondary_java_home = ""
    with self.assertRaises(Fail) as error:
      self.check()
    self.assertIn("requires JDK 21 as the secondary Java home, but none is configured", str(error.exception))
    self.assertIn("--secondary-java-home", str(error.exception))

  def test_secondary_is_not_checked_for_primary_components(self):
    self.params.secondary_java_home = ""
    self.params.java_home_selector = None
    self.check()

  def test_stack_versions_without_requirements_are_not_checked(self):
    self.params.required_primary_java_major = None
    self.params.java_home = "/jdk8"
    self.check()


if __name__ == "__main__":
  unittest.main()
