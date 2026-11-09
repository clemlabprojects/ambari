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

Re-encrypts the sensitive values of an existing NiFi flow when the sensitive properties key or algorithm changes.

NiFi's own commands (nifi.sh set-sensitive-properties-key / -algorithm) read nifi.properties as a plain file, so they
cannot use the key Ambari protected with encrypt-config, and on a flow they cannot decrypt they only print
"Failed to process Flow Configuration" while still writing the new key. The conversion therefore runs on copies,
with the previous key recovered from its protected value, and the converted flow only replaces the original once
NiFi has decrypted it again with the new key and algorithm.
"""
import base64
import os
import pwd
import grp
import time

# NiFi's files belong to the NiFi user: every file access goes through sudo (the agent may run as a non-root user)
from resource_management.core import shell, sudo
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.core.utils import PasswordString

KEY = 'nifi.sensitive.props.key'
KEY_PROTECTED = 'nifi.sensitive.props.key.protected'
ALGORITHM = 'nifi.sensitive.props.algorithm'
# NiFi falls back to this algorithm when the property is empty
DEFAULT_ALGORITHM = 'NIFI_PBKDF2_AES_GCM_256'
FLOW_FILE_PROPERTIES = ('nifi.flow.configuration.file', 'nifi.flow.configuration.json.file')
BOOTSTRAP_KEY = 'nifi.bootstrap.sensitive.key'
COMMAND_PACKAGE = 'org.apache.nifi.flow.encryptor.command.'
FAILURE_MARKER = 'Failed to process'


def read_properties(path):
  """Parses a .properties file, splitting on the first '=' only (keys and protected values may contain '=')."""
  properties = {}
  for line in sudo.read_file(path).decode('utf-8').splitlines():
    if line and not line.lstrip().startswith('#') and '=' in line:
      name, value = line.split('=', 1)
      properties[name.strip()] = value
  return properties


def non_empty_file(path):
  return sudo.path_isfile(path) and shell.call(('test', '-s', path), sudo=True, quiet=True)[0] == 0


def remove_tree(path):
  if sudo.path_lexists(path):
    sudo.rmtree(path)


def unprotect(value, scheme, master_key_hex):
  """Returns the plain value of a property protected by encrypt-config (aes/gcm: base64(iv)||base64(ciphertext+tag))."""
  if not scheme:
    return value
  if not scheme.startswith('aes/gcm'):
    raise Fail("The NiFi sensitive properties key is protected with '{0}', which Ambari cannot read".format(scheme))
  if not master_key_hex:
    raise Fail("The NiFi sensitive properties key is protected but bootstrap.conf has no {0}".format(BOOTSTRAP_KEY))
  from cryptography.hazmat.primitives.ciphers.aead import AESGCM
  iv, ciphertext = value.split('||', 1)
  padded = lambda s: s + '=' * (-len(s) % 4)
  return AESGCM(bytes.fromhex(master_key_hex)).decrypt(base64.b64decode(padded(iv)), base64.b64decode(padded(ciphertext)), None).decode()


def write_properties_copy(source, target, replacements, drop):
  """Copies a .properties file, replacing the values of the given names and dropping others."""
  out = []
  for line in sudo.read_file(source).decode('utf-8').splitlines(True):
    name = line.split('=', 1)[0].strip() if '=' in line and not line.lstrip().startswith('#') else None
    if name in drop:
      continue
    out.append('{0}={1}\n'.format(name, replacements[name]) if name in replacements else line)
  sudo.create_file(target, ''.join(out))
  sudo.chmod(target, 0o600)


class FlowSensitivePropertiesMigration(object):

  def __init__(self, nifi_install_dir, nifi_config_dir, java_home, user, group):
    self.nifi_install_dir = nifi_install_dir
    self.nifi_config_dir = nifi_config_dir
    self.java = os.path.join(java_home, 'bin', 'java')
    self.user = user
    self.owner = pwd.getpwnam(user)
    self.group = grp.getgrnam(group)

  def current_values_if_changed(self, new_key, new_algorithm):
    """The on-disk key, protection and algorithm when they differ from the configured ones, else {}."""
    current_file = os.path.join(self.nifi_config_dir, 'nifi.properties')
    if not sudo.path_isfile(current_file):
      return {}
    current = read_properties(current_file)
    bootstrap_file = os.path.join(self.nifi_config_dir, 'bootstrap.conf')
    master_key = read_properties(bootstrap_file).get(BOOTSTRAP_KEY) if sudo.path_isfile(bootstrap_file) else None
    old_key = unprotect(current.get(KEY, ''), current.get(KEY_PROTECTED, ''), master_key)
    if old_key == new_key and (current.get(ALGORITHM) or DEFAULT_ALGORITHM) == (new_algorithm or DEFAULT_ALGORITHM):
      return {}
    return {name: current[name] for name in (KEY, KEY_PROTECTED, ALGORITHM) if name in current}

  def run(self, new_key, new_algorithm):
    current_file = os.path.join(self.nifi_config_dir, 'nifi.properties')
    if not sudo.path_isfile(current_file):
      return
    current = read_properties(current_file)
    flows = [current[p] for p in FLOW_FILE_PROPERTIES if current.get(p) and non_empty_file(current[p])]
    old_algorithm = current.get(ALGORITHM) or DEFAULT_ALGORITHM
    new_algorithm = new_algorithm or DEFAULT_ALGORITHM
    bootstrap_file = os.path.join(self.nifi_config_dir, 'bootstrap.conf')
    master_key = read_properties(bootstrap_file).get(BOOTSTRAP_KEY) if sudo.path_isfile(bootstrap_file) else None
    old_key = unprotect(current.get(KEY, ''), current.get(KEY_PROTECTED, ''), master_key)
    if old_key == new_key and old_algorithm == new_algorithm:
      return
    if not flows:
      Logger.info("The NiFi sensitive properties key or algorithm changed, there is no flow to re-encrypt")
      return
    Logger.info("The NiFi sensitive properties key or algorithm changed, re-encrypting the existing flow")
    # same filesystem as the flow, so the converted files replace the originals with an atomic rename
    work = os.path.join(os.path.dirname(flows[0]), '.sensitive-props-migration')
    remove_tree(work)
    try:
      staged = self._stage(work, 'stage', current_file, flows, {KEY: old_key, ALGORITHM: old_algorithm})
      if old_key != new_key:
        self._encryptor(work, 'stage', 'SetSensitivePropertiesKey', new_key)
      if old_algorithm != new_algorithm:
        self._encryptor(work, 'stage', 'SetSensitivePropertiesAlgorithm', new_algorithm)
      # NiFi must be able to decrypt every value of the converted flow with the new key and algorithm
      self._stage(work, 'verify', current_file, list(staged.values()), {KEY: new_key, ALGORITHM: new_algorithm})
      self._encryptor(work, 'verify', 'SetSensitivePropertiesKey', new_key)
      suffix = time.strftime('.before-sensitive-props-change-%Y%m%d%H%M%S')
      for original, converted in staged.items():
        sudo.copy(original, original + suffix)
        sudo.chown(original + suffix, self.owner, self.group)
        sudo.chmod(converted, sudo.stat(original).st_mode & 0o777)
        sudo.move(converted, original)
        Logger.info("Re-encrypted {0}, previous version kept as {1}".format(original, original + suffix))
    finally:
      remove_tree(work)

  def _stage(self, work, name, properties_file, flows, values):
    """Copies the flow files and a plain nifi.properties pointing at them into work/name; returns {source: copy}."""
    directory = os.path.join(work, name)
    sudo.makedirs(directory, 0o700)
    copies = {}
    replacements = dict(values)
    current = read_properties(properties_file)
    for flow in flows:
      copy = os.path.join(directory, os.path.basename(flow))
      sudo.copy(flow, copy)
      copies[flow] = copy
    for prop in FLOW_FILE_PROPERTIES:
      if current.get(prop):
        source = current[prop]
        original = next((f for f in copies if f == source or os.path.basename(f) == os.path.basename(source)), None)
        replacements[prop] = copies[original] if original else os.path.join(directory, 'absent-' + os.path.basename(source))
    write_properties_copy(properties_file, os.path.join(directory, 'nifi.properties'), replacements, drop=(KEY_PROTECTED,))
    # chown_recursive leaves the top directory itself to the caller
    sudo.chown(work, self.owner, self.group)
    sudo.chown_recursive(work, self.owner, self.group)
    return copies

  def _encryptor(self, work, name, command, value):
    directory = os.path.join(work, name)
    classpath = '{0}:{1}/lib/bootstrap/*'.format(directory, self.nifi_install_dir)
    cmd = (self.java, '-cp', classpath, '-Dnifi.properties.file.path=' + os.path.join(directory, 'nifi.properties'),
           COMMAND_PACKAGE + command, PasswordString(value))
    code, out = shell.call(cmd, user=self.user, logoutput=False, quiet=True)
    if code != 0 or FAILURE_MARKER in (out or ''):
      details = '\n'.join(l for l in (out or '').splitlines() if FAILURE_MARKER in l or 'Exception' in l)[:2000]
      raise Fail("NiFi could not re-encrypt the flow with the new sensitive properties {0} ({1} exited {2}). The flow and "
                 "nifi.properties on this host are unchanged; restore the previous NiFi configuration version, or fix the "
                 "cause and restart.\n{3}".format('key' if command.endswith('Key') else 'algorithm', command, code, details))
