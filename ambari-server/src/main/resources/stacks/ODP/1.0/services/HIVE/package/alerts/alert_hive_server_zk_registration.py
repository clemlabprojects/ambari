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
import socket
import subprocess
import logging

RESULT_STATE_OK = 'OK'
RESULT_STATE_CRITICAL = 'CRITICAL'
RESULT_STATE_UNKNOWN = 'UNKNOWN'
RESULT_STATE_SKIPPED = 'SKIPPED'

DISCOVERY_KEY = '{{hive-site/hive.server2.support.dynamic.service.discovery}}'
NAMESPACE_KEY = '{{hive-site/hive.server2.zookeeper.namespace}}'
ZK_QUORUM_KEY = '{{hive-site/hive.zookeeper.quorum}}'
TRANSPORT_MODE_KEY = '{{hive-site/hive.server2.transport.mode}}'
THRIFT_PORT_KEY = '{{hive-site/hive.server2.thrift.port}}'
HTTP_PORT_KEY = '{{hive-site/hive.server2.thrift.http.port}}'

CONNECTION_TIMEOUT_KEY = 'connection.timeout'
CONNECTION_TIMEOUT_DEFAULT = 15.0  # zkCli spins up a JVM, so give it headroom

# the HS2 discovery znodes are world-readable (JDBC clients read them to route),
# so a plain zkCli read needs no auth even on a kerberized ensemble
ZKCLI_CANDIDATES = [
  '/usr/odp/current/zookeeper-client/bin/zkCli.sh',
  '/usr/hdp/current/zookeeper-client/bin/zkCli.sh',
  '/usr/lib/zookeeper/bin/zkCli.sh',
]

logger = logging.getLogger('ambari_alerts')


def get_tokens():
  """Config tokens ({{site/property}}) resolved into the configurations dict passed to execute()."""
  return (DISCOVERY_KEY, NAMESPACE_KEY, ZK_QUORUM_KEY, TRANSPORT_MODE_KEY, THRIFT_PORT_KEY, HTTP_PORT_KEY)


def _find_zkcli():
  for path in ZKCLI_CANDIDATES:
    if os.path.isfile(path):
      return path
  return None


def _registered_hosts(zkcli, quorum, namespace, timeout):
  """Hosts registered under /<namespace>, parsed from 'zkCli ls' (serverUri=host:port;...)."""
  out = subprocess.check_output([zkcli, '-server', quorum, 'ls', '/' + namespace],
    timeout=timeout, stderr=subprocess.STDOUT).decode('utf-8', 'ignore')
  hosts = set()
  for line in out.splitlines():
    line = line.strip()
    if line.startswith('[') and 'serverUri=' in line:
      for token in line.strip('[]').split(','):
        token = token.strip()
        if token.startswith('serverUri='):
          host = token[len('serverUri='):].split(';', 1)[0].split(':', 1)[0].strip()
          if host:
            hosts.add(host)
      break
  return hosts


def _port_open(host, port, timeout):
  try:
    sock = socket.create_connection((host, int(port)), timeout=timeout)
    sock.close()
    return True
  except Exception:
    return False


def execute(configurations={}, parameters={}, host_name=None):
  """Verify this HiveServer2 is registered in ZooKeeper (i.e. discoverable by JDBC clients). Catches a
  hung HS2 whose port is still bound but whose ZK session dropped. Returns (state, [label])."""
  if configurations is None:
    return (RESULT_STATE_UNKNOWN, ['There were no configurations supplied to the script.'])

  if str(configurations.get(DISCOVERY_KEY, 'false')).lower() != 'true':
    return (RESULT_STATE_SKIPPED, ['Dynamic service discovery is not enabled; nothing registered in ZooKeeper'])

  namespace = configurations.get(NAMESPACE_KEY) or 'hiveserver2'
  quorum = configurations.get(ZK_QUORUM_KEY)
  if not quorum:
    return (RESULT_STATE_UNKNOWN, ['hive.zookeeper.quorum is not set'])

  if str(configurations.get(TRANSPORT_MODE_KEY, 'binary')).lower() == 'http':
    port = configurations.get(HTTP_PORT_KEY) or '10001'
  else:
    port = configurations.get(THRIFT_PORT_KEY) or '10000'

  timeout = float(parameters.get(CONNECTION_TIMEOUT_KEY, CONNECTION_TIMEOUT_DEFAULT))

  if host_name is None:
    host_name = socket.getfqdn()

  zkcli = _find_zkcli()
  if zkcli is None:
    return (RESULT_STATE_UNKNOWN, ['Could not locate zkCli.sh to query ZooKeeper'])

  try:
    registered = _registered_hosts(zkcli, quorum, namespace, timeout)
  except Exception:
    logger.exception("[Alert] HiveServer2 ZK Registration on %s fails:" % host_name)
    return (RESULT_STATE_UNKNOWN, ['Unable to query ZooKeeper namespace /%s' % namespace])

  short = host_name.split('.')[0]
  registered_here = any(short == h.split('.')[0] for h in registered)
  reg_list = ", ".join(sorted(registered)) or "none"

  if registered_here:
    return (RESULT_STATE_OK,
      ['HiveServer2 is registered in ZooKeeper /%s | registered: [%s]' % (namespace, reg_list)])

  # not registered: the port still being bound is the classic hung/deregistered case
  if _port_open(host_name, port, timeout):
    return (RESULT_STATE_CRITICAL,
      ['HiveServer2 port %s is open but this host is NOT registered in ZooKeeper /%s - hung/deregistered, '
       'JDBC clients cannot discover it | registered: [%s]' % (port, namespace, reg_list)])
  return (RESULT_STATE_CRITICAL,
    ['HiveServer2 is not registered in ZooKeeper /%s and port %s is not listening | registered: [%s]'
     % (namespace, port, reg_list)])
