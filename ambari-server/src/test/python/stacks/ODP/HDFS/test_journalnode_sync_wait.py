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
from unittest.mock import MagicMock, patch

SCRIPTS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.0/services/HDFS/package/scripts"


class TestJournalNodeSyncWait(unittest.TestCase):

  @classmethod
  def setUpClass(cls):
    from resource_management.core.logger import Logger
    Logger.initialize_logger()
    sys.path.insert(0, str(SCRIPTS))
    import journalnode_upgrade
    cls.m = journalnode_upgrade

  @classmethod
  def tearDownClass(cls):
    sys.path.remove(str(SCRIPTS))

  def setUp(self):
    self.params = types.SimpleNamespace(dfs_ha_enabled=True, command_phase="", journalnode_port="8481",
                                        dfs_ha_nameservices="ns1", https_only=True, hostname="jn1.example",
                                        security_enabled=True, hdfs_user="hdfs")
    sys.modules["params"] = self.params
    self.sleep = patch.object(self.m.time, "sleep").start()
    self.addCleanup(patch.stopall)
    self.addCleanup(sys.modules.pop, "params", None)

  def answers(self, *bodies):
    """get_user_call_output returns these JMX bodies, one per call (the last one repeats)."""
    calls = list(bodies)
    def call(cmd, user=None, quiet=False):
      return 0, calls.pop(0) if len(calls) > 1 else calls[0], ""
    return patch.object(self.m, "get_user_call_output", side_effect=call)

  def test_express_upgrade_does_not_wait(self):
    with self.answers("") as jmx:
      self.m.wait_for_this_jn_to_sync(MagicMock(), upgrade_type="nonrolling")
    jmx.assert_not_called()
    self.sleep.assert_not_called()

  def test_no_namenode_writing_does_not_wait(self):
    # a JournalNode started before the NameNodes answers JMX without the Journal-<ns> bean
    with self.answers('{"beans": []}'):
      self.m.wait_for_this_jn_to_sync(MagicMock())
    self.sleep.assert_not_called()

  def test_rolling_restart_waits_until_in_sync(self):
    with self.answers("", '{"beans": [{"CurrentLagTxns": 12}]}', '{"beans": [{"CurrentLagTxns": 0}]}') as jmx:
      self.m.wait_for_this_jn_to_sync(MagicMock(), upgrade_type="rolling")
    self.assertEqual(3, jmx.call_count)
    self.assertEqual(2, self.sleep.call_count)

  def test_rolling_restart_fails_while_lagging(self):
    from resource_management.core.exceptions import Fail
    with self.answers('{"beans": [{"CurrentLagTxns": 5}]}'):
      with self.assertRaises(Fail):
        self.m.wait_for_this_jn_to_sync(MagicMock(), upgrade_type="rolling")

  def test_unreachable_journalnode_does_not_block(self):
    with self.answers(""):
      self.m.wait_for_this_jn_to_sync(MagicMock())
    self.assertEqual(18, self.sleep.call_count)

  def test_read_journal_lag(self):
    for body, expected in (("", (self.m.JN_DOWN, None)), ("not json", (self.m.JN_DOWN, None)),
                           ('{"beans": []}', (self.m.JN_NO_WRITER, None)),
                           ('{"beans": [{"CurrentLagTxns": 3}]}', (self.m.JN_LAG, 3))):
      with self.answers(body) as jmx:
        self.assertEqual(expected, self.m.read_journal_lag("https://jn:8481/jmx", True, "hdfs", True))
      self.assertEqual(['curl', '--negotiate', '-u', ':', '-s', '-k', 'https://jn:8481/jmx'], jmx.call_args[0][0])
