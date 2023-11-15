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
"""

import urllib.request
import ambari_simplejson as json # simplejson is much faster comparing to Python 2.6 json module and has the same functions set.
import logging
import traceback

from resource_management.libraries.functions.curl_krb_request import curl_krb_request
from resource_management.libraries.functions.curl_krb_request import DEFAULT_KERBEROS_KINIT_TIMER_MS
from resource_management.libraries.functions.curl_krb_request import KERBEROS_KINIT_TIMER_PARAMETER
from resource_management.libraries.functions.curl_krb_request import CONNECTION_TIMEOUT_DEFAULT
from resource_management.core.environment import Environment
from resource_management.libraries.functions.ozone_utils import get_all_recon_addresses

HDDS_DN_HTTP_ADDRESS_KEY = '{{ozone-site/hdds.datanode.http-address}}'
HDDS_DN_HTTPS_ADDRESS_KEY = '{{ozone-site/hdds.datanode.https-address}}'
HDDS_DN_HTTP_POLICY_KEY = '{{ozone-site/ozone.http.policy}}'

OZONE_SITE_KEY = '{{ozone-site}}'
KERBEROS_KEYTAB = '{{ozone-site/hdds.datanode.http.auth.kerberos.keytab}}'
KERBEROS_PRINCIPAL = '{{ozone-site/hdds.datanode.http.auth.kerberos.principal}}'
SECURITY_ENABLED_KEY = '{{cluster-env/security_enabled}}'
SMOKEUSER_KEY = "{{cluster-env/smokeuser}}"
EXECUTABLE_SEARCH_PATHS = '{{kerberos-env/executable_search_paths}}'
logger = logging.getLogger('ambari_alerts')

RESULT_STATE_CRITICAL = 'CRITICAL'
RESULT_STATE_WARNING = 'WARNING'
RESULT_STATE_UNKNOWN = 'UNKNOWN'
RESULT_STATE_SKIPPED = 'SKIPPED'
RESULT_STATE_OK = 'GOOD'

KEY_CAPACITY_USED_CRITICAL = 'capacity.datanode.used.critical.threshold'
KEY_CAPACITY_USED_WARNING = 'capacity.datanode.used.warning.threshold'


def execute(configurations={}, parameters={}, host_name=None):
  """
  Returns a tuple containing the result code and a pre-formatted result label

  Keyword arguments:
  configurations : a mapping of configuration key to value
  parameters : a mapping of script parameter key to value
  host_name : the name of this host where the alert is running

  :type configurations dict
  :type parameters dict
  :type host_name str
  """

  if configurations is None:
    return (('UNKNOWN', ['There were no configurations supplied to the script.']))

  uri = None
  http_policy = 'HTTP_ONLY'

  # ozone-site is required
  if not OZONE_SITE_KEY in configurations:
    return 'SKIPPED', ['{0} is a required parameter for the script'.format(OZONE_SITE_KEY)]

  if HDDS_DN_HTTP_POLICY_KEY in configurations:
    http_policy = configurations[HDDS_DN_HTTP_POLICY_KEY]

  if SMOKEUSER_KEY in configurations:
    smokeuser = configurations[SMOKEUSER_KEY]

  executable_paths = None
  if EXECUTABLE_SEARCH_PATHS in configurations:
    executable_paths = configurations[EXECUTABLE_SEARCH_PATHS]

  security_enabled = False
  if SECURITY_ENABLED_KEY in configurations:
    security_enabled = str(configurations[SECURITY_ENABLED_KEY]).upper() == 'TRUE'

  kerberos_keytab = None
  if KERBEROS_KEYTAB in configurations:
    kerberos_keytab = configurations[KERBEROS_KEYTAB]

  kerberos_principal = None
  if KERBEROS_PRINCIPAL in configurations:
    kerberos_principal = configurations[KERBEROS_PRINCIPAL]
    kerberos_principal = kerberos_principal.replace('_HOST', host_name)

  kinit_timer_ms = parameters.get(KERBEROS_KINIT_TIMER_PARAMETER, DEFAULT_KERBEROS_KINIT_TIMER_MS)

  # determine the right URI and whether to use SSL
  hdfs_site = configurations[OZONE_SITE_KEY]

  scheme = "https" if http_policy == "HTTPS_ONLY" else "http"
  address = RECON_HTTPS_ADDRESS_KEY if http_policy == "HTTPS_ONLY" else RECON_HTTP_ADDRESS_KEY
  uri = "{0}:{1}".format(host_name, address.split(':')[1])
  ozone_cluster_state_uri = "{0}://{1}/api/v1/clusterState".format(scheme, uri)

  try:
    if kerberos_principal is not None and kerberos_keytab is not None and security_enabled:
      env = Environment.get_instance()

      clusterStateResponse, error_msg, time_millis = curl_krb_request(
        env.tmp_dir, kerberos_keytab,
        kerberos_principal, ozone_cluster_state_uri, "ozone_cluster_state_uri", executable_paths, False,
        "Ozone Cluster State", smokeuser, kinit_timer_ms = kinit_timer_ms
       )

      cluster_state_response_json = json.loads(clusterStateResponse)
      if KEY_CAPACITY_USED_CRITICAL in parameters:
        generate_capacity_alerts_from_state(cluster_state_response_json, parameters)
      else:
        generate_datanode_alerts_from_state(cluster_state_response_json, parameters)



    else:
      cluster_state_response_json = get_value_from_api(ozone_cluster_state_uri)
      if KEY_CAPACITY_USED_CRITICAL in parameters:
        generate_capacity_alerts_from_state(cluster_state_response_json, parameters)
      else:
        generate_datanode_alerts_from_state(cluster_state_response_json, parameters)

  except:
    label = traceback.format_exc()
    result_code = 'UNKNOWN'

  return ((result_code, [label]))


def formatWithUnit(valueInBytes):
  
  valueInGB = float(valueInBytes)/float(1073741824)
  valueInGB = round(valueInGB,1)
  valueInTB = valueInTB/1024
  valueInTB = round(valueInTB,1)
  valueInMB = valueInGB*1024
  valueInMB = round(valueInMB,1)
  valueInKB = valueInMB*1024
  valueInKB = round(valueInMB,1)
  if valueInTB > 0:
    return format("{valueInTB} TB")
  elif valueInGB > 0 :
    return format("{valueInGB} GB")
  elif valueInMB > 0 :
    return format("{valueInMB} MB")
  else :
    return format("{valueInKB} KB")
  

def generate_datanode_alerts_from_state(state, params):
  # start out assuming an OK status
  label = None
  result_code = "OK"
  numDataNode = state['totalDatanodes']
  numHealthyDatanodes = state['healthyDatanodes']
  dead = numDataNode-numHealthyDatanodes
  
  message =  format("All {numDataNode} DataNode(s) are healthy")
  if dead > params[KEY_DATANODES_DEAD_CRITICAL]:
    message =  format("DataNode Health: [Live={numHealthyDatanodes}, Stale={dead}]")
    return (RESULT_STATE_CRITICAL,[message])
  elif dead > params[KEY_DATANODES_DEAD_WARNING]:
    message =  format("DataNode Health: [Live={numHealthyDatanodes}, Stale={dead}]")
    return (RESULT_STATE_WARNING,[message])
  else:
    return (RESULT_STATE_OK,[message])

def generate_capacity_alerts_from_state(state, params):
  # start out assuming an OK status
  label = None
  result_code = "OK"
  
  ozone_total_capacity = state['storageReport']['capacity'] #by default in Bytes
  ozone_used_capacity = state['storageReport']['used'] #by default in Bytes
  ozone_remaining_capacity = state['storageReport']['remaining'] # by default in Bytes
  percent_used = float(ozone_used_capacity/ozone_total_capacity)
  
  message = "Capacity Used:[{2:.0f}%, {0}], Capacity Remaining:[{1}]".format(percent_used, formatWithUnit(ozone_used_capacity), formatWithUnit(ozone_remaining_capacity))
  
  if percent_used >= params['KEY_CAPACITY_USED_CRITICAL']:
    return (RESULT_STATE_CRITICAL,[message])
  elif percent_used >= params['KEY_CAPACITY_USED_WARNING']:
    return (RESULT_STATE_WARNING,[message])
  else:
    return (RESULT_STATE_OK,[message])

def get_value_from_api(uri):
  """
   Read cluster state from recon ui

  :param uri: uri path
  :return: cluster state value
  
  :type query str
  :type cluster state json
  """
  response = None

  try:
    response = urllib.request.urlopen(uri, timeout=int(CONNECTION_TIMEOUT_DEFAULT))
    data = response.read()

    return json.loads(data)
  finally:
    if response is not None:
      try:
        response.close()
      except:
        pass
