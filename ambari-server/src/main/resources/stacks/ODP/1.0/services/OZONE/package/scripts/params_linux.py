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
import os
import status_params
import ambari_simplejson as json # simplejson is much faster comparing to Python 2.6 json module and has the same functions set.

from functions import calc_xmn_from_xms, ensure_unit_for_memory

from ambari_commons.constants import AMBARI_SUDO_BINARY
from ambari_commons.os_check import OSCheck
from ambari_commons.str_utils import string_set_intersection

from resource_management.libraries.resources.hdfs_resource import HdfsResource
from resource_management.libraries.functions import conf_select
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions import format
from resource_management.libraries.functions import StackFeature
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.stack_features import get_stack_feature_version
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions import get_kinit_path
from resource_management.libraries.functions import is_empty
from resource_management.libraries.functions import get_unique_id_and_date
from resource_management.libraries.functions import om_ha_utils
from resource_management.libraries.functions.get_not_managed_resources import get_not_managed_resources
from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.expect import expect
from ambari_commons.ambari_metrics_helper import select_metric_collector_hosts_from_hostnames
from resource_management.core.logger import Logger
from resource_management.libraries.functions.setup_ranger_plugin_xml import get_audit_configs, generate_ranger_service_config

# server configurations
config = Script.get_config()
exec_tmp_dir = Script.get_tmp_dir()
sudo = AMBARI_SUDO_BINARY
hostname = config['agentLevelParams']['hostname']

jsvc_path = "/usr/lib/bigtop-utils"

ozone_secure_dn_user = config['configurations']['ozone-env']['ozone_user']

stack_name = status_params.stack_name
agent_stack_retry_on_unavailability = config['ambariLevelParams']['agent_stack_retry_on_unavailability']
agent_stack_retry_count = expect("/ambariLevelParams/agent_stack_retry_count", int)
version = default("/commandParams/version", None)
component_directory = status_params.component_directory
etc_prefix_dir = "/etc/hadoop"
ozone_base_conf_dir = format("{etc_prefix_dir}/conf")

stack_version_unformatted = status_params.stack_version_unformatted
stack_version_formatted = status_params.stack_version_formatted
stack_root = status_params.stack_root

# get the correct version to use for checking stack features
version_for_stack_feature_checks = get_stack_feature_version(config)

stack_supports_ranger_kerberos = check_stack_feature(StackFeature.RANGER_KERBEROS_SUPPORT, version_for_stack_feature_checks)
stack_supports_ranger_audit_db = check_stack_feature(StackFeature.RANGER_AUDIT_DB_SUPPORT, version_for_stack_feature_checks)

# hadoop default parameters
hadoop_ozone_bin_dir = stack_select.get_hadoop_ozone_dir("bin")
hadoop_conf_dir = conf_select.get_hadoop_conf_dir()
daemon_script = format('{stack_root}/current/ozone-client/bin/ozone')
ozone_cmd = format('{stack_root}/current/ozone-client/bin/ozone')

ROLE_NAME_MAP_CONF = {
      'ozone-manager': 'ozone.om',
      'ozone-s3g': 'ozone.s3g',
      'ozone-datanode': 'ozone.datanode',
      'ozone-recon': 'ozone.recon',
      'ozone-scm': 'ozone.scm',
      'ozone-httpfs': 'ozone.httpfs',

}
ROLE_NAME_MAP_DAEMON = {
      'ozone-manager': 'om',
      'ozone-s3g': 's3g',
      'ozone-datanode': 'datanode',
      'ozone-recon': 'recon',
      'ozone-scm': 'scm',
      'ozone-httpfs': 'httpfs',

}
ROLE_CONF_MAP = {
      'ozone-manager': {},
      'ozone-s3g': {},
      'ozone-datanode': {},
      'ozone-recon': {},
      'ozone-scm': {},
      'ozone-httpfs': {},
      'ozone-client': {}
}

limits_conf_dir = status_params.limits_conf_dir

ozone_user_nofile_limit = default("/configurations/ozone-env/ozone_user_nofile_limit", "32000")
ozone_user_nproc_limit = default("/configurations/ozone-env/ozone_user_nproc_limit", "16000")

ozone_excluded_hosts = config['commandParams']['excluded_hosts']
ozone_drain_only = default("/commandParams/mark_draining_only",False)
ozone_included_hosts = config['commandParams']['included_hosts']

ozone_user = config['configurations']['ozone-env']['ozone_user']
ozone_principal_name = config['configurations']['ozone-env']['ozone_principal_name']
smokeuser = config['configurations']['cluster-env']['smokeuser']
_authentication = 'simple'
hadoop_security_authorization = ''
hadoop_security_auth_to_local = 'DEFAULT'
for k, v in config['configurations']['ozone-core-site'].items():
  if k is 'hadoop.security.authentication':
    _authentication = v
hadoop_security_authorization = config['configurations']['ozone-core-site']['hadoop.security.authorization']
hadoop_security_auth_to_local = config['configurations']['ozone-core-site']['hadoop.security.auth_to_local']
hadoop_security_authentication = _authentication
security_enabled = config['configurations']['cluster-env']['security_enabled']

# HDFS/OZONE cohabitation params
nn_hosts = default("/clusterHostInfo/namenode_hosts", [])
is_hdfs_enabled = 'hdfs-site' in config['configurations'].keys() and len(nn_hosts) >= 1

# this is "hadoop-metrics.properties" for 1.x stacks
metric_prop_file_name = "hadoop-metrics2-hbase.properties"

# not supporting 32 bit jdk.
java64_home = config['ambariLevelParams']['java_home']
java_version = expect("/ambariLevelParams/java_version", int)

pid_dir = config['configurations']['ozone-env']['ozone_pid_dir_prefix']
log_dir = config['configurations']['ozone-env']['ozone_log_dir_prefix']
ozone_http_dir = config['configurations']['ozone-site']['ozone.http.basedir']
java_io_tmpdir = default("/configurations/ozone-env/ozone_java_io_tmpdir", "/tmp")
dfs_data_dirs = config['configurations']['ozone-site']['hdds.datanode.dir']
data_dir_mount_file = "/var/lib/ambari-agent/data/datanode/hdds_data_dir_mount.hist"
ozone_dn_ratis_dir = config['configurations']['ozone-site']['dfs.container.ratis.datanode.storage.dir']

## SSL related properties
# for component in ['om','scm','datanode','s3g', 'recon']:

## SSL related properties

ssl_server_props_ignore = [
  'ssl.server.keystore.password',
  'ssl.server.keystore.keypassword',
]
ssl_client_props_ignore = [
  'ssl.client.truststore.password'
]

# Ozone Gateway TLS related params #
ozone_ssl_client = {}

for prop in config['configurations']['ozone-ssl-client'].keys():
  ozone_ssl_client[prop] = config['configurations']['ozone-ssl-client'][prop]


# Ozone Manager TLS related params #
ozone_om_credential_file_path = config['configurations']['ssl-server-om']['hadoop.security.credential.provider.path']
ozone_om_tls_ssl_keystore_password = config['configurations']['ssl-server-om']['ssl.server.keystore.password']
ozone_om_tls_ssl_key_password = config['configurations']['ssl-server-om']['ssl.server.keystore.keypassword']
ozone_om_tls_ssl_client_truststore_password = config['configurations']['ssl-client-om']['ssl.client.truststore.password']
om_ssl_server_dict = {}
om_ssl_client_dict = {}

om_ssl_enabled = default("/configurations/ozone-env/ozone_manager_ssl_enabled", False)
if om_ssl_enabled:
  Logger.debug("Preparing Ozone Manager SSL/TLS Dictionnaries")
  for prop in config['configurations']['ssl-server-om'].keys():
    if prop not in ssl_server_props_ignore:
      om_ssl_server_dict[prop] = config['configurations']['ssl-server-om'][prop]
    else:
      Logger.debug("Skipping property {prop} when computing om_ssl_server")
  for prop in config['configurations']['ssl-client-om'].keys():
    if prop not in ssl_client_props_ignore:
      om_ssl_client_dict[prop] = config['configurations']['ssl-client-om'][prop]
    else:
      Logger.debug("Skipping property {prop} when computing om_ssl_client")

# Ozone Storage Container TLS related params #
ozone_scm_credential_file_path = config['configurations']['ssl-server-scm']['hadoop.security.credential.provider.path']
ozone_scm_tls_ssl_keystore_password = config['configurations']['ssl-server-scm']['ssl.server.keystore.password']
ozone_scm_tls_ssl_key_password = config['configurations']['ssl-server-scm']['ssl.server.keystore.keypassword']
ozone_scm_tls_ssl_client_truststore_password = config['configurations']['ssl-client-scm']['ssl.client.truststore.password']
scm_ssl_server_dict = {}
scm_ssl_client_dict = {}
scm_ssl_enabled = default("/configurations/ozone-env/ozone_scm_ssl_enabled", False)
if scm_ssl_enabled:
  Logger.debug("Preparing Ozone Storage Container Manager SSL/TLS Dictionnaries")
  for prop in config['configurations']['ssl-server-scm'].keys():
    if prop not in ssl_server_props_ignore:
      scm_ssl_server_dict[prop] = config['configurations']['ssl-server-scm'][prop]
    else:
      Logger.debug("Skipping property {prop} when computing scm_ssl_server")
  for prop in config['configurations']['ssl-client-scm'].keys():
    if prop not in ssl_client_props_ignore:
      scm_ssl_client_dict[prop] = config['configurations']['ssl-client-scm'][prop]
    else:
      Logger.debug("Skipping property {prop} when computing scm_ssl_client")


## Ozone SCM HA dirs
# path looks like #/var/lib/hadoop-ozone/hdds-metadata/scm/ca/certs/certificate.crt
ozone_scm_hdds_metadata_dir = default("/configurations/ozone-site/hdds.metadata.dir", "/var/lib/hadoop-ozone/hdds-metadata")
ozone_scm_hdds_x509_dir = default("/configurations/ozone-site/hdds.x509.dir.name", "certs")
ozone_scm_hdds_x509_filename = default("/configurations/ozone-site/hdds.x509.file.name", "certificate.crt")
ozone_security_enabled = security_enabled and config['configurations']['ozone-site']['ozone.security.enabled']
ozone_scm_ha_tls_enabled = config['configurations']['ozone-site']['hdds.grpc.tls.enabled']

# Ozone Datanode TLS related params #
ozone_dn_credential_file_path = config['configurations']['ssl-server-datanode']['hadoop.security.credential.provider.path']
ozone_dn_tls_ssl_keystore_password = config['configurations']['ssl-server-datanode']['ssl.server.keystore.password']
ozone_dn_tls_ssl_key_password = config['configurations']['ssl-server-datanode']['ssl.server.keystore.keypassword']
ozone_dn_tls_ssl_client_truststore_password = config['configurations']['ssl-client-datanode']['ssl.client.truststore.password']
dn_ssl_enabled = default("/configurations/ozone-env/ozone_datanode_ssl_enabled", False)
dn_ssl_server_dict = {}
dn_ssl_client_dict = {}
if dn_ssl_enabled:
  Logger.debug("Preparing Ozone Datanode SSL/TLS Dictionnaries")
  for prop in config['configurations']['ssl-server-datanode'].keys():
    if prop not in ssl_server_props_ignore:
      dn_ssl_server_dict[prop] = config['configurations']['ssl-server-datanode'][prop]
    else:
      Logger.debug("Skipping property {prop} when computing dn_ssl_server")
  for prop in config['configurations']['ssl-client-datanode'].keys():
    if prop not in ssl_client_props_ignore:
      dn_ssl_client_dict[prop] = config['configurations']['ssl-client-datanode'][prop]
    else:
      Logger.debug("Skipping property {prop} when computing dn_ssl_client")

# Ozone Recon TLS related params #
ozone_recon_credential_file_path = config['configurations']['ssl-server-recon']['hadoop.security.credential.provider.path']
ozone_recon_tls_ssl_keystore_password = config['configurations']['ssl-server-recon']['ssl.server.keystore.password']
ozone_recon_tls_ssl_key_password = config['configurations']['ssl-server-recon']['ssl.server.keystore.keypassword']
ozone_recon_tls_ssl_client_truststore_password = config['configurations']['ssl-client-recon']['ssl.client.truststore.password']
recon_ssl_enabled = default("/configurations/ozone-env/ozone_recon_ssl_enabled", False)
recon_ssl_server_dict = {}
recon_ssl_client_dict = {}
if recon_ssl_enabled:
  Logger.debug("Preparing Ozone Datanode SSL/TLS Dictionnaries")
  for prop in config['configurations']['ssl-server-recon'].keys():
    if prop not in ssl_server_props_ignore:
      recon_ssl_server_dict[prop] = config['configurations']['ssl-server-recon'][prop]
    else:
      Logger.debug("Skipping property {prop} when computing recon_ssl_server")
  for prop in config['configurations']['ssl-client-recon'].keys():
    if prop not in ssl_client_props_ignore:
      recon_ssl_client_dict[prop] = config['configurations']['ssl-client-recon'][prop]
    else:
      Logger.debug("Skipping property {prop} when computing recon_ssl_client")

# Ozone S3G TLS related params #
ozone_s3g_credential_file_path = config['configurations']['ssl-server-s3g']['hadoop.security.credential.provider.path']
ozone_s3g_tls_ssl_keystore_password = config['configurations']['ssl-server-s3g']['ssl.server.keystore.password']
ozone_s3g_tls_ssl_key_password = config['configurations']['ssl-server-s3g']['ssl.server.keystore.keypassword']
ozone_s3g_tls_ssl_client_truststore_password = config['configurations']['ssl-client-s3g']['ssl.client.truststore.password']
s3g_ssl_enabled = default("/configurations/ozone-env/ozone_s3g_ssl_enabled", False)
s3g_ssl_server_dict = {}
s3g_ssl_client_dict = {}
if s3g_ssl_enabled:
  Logger.debug("Preparing Ozone S3 Gateway SSL/TLS Dictionnaries")
  for prop in config['configurations']['ssl-server-s3g'].keys():
    if prop not in ssl_server_props_ignore:
      s3g_ssl_server_dict[prop] = config['configurations']['ssl-server-s3g'][prop]
    else:
      Logger.debug("Skipping property {prop} when computing s3g_ssl_server")
  for prop in config['configurations']['ssl-client-s3g'].keys():
    if prop not in ssl_client_props_ignore:
      s3g_ssl_client_dict[prop] = config['configurations']['ssl-client-s3g'][prop]
    else:
      Logger.debug("Skipping property {prop} when computing s3g_ssl_client")

## Ozone heapsizes

ozone_client_heapsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_heapsize'])
ozone_manager_heapsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_manager_heapsize'])
ozone_manager_opt_newsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_manager_opt_newsize'])
ozone_manager_opt_maxnewsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_manager_opt_maxnewsize'])
ozone_manager_opt_permsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_manager_opt_permsize'])
ozone_manager_opt_maxpermsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_manager_opt_maxpermsize'])
ozone_scm_heapsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_scm_heapsize'])
ozone_scm_opt_newsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_scm_opt_newsize'])
ozone_scm_opt_maxnewsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_scm_opt_maxnewsize'])
ozone_scm_opt_permsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_scm_opt_permsize'])
ozone_scm_opt_maxpermsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_scm_opt_maxpermsize'])
ozone_datanode_heapsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_datanode_heapsize'])
ozone_datanode_opt_newsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_datanode_opt_newsize'])
ozone_datanode_opt_maxnewsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_datanode_opt_maxnewsize'])
ozone_datanode_opt_permsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_datanode_opt_permsize'])
ozone_datanode_opt_maxpermsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_datanode_opt_maxpermsize'])
ozone_s3g_heapsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_s3g_heapsize'])
ozone_s3g_opt_newsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_s3g_opt_newsize'])
ozone_s3g_opt_maxnewsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_s3g_opt_maxnewsize'])
ozone_s3g_opt_permsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_s3g_opt_permsize'])
ozone_s3g_opt_maxpermsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_s3g_opt_maxpermsize'])
ozone_recon_heapsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_recon_heapsize'])
ozone_recon_opt_newsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_recon_opt_newsize'])
ozone_recon_opt_maxnewsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_recon_opt_maxnewsize'])
ozone_recon_opt_permsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_recon_opt_permsize'])
ozone_recon_opt_maxpermsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_recon_opt_maxpermsize'])
ozone_httpfs_heapsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_httpfs_heapsize'])
ozone_httpfs_opt_newsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_httpfs_opt_newsize'])
ozone_httpfs_opt_maxnewsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_httpfs_opt_maxnewsize'])
ozone_httpfs_opt_permsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_httpfs_opt_permsize'])
ozone_httpfs_opt_maxpermsize = ensure_unit_for_memory(config['configurations']['ozone-env']['ozone_httpfs_opt_maxpermsize'])

## Ozone JAVA options
ozone_client_java_opts = default("/configurations/ozone-env/ozone_java_opts", "")
ozone_manager_java_opts = default("/configurations/ozone-env/ozone_manager_java_opts", "")
ozone_scm_java_opts = default("/configurations/ozone-env/ozone_scm_java_opts", "")
ozone_datanode_java_opts = default("/configurations/ozone-env/ozone_datanode_java_opts", "")
ozone_recon_java_opts = default("/configurations/ozone-env/ozone_recon_java_opts", "")
ozone_s3g_java_opts = default("/configurations/ozone-env/ozone_s3g_java_opts", "")
ozone_httpfs_java_opts = default("/configurations/ozone-env/ozone_httpfs_java_opts", "")

underscored_version = stack_version_unformatted.replace('.', '_')
dashed_version = stack_version_unformatted.replace('.', '-')

ozone_om_jaas_config_file = "ozone_om_jaas.conf"
ozone_scm_jaas_config_file = "ozone_scm_jaas.conf"
ozone_dn_jaas_config_file = "ozone_datanode_jaas.conf"
ozone_recon_jaas_config_file = "ozone_recon_jaas.conf"
ozone_s3g_jaas_config_file = "ozone_gateway_jaas.conf"

set_instanceId = "false"
if 'cluster-env' in config['configurations'] and \
    'metrics_collector_external_hosts' in config['configurations']['cluster-env']:
  ams_collector_hosts = config['configurations']['cluster-env']['metrics_collector_external_hosts']
  set_instanceId = "true"
else:
  ams_collector_hosts = ",".join(default("/clusterHostInfo/metrics_collector_hosts", []))
has_metric_collector = not len(ams_collector_hosts) == 0
metric_collector_port = None
if has_metric_collector:
  if 'cluster-env' in config['configurations'] and \
      'metrics_collector_external_port' in config['configurations']['cluster-env']:
    metric_collector_port = config['configurations']['cluster-env']['metrics_collector_external_port']
  else:
    metric_collector_web_address = default("/configurations/ams-site/timeline.metrics.service.webapp.address", "0.0.0.0:6188")
    if metric_collector_web_address.find(':') != -1:
      metric_collector_port = metric_collector_web_address.split(':')[1]
    else:
      metric_collector_port = '6188'
  if default("/configurations/ams-site/timeline.metrics.service.http.policy", "HTTP_ONLY") == "HTTPS_ONLY":
    metric_collector_protocol = 'https'
  else:
    metric_collector_protocol = 'http'
  metric_truststore_path= default("/configurations/ams-ssl-client/ssl.client.truststore.location", "")
  metric_truststore_type= default("/configurations/ams-ssl-client/ssl.client.truststore.type", "")
  metric_truststore_password= default("/configurations/ams-ssl-client/ssl.client.truststore.password", "")
  pass
metrics_report_interval = default("/configurations/ams-site/timeline.metrics.sink.report.interval", 60)
metrics_collection_period = default("/configurations/ams-site/timeline.metrics.sink.collection.period", 10)

host_in_memory_aggregation = default("/configurations/ams-site/timeline.metrics.host.inmemory.aggregation", True)
host_in_memory_aggregation_port = default("/configurations/ams-site/timeline.metrics.host.inmemory.aggregation.port", 61888)
is_aggregation_https_enabled = False
if default("/configurations/ams-site/timeline.metrics.host.inmemory.aggregation.http.policy", "HTTP_ONLY") == "HTTPS_ONLY":
  host_in_memory_aggregation_protocol = 'https'
  is_aggregation_https_enabled = True
else:
  host_in_memory_aggregation_protocol = 'http'

smoke_test_user = config['configurations']['cluster-env']['smokeuser']
smokeuser_principal =  config['configurations']['cluster-env']['smokeuser_principal_name']
smokeuser_permissions = "RWXCA"
service_check_data = get_unique_id_and_date()
user_group = config['configurations']['cluster-env']["user_group"]

if security_enabled:
  _hostname_lowercase = config['agentLevelParams']['hostname'].lower()
  ## ozone manager
  om_jaas_princ = config['configurations']['ozone-site']['ozone.om.kerberos.principal'].replace('_HOST',_hostname_lowercase)
  om_keytab_path = config['configurations']['ozone-site']['ozone.om.kerberos.keytab.file']
  ## ozone scm
  scm_jaas_princ = config['configurations']['ozone-site']['hdds.scm.kerberos.principal'].replace('_HOST',_hostname_lowercase)
  scm_keytab_path = config['configurations']['ozone-site']['hdds.scm.kerberos.keytab.file']
  ## ozone dn
  dn_jaas_princ = config['configurations']['ozone-site']['dfs.datanode.kerberos.principal'].replace('_HOST',_hostname_lowercase)
  dn_keytab_path = config['configurations']['ozone-site']['dfs.datanode.keytab.file']
  ## ozone s3g
  s3g_jaas_princ = config['configurations']['ozone-site']['ozone.s3g.kerberos.principal'].replace('_HOST',_hostname_lowercase)
  s3g_keytab_path = config['configurations']['ozone-site']['ozone.s3g.kerberos.keytab.file']
  ## ozone recon
  recon_jaas_princ = config['configurations']['ozone-site']['ozone.recon.kerberos.principal'].replace('_HOST',_hostname_lowercase)
  recon_keytab_path = config['configurations']['ozone-site']['ozone.recon.kerberos.keytab.file']

smoke_user_keytab = config['configurations']['cluster-env']['smokeuser_keytab']
ozone_user_keytab = config['configurations']['ozone-env']['ozone_user_keytab']
kinit_path_local = get_kinit_path(default('/configurations/kerberos-env/executable_search_paths', None))
if security_enabled:
  kinit_cmd = format("{kinit_path_local} -kt {ozone_user_keytab} {ozone_principal_name};")
  kinit_cmd_om = format("{kinit_path_local} -kt {om_keytab_path} {om_jaas_princ};")
  master_security_config = format("-Djava.security.auth.login.config={ozone_base_conf_dir}ozone.om/ozone_om_jaas.conf")
else:
  kinit_cmd = ""
  kinit_cmd_master = ""
  master_security_config = ""

# Log4j Properties
## each service has its own properties
#  Manager
ozone_manager_log_level = default('configurations/ozone-env/ozone_manager_log_level','INFO')
ozone_manager_security_log_max_backup_size = default('configurations/ozone-log4j-om/ozone_security_log_max_backup_size',256)
ozone_manager_security_log_number_of_backup_files = default('configurations/ozone-log4j-om/ozone_security_log_number_of_backup_files',20)
ozone_manager_ozone_log_max_backup_size = default('configurations/ozone-log4j-om/ozone_log_max_backup_size',256)
ozone_manager_ozone_log_number_of_backup_files = default('configurations/ozone-log4j-om/ozone_log_number_of_backup_files',20)
ozone_manager_log4j_content = config['configurations']['ozone-log4j-om']['content']

# Storage Container Manager
ozone_scm_log_level = default('configurations/ozone-env/ozone_scm_log_level','INFO')
ozone_scm_security_log_max_backup_size = default('configurations/ozone-log4j-scm/ozone_security_log_max_backup_size',256)
ozone_scm_security_log_number_of_backup_files = default('configurations/ozone-log4j-scm/ozone_security_log_number_of_backup_files',20)
ozone_scm_ozone_log_max_backup_size = default('configurations/ozone-log4j-scm/ozone_log_max_backup_size',256)
ozone_scm_ozone_log_number_of_backup_files = default('configurations/ozone-log4j-scm/ozone_log_number_of_backup_files',20)
ozone_scm_log4j_content = config['configurations']['ozone-log4j-scm']['content']
# Datanode
ozone_datanode_log_level = default('configurations/ozone-env/ozone_datanode_log_level','INFO')
ozone_datanode_security_log_max_backup_size = default('configurations/ozone-log4j-datanode/ozone_security_log_max_backup_size',256)
ozone_datanode_security_log_number_of_backup_files = default('configurations/ozone-log4j-datanode/ozone_security_log_number_of_backup_files',20)
ozone_datanode_ozone_log_max_backup_size = default('configurations/ozone-log4j-datanode/ozone_log_max_backup_size',256)
ozone_datanode_ozone_log_number_of_backup_files = default('configurations/ozone-log4j-datanode/ozone_log_number_of_backup_files',20)
ozone_datanode_log4j_content = config['configurations']['ozone-log4j-datanode']['content']

# Recon
ozone_recon_log_level = default('configurations/ozone-env/ozone_recon_log_level','INFO')
ozone_recon_security_log_max_backup_size = default('configurations/ozone-log4j-recon/ozone_security_log_max_backup_size',256)
ozone_recon_security_log_number_of_backup_files = default('configurations/ozone-log4j-recon/ozone_security_log_number_of_backup_files',20)
ozone_recon_ozone_log_max_backup_size = default('configurations/ozone-log4j-recon/ozone_log_max_backup_size',256)
ozone_recon_ozone_log_number_of_backup_files = default('configurations/ozone-log4j-recon/ozone_log_number_of_backup_files',20)
ozone_recon_log4j_content = config['configurations']['ozone-log4j-recon']['content']

# HTTPFS Gateway
ozone_httpfs_log_level = default('configurations/ozone-env/ozone_httpfs_log_level','INFO')
ozone_httpfs_security_log_max_backup_size = default('configurations/ozone-log4j-httpfs/ozone_security_log_max_backup_size',256)
ozone_httpfs_security_log_number_of_backup_files = default('configurations/ozone-log4j-httpfs/ozone_security_log_number_of_backup_files',20)
ozone_httpfs_ozone_log_max_backup_size = default('configurations/ozone-log4j-httpfs/ozone_log_max_backup_size',256)
ozone_httpfs_ozone_log_number_of_backup_files = default('configurations/ozone-log4j-httpfs/ozone_log_number_of_backup_files',20)
ozone_httpfs_log4j_content = config['configurations']['ozone-log4j-httpfs']['content']

# S3 Gateway
ozone_s3g_log_level = default('configurations/ozone-env/ozone_s3g_log_level','INFO')
ozone_s3g_security_log_max_backup_size = default('configurations/ozone-log4j-s3g/ozone_security_log_max_backup_size',256)
ozone_s3g_security_log_number_of_backup_files = default('configurations/ozone-log4j-s3g/ozone_security_log_number_of_backup_files',20)
ozone_s3g_ozone_log_max_backup_size = default('configurations/ozone-log4j-s3g/ozone_log_max_backup_size',256)
ozone_s3g_ozone_log_number_of_backup_files = default('configurations/ozone-log4j-s3g/ozone_log_number_of_backup_files',20)
ozone_s3g_log4j_content = config['configurations']['ozone-log4j-s3g']['content']

## High Availability, Bootstrap and Init related params

ozone_env_sh_template = config['configurations']['ozone-env']['content']
ozone_log4j_content = config['configurations']['ozone-log4j-properties']['content']

scm_hosts = default("/clusterHostInfo/ozone_scm_hosts", [])
om_hosts = default("/clusterHostInfo/ozone_om_hosts", [])
ozone_scm_ha_is_enabled = len(scm_hosts) > 1
ozone_om_ha_is_enabled = len(om_hosts) >1

ozone_manager_web_port = 9874
om_protocol = 'https' if config['configurations']['ozone-env']['ozone_manager_ssl_enabled'] else 'http'
om_prop = format("ozone.om.{om_protocol}-address")
web_port = config['configurations']['ozone-site'][om_prop].split(':')[1]
om_web_address = format("{hostname}:{web_port}")


ozone_scm_db_dirs = config['configurations']['ozone-site']['ozone.scm.db.dirs']
ozone_manager_ha_dirs = config['configurations']['ozone-site']['ozone.om.ratis.storage.dir']
ozone_om_snapshot_dirs = config['configurations']['ozone-site']['ozone.om.ratis.snapshot.dir']
ozone_recon_db_dir = config['configurations']['ozone-site']['ozone.recon.db.dir']

core_site = config['configurations']['core-site']
ozone_core_site = config['configurations']['ozone-core-site']
#for now ozone datanode and manager use `ozone.metadata.dirs` as configuration key
for prop in config['configurations']['ozone-site'].keys():
  ROLE_CONF_MAP['ozone-manager'][prop] = config['configurations']['ozone-site'][prop]
  ROLE_CONF_MAP['ozone-datanode'][prop] = config['configurations']['ozone-site'][prop]
  ROLE_CONF_MAP['ozone-s3g'][prop] = config['configurations']['ozone-site'][prop]
  ROLE_CONF_MAP['ozone-recon'][prop] = config['configurations']['ozone-site'][prop]
  ROLE_CONF_MAP['ozone-scm'][prop] = config['configurations']['ozone-site'][prop]
  ROLE_CONF_MAP['ozone-httpfs'][prop] = config['configurations']['ozone-site'][prop]
  ROLE_CONF_MAP['ozone-client'][prop] = config['configurations']['ozone-site'][prop]

ozone_datanode_metadata_dir = config['configurations']['ozone-site']['ozone.metadata.dirs']
ozone_manager_metadata_dir =  config['configurations']['ozone-site']['ozone.metadata.dirs']
ozone_scm_metadata_dir =  config['configurations']['ozone-site']['ozone.metadata.dirs']
ozone_recon_scm_metadata_dir =  config['configurations']['ozone-site']['ozone.recon.scm.db.dirs']
ozone_recon_om_metadata_dir =  config['configurations']['ozone-site']['ozone.recon.om.db.dir']
override_metadata_dir_user =  default("/configurations/ozone-env/override_metadata_dir", False)
if not override_metadata_dir_user:
  ROLE_CONF_MAP['ozone-manager']['ozone.metadata.dirs'] = format(str(ROLE_CONF_MAP['ozone-manager']['ozone.metadata.dirs'])+"/"+'om-metadata')
  ROLE_CONF_MAP['ozone-datanode']['ozone.metadata.dirs'] = str(ROLE_CONF_MAP['ozone-datanode']['ozone.metadata.dirs'])+"/"+'dn-metadata'

ozone_fallback_metadatir = config['configurations']['ozone-site']['ozone.metadata.dirs']
ozone_datanode_metadata_dir=  ROLE_CONF_MAP['ozone-datanode']['ozone.metadata.dirs']
ozone_manager_metadata_dir=  ROLE_CONF_MAP['ozone-manager']['ozone.metadata.dirs']
# ozone scm ha enabled
ozone_scm_ha_current_cluster_nameservice = default('/configurations/ozone-site/ozone.scm.default.service.id', None)
ozone_scm_ha_enabled = ozone_scm_ha_current_cluster_nameservice != None
ozone_scm_ha_dirs = ''
if ozone_scm_ha_enabled: 
  ozone_scm_ha_dirs = config['configurations']['ozone-site']['ozone.scm.ha.ratis.storage.dir'].split(',')
ozone_scm_format_disabled = default("/configurations/cluster-env/ozone_scm_format_disabled", False)
ozone_scm_primordial_node_id = config['configurations']['ozone-site']['ozone.scm.primordial.node.id']
ozone_scm_ha_ratis_port = default("/configurations/ozone-site/ozone.scm.ratis.port", 9894)


ozone_manager_db_dirs = config['configurations']['ozone-site']['ozone.om.db.dirs']
ozone_topology_file = config['configurations']['ozone-site']['net.topology.script.file.name']

#Ozone Manager High Availability
command_phase = default("/commandParams/phase","")

# Ozone Manager High Availability properties
ozone_om_ha_current_cluster_nameservice = default('/configurations/ozone-site/ozone.om.internal.service.id', None)
ozone_om_ha_is_enabled = ozone_om_ha_current_cluster_nameservice != None
ozone_om_ha_props = [
  'ozone.om.internal.service.id',
  'ozone.om.service.ids'
]
ozone_scm_format_disabled = default("/configurations/cluster-env/ozone_om_format_disabled", False)
ozone_ha_om_active = om_ha_utils.get_initial_active_om(default("/configurations/ozone-env", {}))
ozone_om_ratis_port = default("/configurations/ozone-site/ozone.om.ratis-por", "9872")
if not ozone_ha_om_active:
  ozone_ha_om_active = frozenset()
om_service_id = ''
if ozone_om_ha_is_enabled:
  om_service_id = ozone_om_ha_current_cluster_nameservice
else:
  if "ozone.om.address" in config["configurations"]["ozone-site"]:
    om_service_id = config["configurations"]["ozone-site"]["ozone.om.address"]
  else:
    om_service_id = config['clusterHostInfo']['ozone_manager_hosts'][0]

ozone_hdds_metadata_dir = config['configurations']['ozone-site']['hdds.metadata.dir']

# to get db connector jar
jdk_location = config['ambariLevelParams']['jdk_location']

# ranger host
ranger_admin_hosts = default("/clusterHostInfo/ranger_admin_hosts", [])
has_ranger_admin = not len(ranger_admin_hosts) == 0
enable_ranger_ozone = False
if has_ranger_admin:
  rangerlookup_create_user = config['configurations']['ranger-env']['rangerlookup_password'] if 'rangerlookup_password' in config['configurations']['ranger-env'] else False
  if rangerlookup_create_user:
    rangerlookup_password = config['configurations']['ranger-env']['rangerlookup_password']
  # ranger support xml_configuration flag, instead of depending on ranger xml_configurations_supported/ranger-env introduced, using stack feature
  xml_configurations_supported = check_stack_feature(StackFeature.RANGER_XML_CONFIGURATION, version_for_stack_feature_checks)

  # ranger ozone plugin enabled property
  enable_ranger_ozone = default("/configurations/ranger-ozone-plugin-properties/ranger-ozone-plugin-enabled", "No")
  enable_ranger_ozone = True if enable_ranger_ozone.lower() == 'yes' else False
  # ranger ozone properties
  if enable_ranger_ozone:
    # get ranger policy url
    policymgr_mgr_url = config['configurations']['admin-properties']['policymgr_external_url']
    if xml_configurations_supported:
      policymgr_mgr_url = config['configurations']['ranger-ozone-security']['ranger.plugin.ozone.policy.rest.url']

    if not is_empty(policymgr_mgr_url) and policymgr_mgr_url.endswith('/'):
      policymgr_mgr_url = policymgr_mgr_url.rstrip('/')

    # ranger audit db user
    xa_audit_db_user = default('/configurations/admin-properties/audit_db_user', 'rangerlogger')

    # ranger ozone service/repository name
    repo_name = str(config['clusterName']) + '_ozone'
    repo_name_value = config['configurations']['ranger-ozone-security']['ranger.plugin.ozone.service.name']
    if not is_empty(repo_name_value) and repo_name_value != "{{repo_name}}":
      repo_name = repo_name_value

    common_name_for_certificate = config['configurations']['ranger-ozone-plugin-properties']['common.name.for.certificate']
    repo_config_username = config['configurations']['ranger-ozone-plugin-properties']['REPOSITORY_CONFIG_USERNAME']
    ranger_plugin_properties = config['configurations']['ranger-ozone-plugin-properties']
    policy_user = config['configurations']['ranger-ozone-plugin-properties']['policy_user']
    repo_config_password = config['configurations']['ranger-ozone-plugin-properties']['REPOSITORY_CONFIG_PASSWORD']

    # ranger-env config
    ranger_env = config['configurations']['ranger-env']

    # create ranger-env config having external ranger credential properties
    if not has_ranger_admin and enable_ranger_ozone:
      external_admin_username = default('/configurations/ranger-ozone-plugin-properties/external_admin_username', 'admin')
      external_admin_password = default('/configurations/ranger-ozone-plugin-properties/external_admin_password', 'admin')
      external_ranger_admin_username = default('/configurations/ranger-ozone-plugin-properties/external_ranger_admin_username', 'amb_ranger_admin')
      external_ranger_admin_password = default('/configurations/ranger-ozone-plugin-properties/external_ranger_admin_password', 'amb_ranger_admin')
      ranger_env = {}
      ranger_env['admin_username'] = external_admin_username
      ranger_env['admin_password'] = external_admin_password
      ranger_env['ranger_admin_username'] = external_ranger_admin_username
      ranger_env['ranger_admin_password'] = external_ranger_admin_password

    if has_ranger_admin:
      ranger_admin_username = config['configurations']['ranger-env']['admin_username']
      ranger_admin_password = config['configurations']['ranger-env']['admin_password']

    xa_audit_db_password = ''
    if not is_empty(config['configurations']['admin-properties']['audit_db_password']) and stack_supports_ranger_audit_db and has_ranger_admin:
      xa_audit_db_password = config['configurations']['admin-properties']['audit_db_password']

    downloaded_custom_connector = None
    previous_jdbc_jar_name = None
    driver_curl_source = None
    driver_curl_target = None
    previous_jdbc_jar = None

    if has_ranger_admin and stack_supports_ranger_audit_db:
      xa_audit_db_flavor = config['configurations']['admin-properties']['DB_FLAVOR']
      jdbc_jar_name, previous_jdbc_jar_name, audit_jdbc_url, jdbc_driver = get_audit_configs(config)

      downloaded_custom_connector = format("{exec_tmp_dir}/{jdbc_jar_name}") if stack_supports_ranger_audit_db else None
      driver_curl_source = format("{jdk_location}/{jdbc_jar_name}") if stack_supports_ranger_audit_db else None
      driver_curl_target = format("{stack_root}/current/{component_directory}/lib/{jdbc_jar_name}") if stack_supports_ranger_audit_db else None
      previous_jdbc_jar = format("{stack_root}/current/{component_directory}/lib/{previous_jdbc_jar_name}") if stack_supports_ranger_audit_db else None
      sql_connector_jar = ''

    ozone_ranger_plugin_config = {
      'username': repo_config_username,
      'password': repo_config_password,
      'ozone.om.http-address': om_web_address,
      'hadoop.security.authentication': hadoop_security_authentication,
      'hadoop.security.authorization': hadoop_security_authorization,
      'hadoop.security.auth_to_local': hadoop_security_auth_to_local,
      'commonNameForCertificate': common_name_for_certificate
    }

    if security_enabled:
      ozone_ranger_plugin_config['policy.download.auth.users'] = ozone_user
      ozone_ranger_plugin_config['tag.download.auth.users'] = ozone_user
      ozone_ranger_plugin_config['policy.grantrevoke.auth.users'] = ozone_user

    # hbase_ranger_plugin_config['setup.additional.default.policies'] = "true"
    # hbase_ranger_plugin_config['default-policy.1.name'] = "Service Check User Policy for Hbase"
    # hbase_ranger_plugin_config['default-policy.1.resource.table'] = "ambarismoketest"
    # hbase_ranger_plugin_config['default-policy.1.resource.column-family'] = "*"
    # hbase_ranger_plugin_config['default-policy.1.resource.column'] = "*"
    # hbase_ranger_plugin_config['default-policy.1.policyItem.1.users'] = policy_user
    # hbase_ranger_plugin_config['default-policy.1.policyItem.1.accessTypes'] = "read,write,create"

    # custom_ranger_service_config = generate_ranger_service_config(ranger_plugin_properties)
    # if len(custom_ranger_service_config) > 0:
    #   hbase_ranger_plugin_config.update(custom_ranger_service_config)

    ozone_ranger_plugin_repo = {
      'isEnabled': 'true',
      'configs': ozone_ranger_plugin_config,
      'description': 'ozone repo',
      'name': repo_name,
      'type': 'ozone'
    }

    ranger_ozone_principal = None
    ranger_ozone_keytab = None
    if stack_supports_ranger_kerberos and security_enabled and 'ozone-manager' in component_directory.lower():
      ranger_ozone_principal = om_jaas_princ
      ranger_ozone_keytab = om_keytab_path
    

    xa_audit_db_is_enabled = False
    if xml_configurations_supported and stack_supports_ranger_audit_db:
      xa_audit_db_is_enabled = config['configurations']['ranger-ozone-audit']['xasecure.audit.destination.solr']

    xa_audit_hdfs_is_enabled = config['configurations']['ranger-ozone-audit']['xasecure.audit.destination.hdfs'] if xml_configurations_supported else False
    ssl_keystore_password = config['configurations']['ranger-ozone-policymgr-ssl']['xasecure.policymgr.clientssl.keystore.password'] if xml_configurations_supported else None
    ssl_truststore_password = config['configurations']['ranger-ozone-policymgr-ssl']['xasecure.policymgr.clientssl.truststore.password'] if xml_configurations_supported else None
    credential_file = format('/etc/ranger/{repo_name}/cred.jceks')

    # for SQLA explicitly disable audit to DB for Ranger
    if has_ranger_admin and stack_supports_ranger_audit_db and xa_audit_db_flavor.lower() == 'sqla':
      xa_audit_db_is_enabled = False
else:
  Logger.debug("Skipping Ranger as not ranger admin hosts is present")
# need this to capture cluster name from where ranger hbase plugin is enabled
cluster_name = config['clusterName']

  # smoke user ozone ranger policy
ranger_policy_config = {
    "isEnabled": "true",
    "policyType": 0,
    "policyPriority": 0,
    "service": cluster_name + "_ozone",
    "name": "[AMBARI] - Ozone Smoke User Check",
    "resources": {
      "volume": {
        "values": [
          "ambarismokevolume"
        ],
        "isExcludes": "false",
        "isRecursive": "false"
      },
      "bucket": {
        "values": [
          "ambarismokebucket"
        ],
        "isExcludes": "false",
        "isRecursive": "false"
      },
      "key": {
        "values": [
          "*"
        ],
        "isExcludes": "false",
        "isRecursive": "true"
      }
    },
    "policyItems": [
          {
            "accesses": [
              {
                "type": "all",
                "isAllowed": "true"
              },
              {
                "type": "read",
                "isAllowed": "true"
              },
              {
                "type": "write",
                "isAllowed": "true"
              },
              {
                "type": "create",
                "isAllowed": "true"
              },
              {
                "type": "list",
                "isAllowed": "true"
              },
              {
                "type": "delete",
                "isAllowed": "true"
              },
              {
                "type": "read_acl",
                "isAllowed": "true"
              },
              {
                "type": "write_acl",
                "isAllowed": "true"
              }
            ],
            "users": [
              format("{smoke_test_user}")
            ],
            "groups": [],
            "roles": [],
            "conditions": [],
            "delegateAdmin": "true"
          }
        ],
  }

# ranger ozone plugin section end
role_scm = ROLE_NAME_MAP_DAEMON['ozone-scm']
role_om = ROLE_NAME_MAP_DAEMON['ozone-manager']
role_s3g = ROLE_NAME_MAP_DAEMON['ozone-s3g']
role_datanode = ROLE_NAME_MAP_DAEMON['ozone-datanode']
role_httpfs = ROLE_NAME_MAP_DAEMON['ozone-httpfs']
role_recon = ROLE_NAME_MAP_DAEMON['ozone-recon']
ozone_scm_pid_file = format("{pid_dir}/ozone-{ozone_user}-{role_scm}.pid")
ozone_datanode_pid_file = format("{pid_dir}/ozone-{ozone_user}-{role_datanode}.pid")
ozone_manager_pid_file = format("{pid_dir}/ozone-{ozone_user}-{role_om}.pid")
ozone_httpfs_pid_file = format("{pid_dir}/ozone-{ozone_user}-{role_httpfs}.pid")
ozone_recon_pid_file = format("{pid_dir}/ozone-{ozone_user}-{role_recon}.pid")
ozone_s3g_pid_file = format("{pid_dir}/ozone-{ozone_user}-{role_s3g}.pid")
# Ozone HTTPFS Related properties

httpfs_ssl_enabled = True if str(default('/configurations/ozone-httpfs-site/httpfs.ssl.enabled', False)).lower() == "true" else False
ozone_httpfs_hosts  = default("/clusterHostInfo/ozone_httpfs_gateway_hosts", [])
ozone_httpfs_properties = {}
if len(ozone_httpfs_hosts) > 0 :
  ozone_httpfs_properties = dict(config['configurations']['ozone-httpfs-site'])
  ozone_httpfs_env_sh_template = config['configurations']['ozone-httpfs-env']['content']

  ozone_httpfs_hadoop_auth_principal_name = default("/configurations/ozone-httpfs-site/httpfs.hadoop.authentication.kerberos.principal", None)
  if ozone_httpfs_hadoop_auth_principal_name is not None:
    ozone_httpfs_hadoop_auth_principal_name = ozone_httpfs_hadoop_auth_principal_name.replace('_HOST',_hostname_lowercase)

  ozone_httpfs_principal_name = default("/configurations/ozone-httpfs-site/hadoop.http.authentication.kerberos.principal", None)
  if ozone_httpfs_principal_name is not None:
    ozone_httpfs_principal_name = ozone_httpfs_principal_name.replace('_HOST',_hostname_lowercase)
  
  # apply principal name
  ozone_httpfs_properties['httpfs.hadoop.authentication.kerberos.principal'] = ozone_httpfs_hadoop_auth_principal_name
  ozone_httpfs_properties['hadoop.http.authentication.kerberos.principal'] = ozone_httpfs_principal_name

ozone_httpfs_max_threads = default("/configurations/ozone-httpfs-env/httpfs_max_threads", 1000)
ozone_httpfs_max_header_size = default("/configurations/ozone-httpfs-env/httpfs_max_header_size", 65536)
ozone_httpfs_http_port = default("/configurations/ozone-httpfs-env/httpfs_http_port", 14000)

# Ozone HTTPFS Gateway TLS related params #
ozone_httpfs_credential_file_path = config['configurations']['ssl-server-httpfs']['hadoop.security.credential.provider.path']
ozone_httpfs_tls_ssl_keystore_password = config['configurations']['ssl-server-httpfs']['ssl.server.keystore.password']
ozone_httpfs_tls_ssl_key_password = config['configurations']['ssl-server-httpfs']['ssl.server.keystore.keypassword']
ozone_httpfs_tls_ssl_client_truststore_password = config['configurations']['ssl-client-httpfs']['ssl.client.truststore.password']

httpfs_ssl_server_dict = {}
httpfs_ssl_client_dict = {}
if httpfs_ssl_enabled:
  Logger.debug("Preparing Ozone HTTPFS Gateway SSL/TLS Dictionnaries")
  ozone_httpfs_ssl_keystore_path = config['configurations']['ssl-server-httpfs']['ssl.server.keystore.location']
  ozone_httpfs_ssl_keystore_password = config['configurations']['ssl-server-httpfs']['ssl.server.keystore.password']
  for prop in config['configurations']['ssl-server-httpfs'].keys():
    if prop not in ssl_server_props_ignore:
      httpfs_ssl_server_dict[prop] = config['configurations']['ssl-server-httpfs'][prop]
    else:
      Logger.debug("Skipping property {prop} when computing httpfs_ssl_server")
  for prop in config['configurations']['ssl-client-httpfs'].keys():
    if prop not in ssl_client_props_ignore:
      httpfs_ssl_client_dict[prop] = config['configurations']['ssl-client-httpfs'][prop]
    else:
      Logger.debug("Skipping property {prop} when computing httpfs_ssl_client")
