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

Ozone service-level alerts computed from the Recon cluster state
(/api/v1/clusterState):
  - ozone_capacity_utilization: used share of the cluster capacity
  - datanode_health_summary: number of DataNodes that are not healthy

The definitions are service-level, so the agent runs this script on every Ozone
master host. Only the host running Recon answers; the others return SKIPPED.
"""

import os
import ssl
import traceback
import urllib.request

import ambari_simplejson as json # simplejson is much faster comparing to Python 2.6 json module and has the same functions set.

from resource_management.core.environment import Environment
from resource_management.libraries.functions.curl_krb_request import curl_krb_request
from resource_management.libraries.functions.curl_krb_request import CONNECTION_TIMEOUT_DEFAULT
from resource_management.libraries.functions.curl_krb_request import DEFAULT_KERBEROS_KINIT_TIMER_MS
from resource_management.libraries.functions.curl_krb_request import KERBEROS_KINIT_TIMER_PARAMETER

OZONE_SITE_KEY = '{{ozone-site}}'
HTTP_POLICY_KEY = '{{ozone-site/ozone.http.policy}}'
RECON_HTTP_PORT_KEY = '{{ozone-site/ozone.recon.http-port}}'
RECON_HTTPS_PORT_KEY = '{{ozone-site/ozone.recon.https-port}}'
RECON_DB_DIR_KEY = '{{ozone-site/ozone.recon.db.dir}}'
HTTP_KERBEROS_ENABLED_KEY = '{{ozone-site/ozone.security.http.kerberos.enabled}}'
KERBEROS_KEYTAB_KEY = '{{ozone-site/ozone.recon.http.auth.kerberos.keytab}}'
KERBEROS_PRINCIPAL_KEY = '{{ozone-site/ozone.recon.http.auth.kerberos.principal}}'
SECURITY_ENABLED_KEY = '{{cluster-env/security_enabled}}'
SMOKEUSER_KEY = '{{cluster-env/smokeuser}}'
EXECUTABLE_SEARCH_PATHS_KEY = '{{kerberos-env/executable_search_paths}}'

RECON_HTTP_PORT_DEFAULT = '9888'
RECON_HTTPS_PORT_DEFAULT = '9889'
RECON_DB_DIR_DEFAULT = '/var/lib/hadoop-ozone/recon/data'

RESULT_STATE_OK = 'OK'
RESULT_STATE_WARNING = 'WARNING'
RESULT_STATE_CRITICAL = 'CRITICAL'
RESULT_STATE_UNKNOWN = 'UNKNOWN'
RESULT_STATE_SKIPPED = 'SKIPPED'

# ozone_capacity_utilization parameters (percent)
KEY_CAPACITY_USED_WARNING = 'capacity.used.warning.threshold'
KEY_CAPACITY_USED_CRITICAL = 'capacity.used.critical.threshold'
# datanode_health_summary parameters (number of DataNodes that are not healthy)
KEY_DATANODES_UNHEALTHY_WARNING = 'health.datanode.warning'
KEY_DATANODES_UNHEALTHY_CRITICAL = 'health.datanode.warning.critical'

CAPACITY_USED_WARNING_DEFAULT = 80.0
CAPACITY_USED_CRITICAL_DEFAULT = 90.0
DATANODES_UNHEALTHY_WARNING_DEFAULT = 1.0
DATANODES_UNHEALTHY_CRITICAL_DEFAULT = 2.0


def get_tokens():
  """
  Returns a tuple of tokens in the format {{site/property}} that will be used
  to build the dictionary passed into execute
  """
  return (OZONE_SITE_KEY, HTTP_POLICY_KEY, RECON_HTTP_PORT_KEY, RECON_HTTPS_PORT_KEY, RECON_DB_DIR_KEY,
          HTTP_KERBEROS_ENABLED_KEY, KERBEROS_KEYTAB_KEY, KERBEROS_PRINCIPAL_KEY, SECURITY_ENABLED_KEY,
          SMOKEUSER_KEY, EXECUTABLE_SEARCH_PATHS_KEY)


def execute(configurations={}, parameters={}, host_name=None):
  """
  Returns a tuple containing the result code and a pre-formatted result label

  Keyword arguments:
  configurations (dictionary): a mapping of configuration key to value
  parameters (dictionary): a mapping of script parameter key to value
  host_name (string): the name of this host where the alert is running
  """
  if configurations is None:
    return (RESULT_STATE_UNKNOWN, ['There were no configurations supplied to the script.'])

  if OZONE_SITE_KEY not in configurations:
    return (RESULT_STATE_UNKNOWN, ['{0} is a required parameter for the script'.format(OZONE_SITE_KEY)])

  # Recon keeps its database on its own host: the other Ozone masters leave the alert to it
  recon_db_dir = configurations.get(RECON_DB_DIR_KEY) or RECON_DB_DIR_DEFAULT
  if not os.path.isdir(recon_db_dir):
    return (RESULT_STATE_SKIPPED, ['Ozone Recon does not run on this host'])

  http_policy = str(configurations.get(HTTP_POLICY_KEY) or 'HTTP_ONLY').strip().upper()
  if http_policy == 'HTTPS_ONLY':
    url = 'https://{0}:{1}/api/v1/clusterState'.format(
      host_name, str(configurations.get(RECON_HTTPS_PORT_KEY) or RECON_HTTPS_PORT_DEFAULT).strip())
  else:
    url = 'http://{0}:{1}/api/v1/clusterState'.format(
      host_name, str(configurations.get(RECON_HTTP_PORT_KEY) or RECON_HTTP_PORT_DEFAULT).strip())

  try:
    state = get_cluster_state(url, configurations, parameters, host_name)
  except Exception:
    return (RESULT_STATE_UNKNOWN, ['Unable to read the Ozone cluster state from {0}\n{1}'.format(url, traceback.format_exc())])

  try:
    if KEY_CAPACITY_USED_WARNING in parameters or KEY_CAPACITY_USED_CRITICAL in parameters:
      return capacity_alert(state, parameters)
    return datanode_health_alert(state, parameters)
  except Exception:
    return (RESULT_STATE_UNKNOWN, ['Unexpected Ozone cluster state from {0}\n{1}'.format(url, traceback.format_exc())])


def get_cluster_state(url, configurations, parameters, host_name):
  """
  Reads the Recon cluster state, with SPNEGO when the Ozone web consoles use Kerberos.
  """
  security_enabled = str(configurations.get(SECURITY_ENABLED_KEY, '')).upper() == 'TRUE'
  http_kerberos = str(configurations.get(HTTP_KERBEROS_ENABLED_KEY, '')).upper() == 'TRUE'
  keytab = configurations.get(KERBEROS_KEYTAB_KEY)
  principal = configurations.get(KERBEROS_PRINCIPAL_KEY)

  if security_enabled and http_kerberos and keytab and principal:
    env = Environment.get_instance()
    response, error_msg, _ = curl_krb_request(
      env.tmp_dir, keytab, principal.replace('_HOST', host_name), url, 'ozone_recon_cluster_state',
      configurations.get(EXECUTABLE_SEARCH_PATHS_KEY), False, 'Ozone Recon cluster state',
      configurations.get(SMOKEUSER_KEY), connection_timeout=CONNECTION_TIMEOUT_DEFAULT,
      kinit_timer_ms=parameters.get(KERBEROS_KINIT_TIMER_PARAMETER, DEFAULT_KERBEROS_KINIT_TIMER_MS))
    if not response:
      raise Exception(error_msg or 'empty response')
    return json.loads(response)

  context = ssl._create_unverified_context() if url.startswith('https') else None
  response = urllib.request.urlopen(url, timeout=int(CONNECTION_TIMEOUT_DEFAULT), context=context)
  try:
    return json.loads(response.read())
  finally:
    response.close()


def capacity_alert(state, parameters):
  report = state['storageReport']
  capacity = float(report['capacity'])
  used = float(report['used'])
  remaining = float(report['remaining'])
  if capacity <= 0:
    return (RESULT_STATE_UNKNOWN, ['Ozone reports no storage capacity (no DataNode registered with Recon)'])

  percent_used = used * 100.0 / capacity
  warning = float(parameters.get(KEY_CAPACITY_USED_WARNING, CAPACITY_USED_WARNING_DEFAULT))
  critical = float(parameters.get(KEY_CAPACITY_USED_CRITICAL, CAPACITY_USED_CRITICAL_DEFAULT))
  message = 'Capacity Used:[{0:.0f}%, {1}], Capacity Remaining:[{2}], Capacity Total:[{3}]'.format(
    percent_used, format_bytes(used), format_bytes(remaining), format_bytes(capacity))

  if percent_used >= critical:
    return (RESULT_STATE_CRITICAL, [message])
  if percent_used >= warning:
    return (RESULT_STATE_WARNING, [message])
  return (RESULT_STATE_OK, [message])


def datanode_health_alert(state, parameters):
  total = int(state['totalDatanodes'])
  healthy = int(state['healthyDatanodes'])
  unhealthy = total - healthy
  warning = float(parameters.get(KEY_DATANODES_UNHEALTHY_WARNING, DATANODES_UNHEALTHY_WARNING_DEFAULT))
  critical = float(parameters.get(KEY_DATANODES_UNHEALTHY_CRITICAL, DATANODES_UNHEALTHY_CRITICAL_DEFAULT))

  if unhealthy <= 0:
    return (RESULT_STATE_OK, ['All {0} DataNode(s) are healthy'.format(total)])

  message = 'DataNode Health: [Healthy={0}, Not healthy={1}, Total={2}]'.format(healthy, unhealthy, total)
  if unhealthy >= critical:
    return (RESULT_STATE_CRITICAL, [message])
  if unhealthy >= warning:
    return (RESULT_STATE_WARNING, [message])
  return (RESULT_STATE_OK, [message])


def format_bytes(value):
  value = float(value)
  for unit in ('B', 'KB', 'MB', 'GB', 'TB'):
    if abs(value) < 1024.0:
      return '{0:.1f} {1}'.format(value, unit)
    value /= 1024.0
  return '{0:.1f} PB'.format(value)
