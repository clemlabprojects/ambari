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

Default Ranger policies of NiFi, and the NiFi workspaces (one process group per user or team, with its own policy).

Ambari creates the Ranger NiFi service but no policy, so nobody, not even the nodes, could use NiFi. At every start
each node ensures the Ambari-managed policies and adds its own identity (the owner of its keystore certificate, after
NiFi's identity mappings) to the node policy. NiFi treats a resource without an exact policy as "inherit from the
parent", so a workspace policy on its process group also denies it to everyone else.
"""
import gzip
import json
import os
import re
import shutil
import tempfile
import time

from resource_management.core import shell, sudo
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.libraries.functions.ranger_default_policies import (
  RangerDefaultPolicies, RangerDefaultPoliciesError, policy_item)

NIFI_RESOURCE = 'nifi-resource'
REGISTRY_RESOURCE = 'nifi-registry-resource'
NODES_POLICY = 'nifi - nodes'
ADMINS_POLICY = 'nifi - admins'
USERS_POLICY = 'nifi - users'
WORKSPACE_POLICY = 'nifi - workspace - {0}'
REGISTRY_PROXY_POLICY = 'nifi-registry - nifi nodes proxy'
REGISTRY_BUCKETS_POLICY = 'nifi-registry - nifi nodes buckets'
NIFI_MAPPING_PREFIX = 'nifi.security.identity.mapping.'
REGISTRY_MAPPING_PREFIX = 'nifi.registry.security.identity.mapping.'
# the nodes proxy users and replicate requests; they also list and create the workspace process groups
NODE_RESOURCES = ['/proxy', '/data/*', '/flow', '/process-groups/*']
USER_RESOURCES = ['/flow', '/system', '/counters', '/provenance']
WORKSPACE_NAME = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]*$')
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


def parse_workspaces(value):
  """'alice, etl=@etl-team;bob' -> [('alice', ['alice'], []), ('etl', ['bob'], ['etl-team'])]"""
  workspaces = []
  for entry in split_list(value):
    name, _, owners = entry.partition('=')
    name = name.strip()
    if not WORKSPACE_NAME.match(name):
      raise Fail("Invalid NiFi workspace name '{0}' in nifi.ranger.workspaces".format(name))
    owners = [o.strip() for o in owners.split(';') if o.strip()] or [name]
    workspaces.append((name, [o for o in owners if not o.startswith('@')], [o[1:] for o in owners if o.startswith('@')]))
  return workspaces


def _ranger(params):
  return RangerDefaultPolicies(params.policymgr_mgr_url, params.ranger_admin_username, params.ranger_admin_password)


def node_identity(params):
  dn = keystore_owner(os.path.join(params.jdk64_home, 'bin', 'keytool'), params.nifi_keystore, params.nifi_keystoreType,
                      params.nifi_keystorePasswd)
  return dn, (map_identity(dn, params.nifi_properties, NIFI_MAPPING_PREFIX) if dn else None)


def ensure_registry_node_policies(ranger, registry_service, identities):
  identities = [i for i in identities if i]
  if not identities or ranger.get_service(registry_service) is None:
    return False
  ranger.ensure_users(identities)
  ranger.ensure_policy(registry_service, REGISTRY_PROXY_POLICY, {REGISTRY_RESOURCE: ['/proxy']},
                       [policy_item(['READ', 'WRITE', 'DELETE'], users=identities)], DESCRIPTION)
  ranger.ensure_policy(registry_service, REGISTRY_BUCKETS_POLICY, {REGISTRY_RESOURCE: ['/buckets']},
                       [policy_item(['READ'], users=identities)], DESCRIPTION)
  return True


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
    if dn and params.nifi_registry_ranger_service:
      registry_node = map_identity(dn, params.nifi_registry_properties, REGISTRY_MAPPING_PREFIX)
      if not ensure_registry_node_policies(ranger, params.nifi_registry_ranger_service, [registry_node]):
        Logger.info("The Ranger NiFi Registry service does not exist yet: NiFi Registry grants this node access when it starts")
    return node
  except RangerDefaultPoliciesError as e:
    if strict:
      raise Fail("Unable to ensure the default Ranger policies of NiFi: {0}".format(e))
    Logger.warning("Unable to ensure the default Ranger policies of NiFi, retried at the next start: {0}".format(e))
    return None


class NifiCli(object):
  """The NiFi toolkit CLI as this node (its keystore), with the TLS settings in a file readable by root only."""

  def __init__(self, params):
    self.params = params
    self.work = tempfile.mkdtemp()
    self.properties = os.path.join(self.work, 'cli.properties')
    values = {'baseUrl': 'https://{0}:{1}'.format(params.nifi_node_host, params.nifi_node_ssl_port),
              'keystore': params.nifi_keystore, 'keystoreType': params.nifi_keystoreType,
              'keystorePasswd': params.nifi_keystorePasswd, 'keyPasswd': params.nifi_keyPasswd or params.nifi_keystorePasswd,
              'truststore': params.nifi_truststore, 'truststoreType': params.nifi_truststoreType,
              'truststorePasswd': params.nifi_truststorePasswd}
    with os.fdopen(os.open(self.properties, os.O_WRONLY | os.O_CREAT, 0o600), 'w') as f:
      f.write(''.join('{0}={1}\n'.format(k, v) for k, v in values.items()))

  def close(self):
    shutil.rmtree(self.work, ignore_errors=True)

  def run(self, *args, **kwargs):
    """Retries while NiFi starts or while its Ranger plugin has not loaded the node policy yet (it polls every 30 s)."""
    deadline = time.time() + kwargs.get('wait', 180)
    cmd = (os.path.join(self.params.stack_root, 'current', 'nifi-toolkit', 'bin', 'cli.sh'), 'nifi') + args + ('-p', self.properties)
    while True:
      code, out = shell.call(cmd, sudo=True, quiet=True, logoutput=False, env={'JAVA_HOME': self.params.jdk64_home})
      if code == 0:
        return out
      if time.time() > deadline:
        raise Fail("NiFi CLI '{0}' failed: {1}".format(' '.join(args), (out or '').strip()[-1000:]))
      time.sleep(10)


def _child_groups(cli, root):
  out = cli.run('pg-list', '-pgid', root, '-ot', 'json')
  groups = json.loads(out[out.find('['):] if '[' in out else '[]')
  return {(g.get('component') or g).get('name'): g.get('id') or (g.get('component') or {}).get('id') for g in groups}


def sync_workspaces():
  """Creates the missing workspace process groups and ensures their policies. Runs on the first NiFi host only, so
  two hosts never create the same group; running it again changes nothing."""
  import params
  if not (params.has_ranger_admin and params.enable_ranger_nifi and params.nifi_ssl_enabled):
    raise Fail("NiFi workspaces need the Ranger NiFi plugin and TLS")
  first = sorted(h.lower() for h in params.nifi_master_hosts)[0]
  if params.nifi_node_host.lower() != first:
    Logger.info("NiFi workspaces are synchronized by {0}".format(first))
    return
  workspaces = parse_workspaces(params.nifi_ranger_workspaces)
  if not workspaces:
    Logger.info("No NiFi workspace is configured (nifi.ranger.workspaces)")
    return
  if not ensure_default_policies(strict=True):
    raise Fail("This node has no identity in Ranger: it cannot create the workspaces")
  root = root_group_id(params.nifi_flow_config_dir)
  if not root:
    raise Fail("NiFi has no flow yet: start NiFi before synchronizing the workspaces")
  ranger = _ranger(params)
  cli = NifiCli(params)
  try:
    existing = _child_groups(cli, root)
    for name, users, groups in workspaces:
      group_id = existing.get(name)
      if not group_id:
        group_id = cli.run('pg-create', '-pgn', name).strip().splitlines()[-1].strip()
        Logger.info("Created the NiFi workspace process group '{0}' ({1})".format(name, group_id))
      try:
        ranger.ensure_groups(groups)
        ranger.ensure_users(users)
        ranger.ensure_policy(params.repo_name, WORKSPACE_POLICY.format(name),
                             {NIFI_RESOURCE: [p + group_id for p in ('/process-groups/', '/data/process-groups/',
                                                                      '/provenance-data/process-groups/', '/operation/process-groups/')]},
                             [policy_item(['READ', 'WRITE'], users=users, groups=groups)], DESCRIPTION)
      except RangerDefaultPoliciesError as e:
        raise Fail("Unable to ensure the Ranger policy of the NiFi workspace {0}: {1}".format(name, e))
      Logger.info("NiFi workspace '{0}': owners {1}".format(name, ', '.join(users + ['@' + g for g in groups])))
  finally:
    cli.close()
