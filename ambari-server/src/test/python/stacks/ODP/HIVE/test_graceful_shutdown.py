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
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
from xml.etree import ElementTree

from resource_management.core.environment import Environment
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.libraries.script.script import Script


STACKS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP"
SCRIPTS = STACKS / "1.0/services/HIVE/package/scripts"
SPEC = importlib.util.spec_from_file_location("hive_graceful_service", SCRIPTS / "hive_service.py")
SERVICE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SERVICE)


class TestGracefulShutdown(unittest.TestCase):
  def config(self, target="1.3.2.0-36"):
    return {
      "clusterLevelParams": {"stack_name": "ODP", "stack_version": "1.3"},
      "commandParams": {"version": target, "command_timeout": "3600"},
      "configurations": {
        "cluster-env": {"stack_features": (STACKS / "1.3/properties/stack_features.json").read_text()},
        "hive-site": {}, "hiveserver2-site": {},
      },
    }

  def timeout(self, selected="1.3.2.0-36", config=None):
    config = config or self.config()
    params = SimpleNamespace(stack_name="ODP", config=config)
    with patch.dict(sys.modules, {"params": params}), \
         patch.object(Script, "get_config", return_value=config), \
         patch.object(Logger, "logger", Mock()), \
         patch.object(SERVICE, "get_component_version_from_symlink", return_value=selected) as version:
      result = SERVICE.hiveserver2_stop_timeout()
      version.assert_called_once_with("ODP", "hive-server2")
      return result

  def test_feature_uses_selected_binaries_not_upgrade_target(self):
    for selected in ("1.2.4.0-115", "1.3.1.0-304", None):
      with self.subTest(selected=selected):
        self.assertIsNone(self.timeout(selected))

  def test_downgrade_still_drains_new_binaries(self):
    self.assertEqual(1805, self.timeout(config=self.config(target="1.3.1.0-304")))

  def test_default_timeout(self):
    self.assertEqual(1805, self.timeout())

  def test_configured_time_units(self):
    for value, expected in (("60", 65), ("60s", 65), ("2m", 125), ("2 minutes", 125),
                            ("60001ms", 66), ("0s", 35), ("1ns", 35)):
      with self.subTest(value=value):
        config = self.config()
        config["configurations"]["hive-site"]["hive.server2.graceful.stop.timeout"] = value
        self.assertEqual(expected, self.timeout(config=config))

  def test_hs2_site_overrides_hive_site(self):
    config = self.config()
    config["configurations"]["hive-site"]["hive.server2.graceful.stop.timeout"] = "60s"
    config["configurations"]["hiveserver2-site"]["hive.server2.graceful.stop.timeout"] = "2m"
    self.assertEqual(125, self.timeout(config=config))

  def test_invalid_timeout_fails_before_signalling(self):
    for value in ("bad", "-1s", "1fortnight"):
      with self.subTest(value=value):
        config = self.config()
        config["configurations"]["hive-site"]["hive.server2.graceful.stop.timeout"] = value
        with self.assertRaisesRegex(Fail, "Invalid hive.server2.graceful.stop.timeout"):
          self.timeout(config=config)

  def test_agent_timeout_must_allow_drain(self):
    config = self.config()
    config["commandParams"]["command_timeout"] = 1500
    with self.assertRaisesRegex(Fail, "Increase the command timeout"):
      self.timeout(config=config)

  def test_metainfo_allows_default_drain_and_restart(self):
    root = ElementTree.parse(STACKS / "1.3/services/HIVE/metainfo.xml")
    component = root.find("./services/service/components/component[name='HIVE_SERVER']")
    self.assertGreater(int(component.findtext("commandScript/timeout")), self.timeout() + 120)

  def stop(self, name="hiveserver2", pid="123\n", running=(0, 0, 1), timeout=35,
           times=(0, 1)):
    params = SimpleNamespace(
      hadoop_home="/hadoop", java64_home="/jdk21", tez_conf_dir="/tez/conf",
      hive_log_dir="/log/hive", hive_server_conf_dir="/conf/hs2",
      hive_metastore_conf_dir="/conf/hms", start_hiveserver2_path="/start-hs2",
      start_metastore_path="/start-hms", hive_user="hive", security_enabled=False,
      version_for_stack_feature_checks="1.3.2.0-36", sudo="sudo")
    status = SimpleNamespace(hive_pid="/run/hs2.pid", hive_metastore_pid="/run/hms.pid")
    with Environment(test_mode=True) as env, \
         patch.dict(sys.modules, {"params": params, "status_params": status}), \
         patch.object(Logger, "logger", Mock()) as logger, \
         patch.object(SERVICE, "Execute") as execute, patch.object(SERVICE, "File") as file, \
         patch.object(SERVICE, "check_stack_feature", return_value=True), \
         patch.object(SERVICE, "hiveserver2_stop_timeout", return_value=timeout) as grace, \
         patch.object(SERVICE.get_user_call_output, "get_user_call_output", return_value=(0, pid, "")), \
         patch.object(SERVICE.shell, "call", side_effect=[(rc, "") for rc in running]) as poll, \
         patch.object(SERVICE.time, "monotonic", side_effect=times), \
         patch.object(SERVICE.time, "sleep") as sleep:
      env.set_params(params)
      SERVICE.hive_service(name, action="stop")
      return execute, file, grace, poll, sleep, logger

  def test_graceful_stop_polls_pid_and_returns_when_exited(self):
    execute, file, grace, poll, sleep, logger = self.stop()
    grace.assert_called_once_with()
    self.assertEqual("sudo kill 123", execute.call_args_list[0].args[0])
    self.assertIn("sleep 0", execute.call_args_list[1].kwargs["not_if"])
    for call in poll.call_args_list:
      self.assertEqual("ps -p 123 >/dev/null 2>&1", call.args[0])
    sleep.assert_called_once_with(1)
    file.assert_called_once_with("/run/hs2.pid", action="delete")
    logger.warning.assert_not_called()

  def test_drain_deadline_keeps_force_kill_fallback(self):
    execute, _, _, _, sleep, logger = self.stop(running=(0, 0), times=(0, 36))
    self.assertEqual("sudo kill -9 123", execute.call_args_list[1].args[0])
    sleep.assert_not_called()
    logger.warning.assert_called_once()

  def test_legacy_hs2_retains_five_second_wait(self):
    execute, _, _, _, sleep, _ = self.stop(timeout=None, running=(0,))
    self.assertIn("sleep 5", execute.call_args_list[1].kwargs["not_if"])
    sleep.assert_not_called()

  def test_metastore_keeps_legacy_stop(self):
    execute, _, grace, poll, _, _ = self.stop(name="metastore", pid="456", running=())
    grace.assert_not_called()
    poll.assert_not_called()
    self.assertEqual("sudo kill 456", execute.call_args_list[0].args[0])
    self.assertIn("sleep 5", execute.call_args_list[1].kwargs["not_if"])

  def test_already_stopped_and_missing_pid_are_noops(self):
    for pid, running in (("", ()), ("123", (1,))):
      with self.subTest(pid=pid):
        execute, file, grace, _, _, _ = self.stop(pid=pid, running=running)
        execute.assert_not_called()
        grace.assert_not_called()
        file.assert_called_once_with("/run/hs2.pid", action="delete")

  def test_invalid_pid_cannot_signal_process_group(self):
    for pid in ("0", "1", "-1", "123; true"):
      with self.subTest(pid=pid), self.assertRaisesRegex(Fail, "Invalid HiveServer2 PID"):
        self.stop(pid=pid, running=())


if __name__ == "__main__":
  unittest.main()
