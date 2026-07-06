#!/usr/bin/env python

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

import os
import glob
import socket
import logging

RESULT_STATE_OK = 'OK'
RESULT_STATE_WARNING = 'WARNING'
RESULT_STATE_CRITICAL = 'CRITICAL'
RESULT_STATE_UNKNOWN = 'UNKNOWN'

CLIENT_PORT_KEY = '{{zoo.cfg/clientPort}}'
CLIENT_PORT_DEFAULT = '2181'

CONNECTION_TIMEOUT_KEY = 'connection.timeout'
CONNECTION_TIMEOUT_DEFAULT = 5.0

# where to look for zoo.cfg so we can enumerate the ensemble (server.N entries).
# The ensemble list is template-injected into zoo.cfg from clusterHostInfo, so it
# is NOT an Ambari config token and must be read from the file on the host.
ZOO_CFG_PATH_KEY = 'zoo.cfg.path'
ZOO_CFG_CANDIDATES = [
  '/etc/zookeeper/conf/zoo.cfg',
  '/usr/odp/current/zookeeper-server/conf/zoo.cfg',
  '/usr/odp/current/zookeeper-client/conf/zoo.cfg',
  '/usr/hdp/current/zookeeper-server/conf/zoo.cfg',
]
ZOO_CFG_GLOBS = [
  '/usr/odp/*/zookeeper*/conf/zoo.cfg',
  '/usr/hdp/*/zookeeper*/conf/zoo.cfg',
]

logger = logging.getLogger('ambari_alerts')


def get_tokens():
  """Config tokens ({{site/property}}) resolved into the configurations dict passed to execute()."""
  return (CLIENT_PORT_KEY,)


def _find_zoo_cfg(parameters):
  """Locate zoo.cfg: the alert's zoo.cfg.path parameter, else the first known ODP/HDP path."""
  if ZOO_CFG_PATH_KEY in parameters and parameters[ZOO_CFG_PATH_KEY]:
    return parameters[ZOO_CFG_PATH_KEY]
  for candidate in ZOO_CFG_CANDIDATES:
    if os.path.isfile(candidate):
      return candidate
  for pattern in ZOO_CFG_GLOBS:
    matches = sorted(glob.glob(pattern))
    if matches:
      return matches[-1]
  return None


def _parse_ensemble(zoo_cfg_path):
  """Return the ensemble hosts from the server.N=host:2888:3888 lines in zoo.cfg."""
  hosts = []
  try:
    with open(zoo_cfg_path, 'r') as f:
      for line in f:
        line = line.strip()
        if not line or line.startswith('#'):
          continue
        if line.startswith('server.') and '=' in line:
          value = line.split('=', 1)[1].strip()
          host = value.split(':', 1)[0].strip()
          if host:
            hosts.append(host)
  except Exception:
    logger.exception("[Alert] ZooKeeper Quorum Health: unable to read %s" % zoo_cfg_path)
  return hosts


def _four_letter_word(host, port, word, timeout):
  """Send a ZooKeeper four-letter-word and return its response text, or None if unreachable."""
  sock = None
  try:
    sock = socket.create_connection((host, int(port)), timeout=timeout)
    sock.settimeout(timeout)
    sock.sendall((word + "\n").encode('utf-8'))
    chunks = []
    while True:
      data = sock.recv(4096)
      if not data:
        break
      chunks.append(data)
    return b"".join(chunks).decode('utf-8', 'ignore')
  except Exception:
    return None
  finally:
    if sock is not None:
      try:
        sock.close()
      except Exception:
        pass


def _get_mode(response):
  """Extract the role from a srvr/stat response ("Mode: <role>"); 'unknown' if absent, None if no response."""
  if not response:
    return None
  for line in response.splitlines():
    line = line.strip()
    if line.lower().startswith('mode:'):
      return line.split(':', 1)[1].strip().lower()
  return 'unknown'


def _probe_mode(host, port, timeout):
  """Role of a ZK server, resilient to a restrictive 4lw whitelist: try srvr, stat, then mntr.
  Returns (mode, reachable): reachable=False if unreachable; mode='unknown' if contacted but the
  role is indeterminate (all 4lw blocked, or mid-election); else leader/follower/observer/standalone."""
  reachable = False
  for word in ('srvr', 'stat'):
    resp = _four_letter_word(host, port, word, timeout)
    if resp is None:
      continue
    reachable = True
    mode = _get_mode(resp)
    if mode and mode != 'unknown':
      return (mode, True)
  # mntr uses a different, tab/space separated format
  resp = _four_letter_word(host, port, 'mntr', timeout)
  if resp is not None:
    reachable = True
    for line in resp.splitlines():
      parts = line.split()
      if len(parts) >= 2 and parts[0] == 'zk_server_state':
        return (parts[1].strip().lower(), True)
  return (('unknown' if reachable else None), reachable)


def execute(configurations={}, parameters={}, host_name=None):
  """Enumerate the ensemble from the local zoo.cfg, probe each server, and verify the quorum is
  healthy (one leader, a majority reachable as leader/follower). Returns (state, [label])."""
  if configurations is None:
    return (RESULT_STATE_UNKNOWN, ['There were no configurations supplied to the script.'])

  client_port = CLIENT_PORT_DEFAULT
  if CLIENT_PORT_KEY in configurations and configurations[CLIENT_PORT_KEY]:
    client_port = str(configurations[CLIENT_PORT_KEY])

  connection_timeout = CONNECTION_TIMEOUT_DEFAULT
  if CONNECTION_TIMEOUT_KEY in parameters:
    connection_timeout = float(parameters[CONNECTION_TIMEOUT_KEY])

  zoo_cfg_path = _find_zoo_cfg(parameters)
  ensemble = _parse_ensemble(zoo_cfg_path) if zoo_cfg_path else []

  # single-node fallback: no server.N lines means a standalone server
  if not ensemble:
    ensemble = [host_name] if host_name else ['localhost']
    expected_standalone = True
  else:
    expected_standalone = len(ensemble) == 1

  leaders = []
  followers = []
  observers = []
  other = []       # responded but neither leader nor follower (e.g. election / looking)
  unreachable = []

  for zk_host in ensemble:
    mode, reachable = _probe_mode(zk_host, client_port, connection_timeout)
    if not reachable:
      unreachable.append(zk_host)
    elif mode == 'leader':
      leaders.append(zk_host)
    elif mode == 'follower':
      followers.append(zk_host)
    elif mode == 'observer':
      observers.append(zk_host)
    elif mode == 'standalone':
      # standalone is only healthy for a 1-node ensemble
      if expected_standalone:
        leaders.append(zk_host)
      else:
        other.append("{0} (standalone)".format(zk_host))
    else:
      other.append("{0} ({1})".format(zk_host, mode))

  ensemble_size = len(ensemble)
  voters = ensemble_size - len(observers)
  # majority is computed over voting members
  majority = (voters // 2) + 1 if voters > 0 else 1
  reachable_voters = len(leaders) + len(followers)

  label = ("ensemble={0}, leader={1}, followers={2}, observers={3}, other={4}, "
           "unreachable={5}").format(ensemble_size, leaders, followers, observers,
           other, unreachable)

  # standalone single node
  if expected_standalone:
    if leaders:
      return (RESULT_STATE_OK, ["Standalone ZooKeeper is up | " + label])
    return (RESULT_STATE_CRITICAL, ["Standalone ZooKeeper is not responding | " + label])

  # multi-node quorum evaluation
  if len(leaders) == 0:
    return (RESULT_STATE_CRITICAL, ["No leader elected - quorum is not established | " + label])

  if len(leaders) > 1:
    return (RESULT_STATE_CRITICAL, ["Split-brain: more than one leader reported | " + label])

  if reachable_voters < majority:
    return (RESULT_STATE_CRITICAL,
      ["Quorum lost: only {0} of {1} voting members reachable (need {2}) | {3}".format(
        reachable_voters, voters, majority, label)])

  if reachable_voters < voters or unreachable or other:
    return (RESULT_STATE_WARNING,
      ["Quorum holds but the ensemble is degraded | " + label])

  return (RESULT_STATE_OK,
    ["Quorum healthy: 1 leader, {0} follower(s) | {1}".format(len(followers), label)])
