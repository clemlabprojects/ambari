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

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SMOKE_SCRIPT = (Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.0/"
                "services/ZOOKEEPER/package/files/zkSmoke.sh")


class TestOdpZookeeperSmoke(unittest.TestCase):
  def setUp(self):
    self.temp = tempfile.TemporaryDirectory(prefix="odp-zk-smoke-")
    self.addCleanup(self.temp.cleanup)
    self.root = Path(self.temp.name)
    self.state = self.root / "znode"
    self.output = self.root / "zkSmoke.out"
    self.script = self.root / "zkSmoke.sh"
    # Exercise the real shell logic without requiring root or changing users.
    self.script.write_text(SMOKE_SCRIPT.read_text().replace(
      "/var/lib/ambari-agent/ambari-sudo.sh su $smoke_user -s /bin/bash - -c", "bash -c"))
    (self.root / "zookeeper-env.sh").write_text("# No JVM needed for this shell regression test.\n")
    (self.root / "zoo.cfg").write_text(
      "server.1=node1:2888:3888\nserver.2=node2:2888:3888\nserver.3=node3:2888:3888\n")
    self.cli = self.root / "zkCli.sh"
    self.cli.write_text("""#!/usr/bin/env bash
read -r action path value
case "$action" in
  delete)
    if [ -f "$ZK_TEST_STATE" ]; then
      rm "$ZK_TEST_STATE"
    else
      echo 'Node does not exist: /zk_smoketest' >&2
      echo 'ERROR ServiceUtils - Exiting JVM with code 1'
      exit 1
    fi
    ;;
  create)
    case "${ZK_TEST_CREATE_FAILURE:-}" in
      stderr) echo 'ERROR RollingFileAppender: Permission denied' >&2 ;;
      exit) echo 'Unable to create znode' >&2; exit 1 ;;
    esac
    printf '%s\\n' "$value" > "$ZK_TEST_STATE"
    echo 'Created /zk_smoketest'
    ;;
  get) cat "$ZK_TEST_STATE" ;;
  ls) echo '[zk_smoketest]' ;;
esac
""")
    self.cli.chmod(0o755)

  def run_smoke(self, failure=""):
    environment = dict(os.environ, ZK_TEST_STATE=str(self.state),
                       ZK_TEST_CREATE_FAILURE=failure)
    return subprocess.run(
      ["bash", str(self.script), str(self.cli), "smoke-user", str(self.root), "2181",
       "False", "/usr/bin/kinit", "unused", "unused", str(self.output)],
      env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
      text=True, timeout=10)

  def test_missing_znode_cleanup_does_not_fail_successful_create(self):
    result = self.run_smoke()
    self.assertEqual(0, result.returncode, result.stdout)
    self.assertIn("Zookeeper Smoke Test: Passed", result.stdout)
    for host in ("node1", "node2", "node3"):
      self.assertIn("Running test on host " + host, result.stdout)
    self.assertFalse(self.state.exists())
    self.assertIn("Exiting JVM with code 1", Path(str(self.output) + ".cleanup").read_text())

  def test_existing_znode_is_replaced_and_removed(self):
    self.state.write_text("leftover_data\n")
    result = self.run_smoke()
    self.assertEqual(0, result.returncode, result.stdout)
    self.assertIn("Zookeeper Smoke Test: Passed", result.stdout)
    self.assertFalse(self.state.exists())

  def test_create_logging_errors_on_stderr_are_not_hidden(self):
    self.state.write_text("leftover_data\n")
    result = self.run_smoke("stderr")
    self.assertNotEqual(0, result.returncode, result.stdout)
    self.assertIn("Permission denied", result.stdout)
    self.assertNotIn("Zookeeper Smoke Test: Passed", result.stdout)

  def test_create_nonzero_exit_is_not_hidden(self):
    result = self.run_smoke("exit")
    self.assertNotEqual(0, result.returncode, result.stdout)
    self.assertIn("Unable to create znode", result.stdout)
    self.assertNotIn("Zookeeper Smoke Test: Passed", result.stdout)

  def test_repeated_checks_pass_from_clean_state(self):
    for _ in range(2):
      result = self.run_smoke()
      self.assertEqual(0, result.returncode, result.stdout)
      self.assertFalse(self.state.exists())
