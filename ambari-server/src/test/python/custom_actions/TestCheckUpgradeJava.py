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

import unittest
from unittest.mock import patch

from check_upgrade_java import check_java, CheckUpgradeJava
from resource_management.core.exceptions import Fail


class TestCheckUpgradeJava(unittest.TestCase):
  def check(self, output, expected=21, rc=0):
    with patch('check_upgrade_java.os.path.isfile', return_value=True), \
         patch('check_upgrade_java.os.access', return_value=True), \
         patch('check_upgrade_java.shell.call', return_value=(rc, output)) as call:
      result = check_java('/opt/jdk', expected)
      call.assert_called_once_with(('/opt/jdk/bin/java', '-version'), timeout=20, quiet=True)
      return result

  def test_java_21(self):
    self.assertTrue(self.check('openjdk version "21.0.10" 2026-01-20')['valid'])

  def test_java_17(self):
    self.assertTrue(self.check('openjdk version "17.0.19"', 17)['valid'])

  def test_java_8_is_rejected(self):
    self.assertFalse(self.check('java version "1.8.0_492"', 17)['valid'])

  def test_java_25_is_rejected(self):
    self.assertFalse(self.check('openjdk version "25.0.1"')['valid'])

  def test_invalid_output_and_failure(self):
    self.assertFalse(self.check('not java')['valid'])
    self.assertFalse(self.check('openjdk version "21.0.1"', rc=1)['valid'])

  def test_relative_and_whitespace_paths_rejected(self):
    self.assertFalse(check_java('jdk21', 21)['valid'])
    self.assertFalse(check_java('/opt/jdk 21', 21)['valid'])

  def test_missing_and_non_executable_java(self):
    with patch('check_upgrade_java.os.path.isfile', return_value=False):
      self.assertFalse(check_java('/missing', 21)['valid'])
    with patch('check_upgrade_java.os.path.isfile', return_value=True), \
         patch('check_upgrade_java.os.access', return_value=False):
      self.assertFalse(check_java('/missing', 21)['valid'])

  def test_jre_rejected(self):
    with patch('check_upgrade_java.os.path.isfile', side_effect=[True, False]), \
         patch('check_upgrade_java.os.access', return_value=True):
      self.assertIn('JRE', check_java('/jre', 21)['message'])

  def test_timeout_is_a_failed_check(self):
    with patch('check_upgrade_java.os.path.isfile', return_value=True), \
         patch('check_upgrade_java.os.access', return_value=True), \
         patch('check_upgrade_java.shell.call', side_effect=Exception('timeout')):
      self.assertFalse(check_java('/jdk', 21)['valid'])

  def test_action_reports_both_results_before_failing(self):
    action = CheckUpgradeJava()
    params = {'primary_java_home': '/jdk17', 'secondary_java_home': '/jdk21',
              'primary_java_major': '17', 'secondary_java_major': '21'}
    with patch.object(action, 'get_config', return_value={'commandParams': params}), \
         patch.object(action, 'put_structured_out') as output, \
         patch('check_upgrade_java.check_java', side_effect=[{'valid': True}, {'valid': False}]):
      with self.assertRaises(Fail):
        action.actionexecute(None)
      self.assertEqual(set(output.call_args[0][0]['upgrade_java']), {'primary', 'secondary'})
