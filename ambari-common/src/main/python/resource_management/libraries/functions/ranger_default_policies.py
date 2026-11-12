#!/usr/bin/env python3
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

Ensures the Ranger users, groups, service settings and policies a service needs, through the Ranger Admin REST API.

Every call is idempotent and only adds: a policy is found by name and created when missing; an existing one only gets
the resource values, users, groups and accesses it lacks. Nothing is removed, so changes made by administrators stay.
"""
import base64
import copy
import json
import random
import re
import time
import urllib.error
import urllib.parse
import urllib.request

from resource_management.core.logger import Logger

__all__ = ['RangerDefaultPolicies', 'RangerDefaultPoliciesError', 'policy_item', 'merge_policy']

AMBARI_MANAGED_LABEL = 'ambari-managed'
PUBLIC_GROUP = 'public'
# Ranger answers 400 with these when another policy already covers the same resources or uses the same name
MATCHING_RESOURCE_MARKER = 'Another policy already exists for matching resource'
CONFLICT_POLICY_NAME = re.compile(r'policy-name=\[([^\]]+)\]')
DUPLICATE_NAME_MARKER = 'already exists'


class RangerDefaultPoliciesError(Exception):
  pass


def policy_item(accesses, users=(), groups=(), delegate_admin=False):
  return {'accesses': [{'type': access, 'isAllowed': True} for access in accesses],
          'users': list(users), 'groups': list(groups), 'conditions': [], 'delegateAdmin': delegate_admin}


def _access_types(item):
  return sorted(a.get('type') for a in item.get('accesses') or [] if a.get('isAllowed', True))


def _merge_items(policy, items):
  """Adds the users and groups of each wanted item to the existing item granting the same accesses, or the item."""
  changed = False
  existing_items = policy.setdefault('policyItems', [])
  for wanted in items:
    target = next((item for item in existing_items
                   if _access_types(item) == _access_types(wanted)
                   and bool(item.get('delegateAdmin')) == bool(wanted.get('delegateAdmin'))
                   and not item.get('conditions')), None)
    if target is None:
      existing_items.append(copy.deepcopy(wanted))
      changed = True
      continue
    for key in ('users', 'groups'):
      present = target.setdefault(key, [])
      for principal in wanted.get(key) or []:
        if principal not in present:
          present.append(principal)
          changed = True
  return changed


def merge_policy(policy, resources, items):
  """Adds missing resource values and policy items to an existing policy; returns True when it changed."""
  changed = False
  for name, values in resources.items():
    resource = policy.setdefault('resources', {}).setdefault(name, {'values': [], 'isExcludes': False, 'isRecursive': False})
    present = resource.setdefault('values', [])
    for value in values:
      if value not in present:
        present.append(value)
        changed = True
  return _merge_items(policy, items) or changed


def _contains(policy, resources, items):
  probe = copy.deepcopy(policy)
  return not merge_policy(probe, resources, items)


class RangerDefaultPolicies(object):

  def __init__(self, ranger_url, username, password, timeout=30):
    self.base_url = ranger_url.rstrip('/')
    self.authorization = 'Basic ' + base64.b64encode('{0}:{1}'.format(username, password).encode()).decode()
    self.timeout = timeout

  def _call(self, method, path, payload=None):
    request = urllib.request.Request(self.base_url + path, method=method,
                                     data=json.dumps(payload).encode('utf-8') if payload is not None else None,
                                     headers={'Accept': 'application/json', 'Content-Type': 'application/json',
                                              'Authorization': self.authorization})
    try:
      response = urllib.request.urlopen(request, timeout=self.timeout)
      code, raw = response.getcode(), response.read().decode('utf-8')
    except urllib.error.HTTPError as e:
      code, raw = e.code, e.read().decode('utf-8', 'replace')
    except (urllib.error.URLError, OSError) as e:
      raise RangerDefaultPoliciesError('Ranger Admin {0} is not reachable: {1}'.format(self.base_url, e))
    try:
      body = json.loads(raw) if raw else {}
    except ValueError:
      body = raw
    return code, body

  def _checked(self, method, path, payload=None, what=''):
    code, body = self._call(method, path, payload)
    if code not in (200, 201, 204):
      raise RangerDefaultPoliciesError('{0} failed: HTTP {1} {2}'.format(what or method + ' ' + path, code, str(body)[:500]))
    return body

  # users and groups

  def ensure_users(self, names):
    for name in sorted(set(n for n in names if n)):
      found = self._checked('GET', '/service/xusers/users?name=' + urllib.parse.quote(name), what='Looking up Ranger user ' + name)
      if any(u.get('name') == name for u in found.get('vXUsers') or []):
        continue
      self._checked('POST', '/service/xusers/users/external',
                    {'name': name, 'firstName': name, 'status': 1, 'userSource': 0, 'userRoleList': ['ROLE_USER'],
                     'description': 'Created by Ambari for its default policies'}, what='Creating Ranger user ' + name)
      Logger.info("Created Ranger user '{0}'".format(name))

  def ensure_groups(self, names):
    for name in sorted(set(n for n in names if n and n != PUBLIC_GROUP)):
      found = self._checked('GET', '/service/xusers/groups?name=' + urllib.parse.quote(name), what='Looking up Ranger group ' + name)
      if any(g.get('name') == name for g in found.get('vXGroups') or []):
        continue
      self._checked('POST', '/service/xusers/secure/groups',
                    {'name': name, 'description': 'Created by Ambari for its default policies'},
                    what='Creating Ranger group ' + name)
      Logger.info("Created Ranger group '{0}'".format(name))

  # service

  def get_service(self, service_name):
    code, body = self._call('GET', '/service/public/v2/api/service/name/' + urllib.parse.quote(service_name))
    if code == 404 or (code == 400 and 'not found' in str(body).lower()):
      return None
    if code != 200:
      raise RangerDefaultPoliciesError('Reading Ranger service {0} failed: HTTP {1} {2}'.format(service_name, code, str(body)[:500]))
    return body

  def ensure_service_config_users(self, service_name, keys, users):
    """Adds users to comma-separated settings of the service, such as policy.download.auth.users."""
    users = [u for u in users if u]
    service = self.get_service(service_name)
    if service is None or not users:
      return
    configs = service.setdefault('configs', {})
    changed = False
    for key in keys:
      present = [u.strip() for u in (configs.get(key) or '').split(',') if u.strip()]
      missing = [u for u in users if u not in present]
      if missing:
        configs[key] = ','.join(present + missing)
        changed = True
    if changed:
      self._checked('PUT', '/service/public/v2/api/service/{0}'.format(service['id']), service,
                    what='Updating Ranger service ' + service_name)
      Logger.info("Ranger service '{0}': {1} now include {2}".format(service_name, ', '.join(keys), ', '.join(users)))

  # policies

  def get_policy(self, service_name, policy_name):
    found = self._checked('GET', '/service/public/v2/api/service/{0}/policy?policyName={1}'.format(
      urllib.parse.quote(service_name), urllib.parse.quote(policy_name)), what='Looking up Ranger policy ' + policy_name)
    return next((p for p in found or [] if isinstance(p, dict) and p.get('name') == policy_name), None)

  def ensure_policy(self, service_name, policy_name, resources, items, description, attempts=6):
    """Creates the policy or adds what it lacks. Concurrent hosts may update it at the same time: an update is checked
    by reading the policy back, and retried when another host overwrote it."""
    for attempt in range(attempts):
      existing = self.get_policy(service_name, policy_name)
      if existing is None:
        policy = {'service': service_name, 'name': policy_name, 'description': description, 'isEnabled': True,
                  'isAuditEnabled': True, 'policyLabels': [AMBARI_MANAGED_LABEL],
                  'resources': {name: {'values': list(values), 'isExcludes': False, 'isRecursive': False}
                                for name, values in resources.items()},
                  'policyItems': copy.deepcopy(items)}
        code, body = self._call('POST', '/service/public/v2/api/policy', policy)
        if code in (200, 201):
          Logger.info("Created Ranger policy '{0}' in {1}".format(policy_name, service_name))
          return
        text = str(body)
        if code == 400 and MATCHING_RESOURCE_MARKER in text and CONFLICT_POLICY_NAME.search(text):
          # an administrator's policy already covers these resources: grant the access in it instead
          other = CONFLICT_POLICY_NAME.search(text).group(1)
          if other != policy_name:
            self._ensure_items(service_name, other, items)
            return
        if code == 400 and DUPLICATE_NAME_MARKER in text:
          time.sleep(random.uniform(1, 3))
          continue
        raise RangerDefaultPoliciesError('Creating Ranger policy {0} failed: HTTP {1} {2}'.format(policy_name, code, text[:500]))
      if _contains(existing, resources, items):
        return
      merge_policy(existing, resources, items)
      self._checked('PUT', '/service/public/v2/api/policy/{0}'.format(existing['id']), existing,
                    what='Updating Ranger policy ' + policy_name)
      if _contains(self.get_policy(service_name, policy_name) or {}, resources, items):
        Logger.info("Updated Ranger policy '{0}' in {1}".format(policy_name, service_name))
        return
      time.sleep(random.uniform(1, 3))
    raise RangerDefaultPoliciesError('Ranger policy {0} did not keep its changes after {1} attempts'.format(policy_name, attempts))

  def _ensure_items(self, service_name, policy_name, items):
    existing = self.get_policy(service_name, policy_name)
    if existing is None or not _merge_items(existing, items):
      return
    self._checked('PUT', '/service/public/v2/api/policy/{0}'.format(existing['id']), existing,
                  what='Updating Ranger policy ' + policy_name)
    Logger.info("Granted the default access in the existing Ranger policy '{0}' of {1}".format(policy_name, service_name))
