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
import time
import tempfile
import subprocess
import ambari_simplejson as json
import logging

from resource_management.libraries.functions.curl_krb_request import curl_krb_request
from resource_management.libraries.functions.curl_krb_request import DEFAULT_KERBEROS_KINIT_TIMER_MS
from resource_management.libraries.functions.curl_krb_request import KERBEROS_KINIT_TIMER_PARAMETER
from resource_management.core.environment import Environment

RESULT_STATE_OK = 'OK'
RESULT_STATE_WARNING = 'WARNING'
RESULT_STATE_CRITICAL = 'CRITICAL'
RESULT_STATE_UNKNOWN = 'UNKNOWN'
RESULT_STATE_SKIPPED = 'SKIPPED'

HDFS_SITE_KEY = '{{hdfs-site}}'
# dfs.internal.nameservices is only present when NameNode HA is configured
NAMESERVICE_KEY = '{{hdfs-site/dfs.internal.nameservices}}'
SHARED_EDITS_KEY = '{{hdfs-site/dfs.namenode.shared.edits.dir}}'
JN_HTTP_ADDRESS_KEY = '{{hdfs-site/dfs.journalnode.http-address}}'
JN_HTTPS_ADDRESS_KEY = '{{hdfs-site/dfs.journalnode.https-address}}'
DFS_POLICY_KEY = '{{hdfs-site/dfs.http.policy}}'

KERBEROS_KEYTAB = '{{hdfs-site/dfs.web.authentication.kerberos.keytab}}'
KERBEROS_PRINCIPAL = '{{hdfs-site/dfs.web.authentication.kerberos.principal}}'
SECURITY_ENABLED_KEY = '{{cluster-env/security_enabled}}'
SMOKEUSER_KEY = '{{cluster-env/smokeuser}}'
EXECUTABLE_SEARCH_PATHS = '{{kerberos-env/executable_search_paths}}'

# script parameters (all overridable from the alert definition in the UI)
CONNECTION_TIMEOUT_KEY = 'connection.timeout'
CONNECTION_TIMEOUT_DEFAULT = 5.0

# a JournalNode whose CurrentLagTxns is greater than this transaction count is
# considered "lagging". Defaults to 0 => any non-zero lag starts the timer.
LAG_TXNS_THRESHOLD_KEY = 'lag.txns.threshold'
LAG_TXNS_THRESHOLD_DEFAULT = 0

# how long (seconds) a JournalNode must stay above the txn threshold before the
# alert escalates. WARNING at the warning duration, CRITICAL at the critical one.
LAG_WARNING_SECONDS_KEY = 'lag.duration.warning.seconds'
LAG_WARNING_SECONDS_DEFAULT = 60
LAG_CRITICAL_SECONDS_KEY = 'lag.duration.critical.seconds'
LAG_CRITICAL_SECONDS_DEFAULT = 120

STATE_FILE_NAME = 'alert_journalnode_lag_state.json'

LOGGER_EXCEPTION_MESSAGE = "[Alert] JournalNode Sync Lag on {0} fails:"
logger = logging.getLogger('ambari_alerts')

JN_JMX_BEAN_PREFIX = 'Hadoop:service=JournalNode,name=Journal-'


def get_tokens():
  """Config tokens ({{site/property}}) resolved into the configurations dict passed to execute()."""
  return (HDFS_SITE_KEY, NAMESERVICE_KEY, SHARED_EDITS_KEY, JN_HTTP_ADDRESS_KEY,
    JN_HTTPS_ADDRESS_KEY, DFS_POLICY_KEY, SECURITY_ENABLED_KEY, SMOKEUSER_KEY,
    KERBEROS_KEYTAB, KERBEROS_PRINCIPAL, EXECUTABLE_SEARCH_PATHS)


def _state_file_path():
  try:
    tmp_dir = Environment.get_instance().tmp_dir
  except Exception:
    tmp_dir = None
  if not tmp_dir or not os.path.isdir(tmp_dir):
    tmp_dir = tempfile.gettempdir()
  return os.path.join(tmp_dir, STATE_FILE_NAME)


def _load_state():
  try:
    with open(_state_file_path(), 'r') as f:
      return json.loads(f.read())
  except Exception:
    return {}


def _save_state(state):
  try:
    with open(_state_file_path(), 'w') as f:
      f.write(json.dumps(state))
  except Exception:
    logger.exception("[Alert] JournalNode Sync Lag: unable to persist state file")


def _parse_jn_hosts(shared_edits_uri):
  """
  Turn qjournal://h1:8485;h2:8485;h3:8485/nameservice into ['h1', 'h2', 'h3'].
  """
  hosts = []
  if not shared_edits_uri or 'qjournal://' not in shared_edits_uri:
    return hosts
  authority = shared_edits_uri.split('qjournal://', 1)[1]
  # strip the trailing /nameservice
  authority = authority.split('/', 1)[0]
  for entry in authority.split(';'):
    entry = entry.strip()
    if not entry:
      continue
    host = entry.split(':', 1)[0]
    if host:
      hosts.append(host)
  return hosts


def _read_current_lag(jmx_uri, security_enabled, kerberos_keytab, kerberos_principal,
                      smokeuser, executable_paths, connection_timeout, kinit_timer_ms):
  """Return the JournalNode's CurrentLagTxns (0 is a valid, healthy value), or None if unreadable."""
  if security_enabled and kerberos_principal is not None and kerberos_keytab is not None:
    env = Environment.get_instance()
    payload, _, _ = curl_krb_request(env.tmp_dir, kerberos_keytab, kerberos_principal,
      jmx_uri, "jn_sync_lag", executable_paths, False, "JournalNode Sync Lag", smokeuser,
      connection_timeout=int(connection_timeout), kinit_timer_ms=kinit_timer_ms)
  else:
    # -k accepts the JN's (usually self-signed) cert on an HTTPS_ONLY non-kerberized cluster
    payload = subprocess.check_output(
      ['curl', '-s', '-k', '--max-time', str(int(connection_timeout)), jmx_uri],
      timeout=connection_timeout + 5)

  if not payload:
    return None
  for bean in json.loads(payload).get('beans', []):
    if bean.get('name', '').startswith(JN_JMX_BEAN_PREFIX) and 'CurrentLagTxns' in bean:
      return int(bean['CurrentLagTxns'])
  return None


def execute(configurations={}, parameters={}, host_name=None):
  """Walk the JN quorum's CurrentLagTxns; a state file tracks how long each JN has
  been lagging so a brief spike stays OK while a sustained lag escalates. Returns (state, [label])."""
  if configurations is None:
    return (RESULT_STATE_UNKNOWN, ['There were no configurations supplied to the script.'])

  # not in HA mode -> nothing to compare, skip
  if NAMESERVICE_KEY not in configurations:
    return (RESULT_STATE_SKIPPED, ['NameNode HA is not enabled; no JournalNode quorum to check'])

  if HDFS_SITE_KEY not in configurations:
    return (RESULT_STATE_UNKNOWN, ['{0} is a required parameter for the script'.format(HDFS_SITE_KEY)])

  name_service = configurations[NAMESERVICE_KEY]
  # dfs.internal.nameservices can be a comma separated list (federation); use the first
  name_service = name_service.split(',')[0].strip()

  shared_edits = configurations.get(SHARED_EDITS_KEY)
  jn_hosts = _parse_jn_hosts(shared_edits)
  if not jn_hosts:
    return (RESULT_STATE_SKIPPED, ['No JournalNode quorum found in dfs.namenode.shared.edits.dir'])

  # determine protocol / port
  is_ssl_enabled = False
  if DFS_POLICY_KEY in configurations and configurations[DFS_POLICY_KEY] == "HTTPS_ONLY":
    is_ssl_enabled = True

  if is_ssl_enabled:
    protocol = 'https'
    address = configurations.get(JN_HTTPS_ADDRESS_KEY)
  else:
    protocol = 'http'
    address = configurations.get(JN_HTTP_ADDRESS_KEY)

  if not address or ':' not in address:
    return (RESULT_STATE_UNKNOWN, ['Unable to determine the JournalNode {0} port'.format(protocol)])
  jn_port = address.split(':')[-1]

  # security / parameters
  security_enabled = False
  if SECURITY_ENABLED_KEY in configurations:
    security_enabled = str(configurations[SECURITY_ENABLED_KEY]).upper() == 'TRUE'

  smokeuser = configurations.get(SMOKEUSER_KEY)
  executable_paths = configurations.get(EXECUTABLE_SEARCH_PATHS)
  kerberos_keytab = configurations.get(KERBEROS_KEYTAB)
  kerberos_principal = configurations.get(KERBEROS_PRINCIPAL)
  if kerberos_principal is not None and host_name is not None:
    kerberos_principal = kerberos_principal.replace('_HOST', host_name)

  connection_timeout = CONNECTION_TIMEOUT_DEFAULT
  if CONNECTION_TIMEOUT_KEY in parameters:
    connection_timeout = float(parameters[CONNECTION_TIMEOUT_KEY])

  lag_txns_threshold = LAG_TXNS_THRESHOLD_DEFAULT
  if LAG_TXNS_THRESHOLD_KEY in parameters:
    lag_txns_threshold = int(float(parameters[LAG_TXNS_THRESHOLD_KEY]))

  warning_seconds = LAG_WARNING_SECONDS_DEFAULT
  if LAG_WARNING_SECONDS_KEY in parameters:
    warning_seconds = int(float(parameters[LAG_WARNING_SECONDS_KEY]))

  critical_seconds = LAG_CRITICAL_SECONDS_DEFAULT
  if LAG_CRITICAL_SECONDS_KEY in parameters:
    critical_seconds = int(float(parameters[LAG_CRITICAL_SECONDS_KEY]))

  kinit_timer_ms = parameters.get(KERBEROS_KINIT_TIMER_PARAMETER, DEFAULT_KERBEROS_KINIT_TIMER_MS)

  now = int(time.time())
  state = _load_state()
  new_state = {}

  lag_by_host = {}
  unreachable = []

  for jn_host in jn_hosts:
    jmx_uri = "{0}://{1}:{2}/jmx?qry={3}{4}".format(protocol, jn_host, jn_port,
      JN_JMX_BEAN_PREFIX, name_service)
    try:
      lag = _read_current_lag(jmx_uri, security_enabled, kerberos_keytab,
        kerberos_principal, smokeuser, executable_paths, connection_timeout, kinit_timer_ms)
    except Exception:
      logger.exception(LOGGER_EXCEPTION_MESSAGE.format(host_name))
      lag = None

    if lag is None:
      unreachable.append(jn_host)
      continue

    lag_by_host[jn_host] = lag
    if lag > lag_txns_threshold:
      # keep the earliest timestamp at which this JN was first seen lagging
      first_seen = state.get(jn_host, {}).get('since', now)
      new_state[jn_host] = {'since': first_seen, 'lag': lag}
    # else: synced -> drop from state (implicitly, by not copying it forward)

  _save_state(new_state)

  # build the verdict
  worst = RESULT_STATE_OK
  offenders = []
  for jn_host, info in new_state.items():
    duration = now - info['since']
    if duration >= critical_seconds:
      severity = RESULT_STATE_CRITICAL
    elif duration >= warning_seconds:
      severity = RESULT_STATE_WARNING
    else:
      severity = RESULT_STATE_OK  # lagging, but not long enough yet
    offenders.append((jn_host, info['lag'], duration, severity))
    if severity == RESULT_STATE_CRITICAL:
      worst = RESULT_STATE_CRITICAL
    elif severity == RESULT_STATE_WARNING and worst != RESULT_STATE_CRITICAL:
      worst = RESULT_STATE_WARNING

  # an unreachable JournalNode is itself a problem worth a WARNING (the process
  # alert covers hard-down; here we only nudge so lag detection isn't silent)
  if unreachable and worst == RESULT_STATE_OK:
    worst = RESULT_STATE_WARNING

  synced = [h for h in jn_hosts if h in lag_by_host and lag_by_host[h] <= lag_txns_threshold]

  parts = []
  parts.append("nameservice={0}".format(name_service))
  parts.append("synced={0}/{1}".format(len(synced), len(jn_hosts)))
  if offenders:
    lagging_desc = ", ".join(
      "{0} (lag={1} txns for {2}s)".format(h, lag, dur) for (h, lag, dur, sev) in offenders)
    parts.append("lagging: [{0}]".format(lagging_desc))
  if unreachable:
    parts.append("unreachable: [{0}]".format(", ".join(unreachable)))
  if worst == RESULT_STATE_OK and not offenders and not unreachable:
    parts.append("all JournalNodes in sync")

  return (worst, [" | ".join(parts)])
