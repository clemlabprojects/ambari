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
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

SCRIPTS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.2/services/NIFI/package/scripts"

STALE = {'nifi.sensitive.props.key': 'NifiKey1', 'nifi.sensitive.props.algorithm': 'PBEWITHMD5AND256BITAES-CBC-OPENSSL',
         'nifi.flow.configuration.json.file': '/var/lib/nifi/conf/flow.json.gz'}
NEW_KEY, NEW_ALGORITHM = 'NifiUpgradeKey-2026', 'NIFI_PBKDF2_AES_GCM_256'


class TestFlowSensitivePropertiesMigration(unittest.TestCase):

  @classmethod
  def setUpClass(cls):
    from resource_management.core.logger import Logger
    Logger.initialize_logger()
    sys.path.insert(0, str(SCRIPTS))
    import nifi_sensitive_props
    cls.m = nifi_sensitive_props

  @classmethod
  def tearDownClass(cls):
    sys.path.remove(str(SCRIPTS))

  def setUp(self):
    m = self.m
    for target, value in ((m.sudo, 'path_isfile'), (m, 'non_empty_file'), (m, 'has_encrypted_values')):
      patch.object(target, value, return_value=True).start()
    patch.object(m, 'read_properties', side_effect=lambda path: dict(STALE) if path.endswith('nifi.properties') else {}).start()
    for name in ('remove_tree',):
      patch.object(m, name).start()
    for name in ('copy', 'chown', 'chmod', 'move', 'stat'):
      patch.object(m.sudo, name).start()
    patch.object(m.pwd, 'getpwnam').start()
    patch.object(m.grp, 'getgrnam').start()
    self.addCleanup(patch.stopall)
    self.migration = m.FlowSensitivePropertiesMigration('/usr/odp/current/nifi', '/usr/odp/current/nifi/conf', '/jdk', 'nifi', 'nifi')
    self.staged = []
    self.migration._stage = MagicMock(side_effect=lambda work, name, props, flows, values:
                                      self.staged.append((name, values)) or {f: f + '.staged' for f in flows})

  def test_flow_already_converted_under_the_previous_version_is_left_alone(self):
    # the new version's nifi.properties was seeded before the key/algorithm change; the flow already uses the new ones
    self.migration._encryptor = MagicMock()
    self.migration.run(NEW_KEY, NEW_ALGORITHM)
    self.assertEqual([('probe', {self.m.KEY: NEW_KEY, self.m.ALGORITHM: NEW_ALGORITHM})], self.staged)
    self.migration._encryptor.assert_called_once()
    self.m.sudo.move.assert_not_called()

  def test_a_real_change_is_still_re_encrypted(self):
    def encryptor(work, name, command, value):
      if name == 'probe':
        raise self.m.Fail('cannot decrypt')
    self.migration._encryptor = MagicMock(side_effect=encryptor)
    self.migration.run(NEW_KEY, NEW_ALGORITHM)
    self.assertEqual(['probe', 'stage', 'verify'], [name for name, _ in self.staged])
    self.assertEqual({self.m.KEY: 'NifiKey1', self.m.ALGORITHM: 'PBEWITHMD5AND256BITAES-CBC-OPENSSL'}, self.staged[1][1])
    self.assertEqual([('stage', 'SetSensitivePropertiesKey'), ('stage', 'SetSensitivePropertiesAlgorithm'),
                      ('verify', 'SetSensitivePropertiesKey')],
                     [(c[0][1], c[0][2]) for c in self.migration._encryptor.call_args_list[1:]])
    self.m.sudo.move.assert_called_once_with('/var/lib/nifi/conf/flow.json.gz.staged', '/var/lib/nifi/conf/flow.json.gz')

  def test_nifi2_uses_only_the_json_flow(self):
    # after the upgrade to NiFi 2 the nifi.properties on disk is still the NiFi 1 one: flow.xml.gz is not a NiFi 2 flow
    props = dict(STALE, **{'nifi.flow.configuration.file': '/var/lib/nifi/conf/flow.xml.gz'})
    self.m.read_properties.side_effect = lambda path: dict(props) if path.endswith('nifi.properties') else {}
    migration = self.m.FlowSensitivePropertiesMigration('/usr/odp/current/nifi', '/usr/odp/current/nifi/conf', '/jdk',
                                                        'nifi', 'nifi', json_flow_only=True)
    self.assertEqual(['/var/lib/nifi/conf/flow.json.gz'], migration._flows(props))
    written = {}
    with patch.object(self.m, 'write_properties_copy', side_effect=lambda src, dst, repl, drop: written.update(repl=repl, drop=drop)), \
         patch.object(self.m.sudo, 'makedirs'), patch.object(self.m.sudo, 'chown_recursive'):
      self.m.FlowSensitivePropertiesMigration._stage(migration, '/w', 'probe', '/conf/nifi.properties',
                                                    ['/var/lib/nifi/conf/flow.json.gz'], {self.m.KEY: NEW_KEY})
    self.assertEqual('/w/probe/flow.json.gz', written['repl']['nifi.flow.configuration.file'])
    self.assertIn('nifi.flow.configuration.json.file', written['drop'])
