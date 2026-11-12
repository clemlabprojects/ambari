'''
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
'''
import copy
import os
import sys
from unittest import TestCase
from unittest.mock import patch

from resource_management.core.logger import Logger
from resource_management.libraries.functions import ranger_default_policies as rdp
from resource_management.libraries.functions.ranger_default_policies import (
  RangerDefaultPolicies, RangerDefaultPoliciesError, merge_policy, policy_item)

Logger.initialize_logger()

NIFI_SCRIPTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', '..', '..', 'ambari-server', 'src',
                            'main', 'resources', 'stacks', 'ODP', '1.2', 'services', 'NIFI', 'package', 'scripts')


class FakeRanger(RangerDefaultPolicies):
  """Ranger Admin in memory: policies by id, with the 400 answers of the real one."""

  def __init__(self, policies=(), lose_next_updates=0):
    RangerDefaultPolicies.__init__(self, 'https://ranger:6182', 'admin', 'secret')
    self.policies = {p['id']: copy.deepcopy(p) for p in policies}
    self.lose_next_updates = lose_next_updates
    self.calls = []

  def _call(self, method, path, payload=None):
    self.calls.append((method, path))
    if method == 'GET' and '/policy?policyName=' in path:
      name = rdp.urllib.parse.unquote(path.split('policyName=')[1])
      return 200, [copy.deepcopy(p) for p in self.policies.values() if p['name'] == name]
    if method == 'POST' and path.endswith('/api/policy'):
      for p in self.policies.values():
        if p['resources'] == payload['resources']:
          return 400, {'msgDesc': '{0} policy-name=[{1}]'.format(rdp.MATCHING_RESOURCE_MARKER, p['name'])}
      new = copy.deepcopy(payload)
      new['id'] = len(self.policies) + 1
      self.policies[new['id']] = new
      return 200, new
    if method == 'PUT' and '/api/policy/' in path:
      if self.lose_next_updates:
        self.lose_next_updates -= 1  # another host wrote its own version at the same time
        return 200, payload
      self.policies[payload['id']] = copy.deepcopy(payload)
      return 200, payload
    raise AssertionError('unexpected call {0} {1}'.format(method, path))


def policy(pid, name, values, items):
  return {'id': pid, 'name': name, 'service': 'c_nifi',
          'resources': {'nifi-resource': {'values': list(values), 'isExcludes': False, 'isRecursive': False}},
          'policyItems': items}


@patch.object(rdp.time, 'sleep', lambda *_: None)
class TestRangerDefaultPolicies(TestCase):

  def test_merge_adds_only_what_is_missing(self):
    existing = policy(1, 'nifi - nodes', ['/proxy'], [policy_item(['READ', 'WRITE'], users=['admin-added', 'node1'])])
    changed = merge_policy(existing, {'nifi-resource': ['/proxy', '/flow']}, [policy_item(['WRITE', 'READ'], users=['node2'])])
    self.assertTrue(changed)
    self.assertEqual(['/proxy', '/flow'], existing['resources']['nifi-resource']['values'])
    self.assertEqual(1, len(existing['policyItems']))
    self.assertEqual(['admin-added', 'node1', 'node2'], existing['policyItems'][0]['users'])
    self.assertFalse(merge_policy(existing, {'nifi-resource': ['/flow']}, [policy_item(['READ', 'WRITE'], users=['node1'])]))

  def test_merge_keeps_items_with_other_accesses_apart(self):
    existing = policy(1, 'p', ['*'], [policy_item(['READ'], groups=['public'])])
    merge_policy(existing, {'nifi-resource': ['*']}, [policy_item(['READ', 'WRITE'], groups=['nifiadmin'], delegate_admin=True)])
    self.assertEqual(2, len(existing['policyItems']))
    self.assertEqual(['public'], existing['policyItems'][0]['groups'])

  def test_creates_a_missing_policy_with_the_ambari_label(self):
    ranger = FakeRanger()
    ranger.ensure_policy('c_nifi', 'nifi - admins', {'nifi-resource': ['*']}, [policy_item(['READ'], groups=['nifiadmin'])], 'd')
    created = list(ranger.policies.values())[0]
    self.assertEqual('nifi - admins', created['name'])
    self.assertEqual(['ambari-managed'], created['policyLabels'])

  def test_existing_complete_policy_is_not_rewritten(self):
    ranger = FakeRanger([policy(1, 'nifi - admins', ['*'], [policy_item(['READ'], groups=['nifiadmin'])])])
    ranger.ensure_policy('c_nifi', 'nifi - admins', {'nifi-resource': ['*']}, [policy_item(['READ'], groups=['nifiadmin'])], 'd')
    self.assertNotIn('PUT', [m for m, _ in ranger.calls])

  def test_update_overwritten_by_another_host_is_retried(self):
    ranger = FakeRanger([policy(1, 'nifi - nodes', ['/proxy'], [policy_item(['READ', 'WRITE'], users=['node1'])])], lose_next_updates=2)
    ranger.ensure_policy('c_nifi', 'nifi - nodes', {'nifi-resource': ['/proxy']}, [policy_item(['READ', 'WRITE'], users=['node2'])], 'd')
    self.assertEqual(['node1', 'node2'], ranger.policies[1]['policyItems'][0]['users'])
    self.assertEqual(3, [m for m, _ in ranger.calls].count('PUT'))

  def test_update_that_never_sticks_fails(self):
    ranger = FakeRanger([policy(1, 'nifi - nodes', ['/proxy'], [])], lose_next_updates=100)
    with self.assertRaises(RangerDefaultPoliciesError):
      ranger.ensure_policy('c_nifi', 'nifi - nodes', {'nifi-resource': ['/proxy']}, [policy_item(['READ'], users=['n'])], 'd', attempts=3)

  def test_access_is_granted_in_an_administrator_policy_on_the_same_resources(self):
    ranger = FakeRanger([policy(7, 'all - nifi-resource', ['*'], [policy_item(['READ', 'WRITE'], users=['admin'])])])
    ranger.ensure_policy('c_nifi', 'nifi - admins', {'nifi-resource': ['*']},
                         [policy_item(['READ', 'WRITE'], groups=['nifiadmin'], delegate_admin=True)], 'd')
    self.assertEqual(1, len(ranger.policies))
    self.assertEqual(['nifiadmin'], ranger.policies[7]['policyItems'][1]['groups'])


class TestNifiRangerPolicies(TestCase):

  @classmethod
  def setUpClass(cls):
    sys.path.insert(0, NIFI_SCRIPTS)
    import nifi_ranger_policies
    cls.m = nifi_ranger_policies

  @classmethod
  def tearDownClass(cls):
    sys.path.remove(NIFI_SCRIPTS)

  def test_identity_mapping_like_nifi(self):
    props = {'nifi.security.identity.mapping.pattern.kerb': '^(.*?)@(.*?)$', 'nifi.security.identity.mapping.value.kerb': '$1',
             'nifi.security.identity.mapping.pattern.dn': '^CN=([^,]+),.*$', 'nifi.security.identity.mapping.value.dn': '$1',
             'nifi.security.identity.mapping.transform.dn': 'UPPER', 'nifi.security.identity.mapping.pattern.empty': ''}
    prefix = 'nifi.security.identity.mapping.'
    self.assertEqual('alice', self.m.map_identity('alice@DEV11.EXAMPLE', props, prefix))
    self.assertEqual('W1.EXAMPLE', self.m.map_identity('CN=w1.example, O=Clemlab, C=FR', props, prefix))
    self.assertEqual('CN=w1, O=x', self.m.map_identity('CN=w1, O=x', {}, prefix))

  def test_keystore_owner_is_the_key_entry(self):
    out = ('Keystore type: JKS\n\nAlias name: ca\nEntry type: trustedCertEntry\n\nOwner: CN=Clemlab CA, O=Clemlab\n'
           '\nAlias name: worker11\nEntry type: PrivateKeyEntry\nCertificate chain length: 2\nCertificate[1]:\n'
           'Owner: CN=worker11.dev11, O=Clemlab, L=Paris, C=FR\nIssuer: CN=Clemlab CA, O=Clemlab\n')
    with patch.object(self.m.sudo, 'path_isfile', return_value=True), \
         patch.object(self.m.shell, 'call', return_value=(0, out)) as call:
      self.assertEqual('CN=worker11.dev11, O=Clemlab, L=Paris, C=FR', self.m.keystore_owner('keytool', '/ks.jks', 'JKS', 'pw'))
    self.assertNotIn('pw', ' '.join(call.call_args[0][0]))  # the password goes through a file, never the command line
