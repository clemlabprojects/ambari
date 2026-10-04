#!/usr/bin/env python
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import os
import re

from resource_management.core import shell
from resource_management.core.exceptions import Fail
from resource_management.libraries.script import Script


def check_java(home, expected_major):
  """Check an operator-selected JDK without changing alternatives or configuration."""
  result = {"home": home, "expected_major": int(expected_major), "valid": False}
  try:
    if not home or not os.path.isabs(home) or any(c.isspace() for c in home):
      raise ValueError("Use an absolute JDK path without whitespace")
    java = os.path.join(home, "bin", "java")
    if not os.path.isfile(java) or not os.access(java, os.X_OK):
      raise ValueError("bin/java is missing or not executable")
    if not os.path.isfile(os.path.join(home, "bin", "javac")):
      raise ValueError("Select a JDK, not a JRE (bin/javac is missing)")
    rc, output = shell.call((java, "-version"), timeout=20, quiet=True)
    match = re.search(r'(?:openjdk|java) version "(\d+)(?:\.(\d+))?', output)
    if rc or not match:
      raise ValueError("Cannot determine Java version: " + output[:500])
    major = int(match.group(2) if match.group(1) == "1" else match.group(1))
    result["major"] = major
    result["valid"] = major == int(expected_major)
    result["message"] = "Java {0}; expected {1}".format(major, expected_major)
  except Exception as error:
    result["message"] = str(error)
  return result


class CheckUpgradeJava(Script):
  def actionexecute(self, env):
    params = self.get_config()["commandParams"]
    results = {name: check_java(params[name + "_java_home"], params[name + "_java_major"])
               for name in ("primary", "secondary")}
    self.put_structured_out({"upgrade_java": results})
    if not all(result["valid"] for result in results.values()):
      raise Fail("Upgrade JDK validation failed; see the host results")


if __name__ == "__main__":
  CheckUpgradeJava().execute()
