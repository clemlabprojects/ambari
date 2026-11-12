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

Default Ranger policies of NiFi.

Ambari creates the Ranger NiFi service but no policy, so nobody, not even the nodes, could use NiFi. At every start
each node ensures the Ambari-managed policies and adds its own identity (the owner of its keystore certificate, after
NiFi's identity mappings) to the node policy.
"""
import gzip
import json
import os
import re
import shutil
import tempfile

from resource_management.core import shell, sudo
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.libraries.functions.ranger_default_policies import (
  RangerDefaultPolicies, RangerDefaultPoliciesError, policy_item)

NIFI_RESOURCE = 'nifi-resource'
NODES_POLICY = 'nifi - nodes'
ADMINS_POLICY = 'nifi - admins'
USERS_POLICY = 'nifi - users'
NIFI_MAPPING_PREFIX = 'nifi.security.identity.mapping.'
# the nodes proxy users and replicate requests
NODE_RESOURCES = ['/proxy', '/data/*', '/flow', '/process-groups/*']
USER_RESOURCES = ['/flow', '/system', '/counters', '/provenance']
DESCRIPTION = 'Created by Ambari (default NiFi policies); Ambari only adds to it'


def split_list(value):
  return [item.strip() for item in (value or '').split(',') if item.strip()]


def map_identity(identity, properties, prefix):
  """Applies NiFi's identity mappings (pattern.X / value.X / transform.X, in key order) like NiFi does."""
  for key in sorted(k for k in properties if k.startswith(prefix + 'pattern.') and properties[k]):
    suffix = key[len(prefix + 'pattern.'):]
    match = re.fullmatch(properties[key], identity)
    if not match:
      continue
    value = re.sub(r'\$(\d+)', lambda g: match.group(int(g.group(1))) or '', properties.get(prefix + 'value.' + suffix) or '')
    transform = (properties.get(prefix + 'transform.' + suffix) or 'NONE').upper()
    return value.lower() if transform == 'LOWER' else value.upper() if transform == 'UPPER' else value
  return identity


def keystore_owner(keytool, keystore, store_type, password):
  """The subject of the key entry, as NiFi prints peer identities (CN=host, O=..., C=...)."""
  if not keystore or not sudo.path_isfile(keystore):
    return None
  work = tempfile.mkdtemp()
  try:
    password_file = os.path.join(work, 'storepass')
    with os.fdopen(os.open(password_file, os.O_WRONLY | os.O_CREAT, 0o600), 'w') as f:
      f.write(password or '')
    code, out = shell.call((keytool, '-list', '-v', '-keystore', keystore, '-storetype', store_type or 'JKS',
                            '-storepass:file', password_file), sudo=True, quiet=True, logoutput=False)
  finally:
    shutil.rmtree(work, ignore_errors=True)
  if code != 0:
    Logger.warning("Unable to read the keystore {0} to find this node's identity".format(keystore))
    return None
  for entry in re.split(r'\n(?=Alias name:)', out or ''):
    if 'PrivateKeyEntry' in entry:
      owner = re.search(r'^Owner:\s*(.+)$', entry, re.M)
      if owner:
        return owner.group(1).strip()
  return None


def root_group_id(flow_dir):
  """The id of the root process group, from the flow on disk (absent before the first start)."""
  for name, pattern in (('flow.json.gz', None), ('flow.xml.gz', re.compile(rb'<rootGroup>\s*<id>([^<]+)</id>'))):
    path = os.path.join(flow_dir, name)
    if not sudo.path_isfile(path):
      continue
    try:
      content = gzip.decompress(sudo.read_file(path))
      if pattern is None:
        return json.loads(content)['rootGroup']['instanceIdentifier']
      match = pattern.search(content)
      if match:
        return match.group(1).decode()
    except (OSError, ValueError, KeyError, TypeError):
      Logger.warning("Unable to read the root process group id from {0}".format(path))
  return None


def _ranger(params):
  return RangerDefaultPolicies(params.policymgr_mgr_url, params.ranger_admin_username, params.ranger_admin_password)


def node_identity(params):
  dn = keystore_owner(os.path.join(params.jdk64_home, 'bin', 'keytool'), params.nifi_keystore, params.nifi_keystoreType,
                      params.nifi_keystorePasswd)
  return dn, (map_identity(dn, params.nifi_properties, NIFI_MAPPING_PREFIX) if dn else None)


def ensure_default_policies(strict=False):
  """Called at every NiFi start, after the Ranger service exists. A Ranger problem is logged, not fatal, unless strict:
  NiFi still starts, and the next start tries again."""
  import params
  if not params.ranger_nifi_default_policies:
    Logger.info("Default Ranger policies for NiFi are disabled (nifi.ranger.default.policies.enabled)")
    return None
  if not params.nifi_ssl_enabled:
    Logger.info("NiFi runs without TLS, so it authorizes nobody: no default Ranger policy is needed")
    return None
  try:
    ranger = _ranger(params)
    if ranger.get_service(params.repo_name) is None:
      Logger.warning("The Ranger service {0} does not exist yet: default NiFi policies are created at the next start".format(params.repo_name))
      return None
    if params.security_enabled:
      ranger.ensure_service_config_users(params.repo_name, ['policy.download.auth.users', 'tag.download.auth.users',
                                                            'userstore.download.auth.users'], [params.nifi_user])
    dn, node = node_identity(params)
    admin_users = split_list(params.nifi_ranger_admin_users) + ([params.nifi_initial_admin_id] if params.nifi_initial_admin_id else [])
    admin_groups = split_list(params.nifi_ranger_admin_groups)
    user_groups = split_list(params.nifi_ranger_user_groups)
    ranger.ensure_groups(admin_groups + user_groups)
    ranger.ensure_users(admin_users + ([node] if node else []))
    if node:
      Logger.info("NiFi node identity: {0}".format(node))
      ranger.ensure_policy(params.repo_name, NODES_POLICY, {NIFI_RESOURCE: NODE_RESOURCES},
                           [policy_item(['READ', 'WRITE'], users=[node])], DESCRIPTION)
    else:
      Logger.warning("No key entry found in {0}: this node is not added to the policy {1}".format(params.nifi_keystore, NODES_POLICY))
    if admin_users or admin_groups:
      ranger.ensure_policy(params.repo_name, ADMINS_POLICY, {NIFI_RESOURCE: ['*']},
                           [policy_item(['READ', 'WRITE'], users=admin_users, groups=admin_groups, delegate_admin=True)], DESCRIPTION)
    root = root_group_id(params.nifi_flow_config_dir)
    if user_groups:
      ranger.ensure_policy(params.repo_name, USERS_POLICY,
                           {NIFI_RESOURCE: USER_RESOURCES + (['/process-groups/' + root] if root else [])},
                           [policy_item(['READ'], groups=user_groups)], DESCRIPTION)
    return node
  except RangerDefaultPoliciesError as e:
    if strict:
      raise Fail("Unable to ensure the default Ranger policies of NiFi: {0}".format(e))
    Logger.warning("Unable to ensure the default Ranger policies of NiFi, retried at the next start: {0}".format(e))
    return None

