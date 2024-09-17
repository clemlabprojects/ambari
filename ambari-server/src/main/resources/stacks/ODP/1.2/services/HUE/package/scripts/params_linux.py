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

Ambari Agent

"""
import os
import status_params

from resource_management.core.logger import Logger

import ambari_simplejson as json # simplejson is much faster comparing to Python 2.6 json module and has the same functions set.
from resource_management.libraries.functions import format
from resource_management.libraries.functions.version import format_stack_version
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.get_port_from_url import get_port_from_url
from resource_management.libraries.functions.get_stack_version import get_stack_version
from resource_management.libraries.functions import get_kinit_path
from resource_management.libraries.script.script import Script
from status_params import *
from resource_management.libraries.resources.hdfs_resource import HdfsResource
from resource_management.libraries.functions import stack_select, conf_select
from resource_management.libraries.functions.get_not_managed_resources import get_not_managed_resources
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.stack_features import get_stack_feature_version
from resource_management.libraries.functions import upgrade_summary
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions import is_empty
from resource_management.libraries.functions.setup_ranger_plugin_xml import get_audit_configs, generate_ranger_service_config

def getDBEngine(databaseType):
  driverDict = {
    "MYSQL": "mysql",
    "ORACLE": "derby",
    "POSTGRESQL": "postgres",
    "POSTGRES": "postgres"
  }
  return driverDict.get(databaseType.upper())

# def getDBEngine(databaseType):
#   driverDict = {
#     "MYSQL": "mysql",
#     "ORACLE": "derby",
#     "POSTGRES": "postgres"
#     # "EXISTING MYSQL / MARIADB DATABASE": "mysql",
#     # "EXISTING POSTGRESQL DATABASE": "postgres",
#     # "EXISTING ORACLE DATABASE": "oracle",
#   }
#   return driverDict.get(databaseType.upper())
# server configurations
config = Script.get_config()
stack_root = Script.get_stack_root()

tmp_dir = Script.get_tmp_dir()
stack_name = status_params.stack_name
upgrade_direction = default("/commandParams/upgrade_direction", None)
version = default("/commandParams/version", None)
# E.g., 2.3.2.0
version_formatted = format_stack_version(version)

# E.g., 2.3
stack_version_unformatted = config['clusterLevelParams']['stack_version']
stack_version_formatted = format_stack_version(stack_version_unformatted)

# get the correct version to use for checking stack features
version_for_stack_feature_checks = get_stack_feature_version(config)

stack_supports_ranger_kerberos = check_stack_feature(StackFeature.RANGER_KERBEROS_SUPPORT, version_for_stack_feature_checks)
stack_supports_ranger_audit_db = check_stack_feature(StackFeature.RANGER_AUDIT_DB_SUPPORT, version_for_stack_feature_checks)
stack_supports_core_site_for_ranger_plugin = check_stack_feature(StackFeature.CORE_SITE_FOR_RANGER_PLUGINS_SUPPORT, version_for_stack_feature_checks)

# This is the version whose state is CURRENT. During an RU, this is the source version.
# DO NOT format it since we need the build number too.
upgrade_from_version = upgrade_summary.get_source_version()

stack_name_lower = stack_name.lower()
hue_home_dir = format('/usr/{stack_name_lower}/{version}/hue')
hue_user = default("/configurations/hue-env/hue_user", "hue")

# server configurations
hue_logs_dir = default("/configurations/hue-env/hue_log_dir", "/var/log/hue")

# default parameters
hue_conf_dir = '/etc/hue/conf'

hue_pid_dir = default("/configurations/hue-env/hue_pid_dir", "/var/run/hue")
hue_pid_file = format('{hue_pid_dir}/hue.pid')
hue_group = default("/configurations/hue-env/hue_group", "hue")
mode = 0o644

namenode_hosts = default("/clusterHostInfo/namenode_hosts", None)
has_namenode = bool(namenode_hosts)

dfs_ha_enabled = False
dfs_ha_nameservices = default('/configurations/hdfs-site/dfs.internal.nameservices', None)
if dfs_ha_nameservices is None:
  dfs_ha_nameservices = default('/configurations/hdfs-site/dfs.nameservices', None)

if dfs_ha_nameservices is not None:
  dfs_ha_nameservices = dfs_ha_nameservices.split(',')[0]

dfs_ha_namenode_ids = default(format("/configurations/hdfs-site/dfs.ha.namenodes.{dfs_ha_nameservices}"), None)

namenode_rpc = None

dfs_type = default("/clusterLevelParams/dfs_type", "").lower()

namenode_http_port = "9870"
namenode_https_port = "9871"
namenode_rpc_port = "8020"
namenode_address = ""

if has_namenode:
  if 'dfs.namenode.http-address' in config['configurations']['hdfs-site']:
    namenode_http_port = get_port_from_url(config['configurations']['hdfs-site']['dfs.namenode.http-address'])
  if 'dfs.namenode.https-address' in config['configurations']['hdfs-site']:
    namenode_https_port = get_port_from_url(config['configurations']['hdfs-site']['dfs.namenode.https-address'])
  if dfs_ha_enabled and namenode_rpc:
    namenode_rpc_port = get_port_from_url(namenode_rpc)
  else:
    if 'dfs.namenode.rpc-address' in config['configurations']['hdfs-site']:
      namenode_rpc_port = get_port_from_url(config['configurations']['hdfs-site']['dfs.namenode.rpc-address'])

  namenode_address = format("{dfs_type}://{namenode_hosts[0]}:{namenode_rpc_port}")

if dfs_ha_namenode_ids:
  dfs_ha_namemodes_ids_list = dfs_ha_namenode_ids.split(",")
  dfs_ha_namenode_ids_array_len = len(dfs_ha_namemodes_ids_list)
  if dfs_ha_namenode_ids_array_len > 1:
    dfs_ha_enabled = True


if dfs_ha_enabled:
  for nn_id in dfs_ha_namemodes_ids_list:
    nn_host = config['configurations']['hdfs-site'][format('dfs.namenode.rpc-address.{dfs_ha_nameservices}.{nn_id}')]
    if hostname.lower() in nn_host.lower():
      namenode_id = nn_id
      namenode_rpc = nn_host
    # With HA enabled namenode_address is recomputed
  namenode_address = format('{dfs_type}://{dfs_ha_nameservices}')

namenode_port_map = {}
dfs_http_policy = default('/configurations/hdfs-site/dfs.http.policy', None)

hdfs_https_on = False
hdfs_scheme = 'http'
if dfs_http_policy  !=  None :
   hdfs_https_on = (dfs_http_policy.upper() == 'HTTPS_ONLY')
   hdfs_scheme = 'http' if not hdfs_https_on else 'https'
   hdfs_port = str(namenode_http_port)if not hdfs_https_on else str(namenode_https_port)
   namenode_http_port = hdfs_port

if dfs_ha_enabled:
    for nn_id in dfs_ha_namemodes_ids_list:
        nn_host = config['configurations']['hdfs-site'][format('dfs.namenode.{hdfs_scheme}-address.{dfs_ha_nameservices}.{nn_id}')]
        nn_host_parts = nn_host.split(':')
        namenode_port_map[nn_host_parts[0]] = nn_host_parts[1]

# cluster id
hue_runtime_cluster_id = config['clusterLevelParams']['cluster_name']

def buildUrlElement(protocol, hdfs_host, port, servicePath) :
  # openTag = "<url>"
  # closeTag = "</url>"
  # proto = protocol + "://"
  # newLine = "\n"
  openTag = ""
  closeTag = ""
  proto = protocol + "://"
  newLine = ","
  if hdfs_host is None or port is None:
      return ""
  else:
    return openTag + proto + hdfs_host + ":" + port + servicePath + closeTag + newLine

namenode_host_keys = namenode_port_map.keys();
webhdfs_service_urls = ""
if len(namenode_host_keys) > 0:
    for host in namenode_host_keys:
      webhdfs_service_urls += buildUrlElement(hdfs_scheme, host, namenode_port_map[host], "/webhdfs")
elif has_namenode:
  webhdfs_service_urls = buildUrlElement(hdfs_scheme, namenode_hosts[0], namenode_http_port, "/webhdfs")


httpfs_hosts = default("/clusterHostInfo/httpfs_gateway_hosts", None)
httpfs_port = 14000

if check_stack_feature(StackFeature.HDFS_SUPPORTS_HTTPFS, version_for_stack_feature_checks):
  httpfs_ssl_enabled = default('/configurations/httpfs-site/httpfs.ssl.enabled', False)
  if httpfs_hosts != None:
    httpfs_scheme = 'https' if httpfs_ssl_enabled else 'http'
    httpfs_url = '{0}://{1}:{2}/webhdfs/v1'.format(httpfs_scheme, httpfs_hosts[0], httpfs_port)
    webhdfs_service_urls = httpfs_url

yarn_http_policy = default('/configurations/yarn-site/yarn.http.policy', None )
yarn_https_on = False
yarn_scheme = 'http'
if yarn_http_policy !=  None :
   yarn_https_on = ( yarn_http_policy.upper() == 'HTTPS_ONLY')
   yarn_scheme = 'http' if not yarn_https_on else 'https'

rm_hosts = default("/clusterHostInfo/resourcemanager_hosts", None)
if type(rm_hosts) is list:
  rm_host = rm_hosts[0]
else:
  rm_host = rm_hosts
has_rm = not rm_host == None

rm_port = "8080"
rm_submit_port = "8032"

if has_rm:
  # Need to get Url based on scheme
  # do not support RM on different port (no cluster should be like this ^^) 
  if yarn_https_on:
    rm_port = get_port_from_url(config['configurations']['yarn-site']['yarn.resourcemanager.webapp.https.address'])
  else:
    rm_port = get_port_from_url(config['configurations']['yarn-site']['yarn.resourcemanager.webapp.address'])

hive_http_port = default('/configurations/hive-site/hive.server2.thrift.http.port', "10001")
hive_http_path = default('/configurations/hive-site/hive.server2.thrift.http.path', "cliservice")
hive_server_hosts = default("/clusterHostInfo/hive_server_hosts", None)
if hive_server_hosts != None:
  if type(hive_server_hosts) is list:
    hive_server_host = hive_server_hosts[0] if len(hive_server_hosts) > 0 else None
  else:
    hive_server_host = hive_server_hosts

templeton_port = default('/configurations/webhcat-site/templeton.port', "50111")
webhcat_server_hosts = default("/clusterHostInfo/webhcat_server_hosts", None)
if type(webhcat_server_hosts) is list:
  webhcat_server_host = webhcat_server_hosts[0]
else:
  webhcat_server_host = webhcat_server_hosts

hive_scheme = 'http'
webhcat_scheme = 'http'

hbase_master_scheme = 'http'
hbase_master_ui_port = default('/configurations/hbase-site/hbase.master.info.port', "16010")
hbase_master_rs_port = default('/configurations/hbase-site/hbase.regionserver.thrift.port', "9090")
hbase_master_port = default('/configurations/hbase-site/hbase.rest.port', "8080")
hbase_master_hosts = default("/clusterHostInfo/hbase_master_hosts", None)
if type(hbase_master_hosts) is list:
  hbase_master_host = hbase_master_hosts[0]
else:
  hbase_master_host = hbase_master_hosts

hbase_thrift_stack_enabled = check_stack_feature(StackFeature.HBASE_SUPPORTS_THRIFT, version_for_stack_feature_checks)
has_hbase = False
if hbase_thrift_stack_enabled:
  hbase_thrift_hosts = default("/clusterHostInfo/hbase_thriftserver_hosts", None)
  if hbase_thrift_hosts != None:
    has_hbase = True

#
# Oozie
#
oozie_server_hosts = default("/clusterHostInfo/oozie_server_hosts", None)
oozie_server_port = "11000"
has_oozie = False
if oozie_server_hosts != None:
  has_oozie = True
  oozie_https_port = None
  oozie_scheme = 'http' if config['configurations']['oozie-site']['oozie.base.url'].startswith('http://') else 'https'
  if type(oozie_server_hosts) is list:
    oozie_server_host = oozie_server_hosts[0]
  else:
    oozie_server_host = oozie_server_hosts

if has_oozie:
  oozie_server_port = get_port_from_url(config['configurations']['oozie-site']['oozie.base.url'])
  oozie_https_port = default("/configurations/oozie-site/oozie.https.port", None)

  if oozie_https_port is not None:
    oozie_server_port = oozie_https_port

#
# Falcon
#
falcon_server_hosts = default("/clusterHostInfo/falcon_server_hosts", None)
if type(falcon_server_hosts) is list:
  falcon_server_host = falcon_server_hosts[0]
else:
  falcon_server_host = falcon_server_hosts

falcon_scheme = 'http'
has_falcon = not falcon_server_host == None
falcon_server_port = "15000"

if has_falcon:
  falcon_server_port = config['configurations']['falcon-env']['falcon_port']

#
# Solr
#
solr_scheme='http'
solr_server_hosts  = default("/clusterHostInfo/solr_hosts", None)
if type(solr_server_hosts ) is list:
  solr_host = solr_server_hosts[0]
else:
  solr_host = solr_server_hosts
solr_port=default("/configuration/solr/solr-env/solr_port","8886")

#
# Spark
#
spark_scheme = 'http'
spark_historyserver_hosts = default("/clusterHostInfo/spark_jobhistoryserver_hosts", None)
if type(spark_historyserver_hosts) is list:
  spark_historyserver_host = spark_historyserver_hosts[0]
else:
  spark_historyserver_host = spark_historyserver_hosts
spark_historyserver_ui_port = default("/configurations/spark-defaults/spark.history.ui.port", "18080")

#
# JobHistory mapreduce
#
mr_scheme='http'
mr_historyserver_address = default("/configurations/mapred-site/mapreduce.jobhistory.webapp.address", None)

#
# Yarn nodemanager
#
nodeui_scheme= 'http'
nodeui_port = "8042"
nm_hosts = default("/clusterHostInfo/nodemanager_hosts", None)
if type(nm_hosts) is list:
  nm_host = nm_hosts[0]
else:
  nm_host = nm_hosts

has_yarn = default("/configurations/yarn-site", None )
if has_yarn and 'yarn.nodemanager.webapp.address' in config['configurations']['yarn-site']:
  nodeui_port = get_port_from_url(config['configurations']['yarn-site']['yarn.nodemanager.webapp.address'])


#
# Spark Thrift UI
#
spark_thriftserver_scheme = 'http'
spark_thriftserver_ui_port = 4039
spark_thriftserver_hosts = default("/clusterHostInfo/spark_thriftserver_hosts", None)
if type(spark_thriftserver_hosts) is list:
  spark_thriftserver_host = spark_thriftserver_hosts[0]
else:
  spark_thriftserver_host = spark_thriftserver_hosts


# Atlas UI
#

atlas_metadata_hosts = default("/clusterHostInfo/atlas_server_hosts", None)
if atlas_metadata_hosts != None:
  atlas_tls_enabled = config['configurations']['application-properties']['atlas.enableTLS']
  if atlas_tls_enabled:
    atlas_scheme = "https"
  else:
    atlas_scheme = "http"
  atlas_ui_port = config['configurations']['application-properties'][format('atlas.server.{atlas_scheme}.port')]  


# Hue managed properties
hue_managed_pid_symlink= format('{stack_root}/current/hue-server/pids')

#Hue log4j
hue_gateway_log_maxfilesize = default('/configurations/gateway-log4j/hue_gateway_log_maxfilesize',256)
hue_gateway_log_maxbackupindex = default('/configurations/gateway-log4j/hue_gateway_log_maxbackupindex',20)

# server configurations
hue_server_hosts = default("/clusterHostInfo/hue_server_hosts", [])
hue_host_port = config['configurations']['hue-ini-conf']['hue_http_port']
java_home = config['ambariLevelParams']['java_home']
security_enabled = config['configurations']['cluster-env']['security_enabled']
smokeuser = config['configurations']['cluster-env']['smokeuser']
smokeuser_principal = config['configurations']['cluster-env']['smokeuser_principal_name']
smoke_user_keytab = config['configurations']['cluster-env']['smokeuser_keytab']
kinit_path_local = get_kinit_path(default('/configurations/kerberos-env/executable_search_paths', None))
_hostname_lowercase = config['agentLevelParams']['hostname'].lower()
if security_enabled:
  hue_keytab_path = config['configurations']['hue-ini-conf']['hue_keytab']
  hue_principal_name = config['configurations']['hue-env']['hue_principal'].replace('_HOST',_hostname_lowercase)

# for curl command in ranger plugin to get db connector
jdk_location = config['ambariLevelParams']['jdk_location']

if has_namenode:
  hdfs_user = config['configurations']['hadoop-env']['hdfs_user'] if has_namenode else None
  hdfs_user_keytab = config['configurations']['hadoop-env']['hdfs_user_keytab'] if has_namenode else None
  hdfs_principal_name = config['configurations']['hadoop-env']['hdfs_principal_name'] if has_namenode else None
  hdfs_site = config['configurations']['hdfs-site'] if has_namenode else None
  default_fs = config['configurations']['core-site']['fs.defaultFS'] if has_namenode else None
  hadoop_bin_dir = stack_select.get_hadoop_dir("bin") if has_namenode else None
  hadoop_conf_dir = conf_select.get_hadoop_conf_dir() if has_namenode else None

  import functools
  #create partial functions with common arguments for every HdfsResource call
  #to create/delete hdfs directory/file/copyfromlocal we need to call params.HdfsResource in code
  HdfsResource = functools.partial(
    HdfsResource,
    user=hdfs_user,
    hdfs_resource_ignore_file = "/var/lib/ambari-agent/data/.hdfs_resource_ignore",
    security_enabled = security_enabled,
    keytab = hdfs_user_keytab,
    kinit_path_local = kinit_path_local,
    hadoop_bin_dir = hadoop_bin_dir,
    hadoop_conf_dir = hadoop_conf_dir,
    principal_name = hdfs_principal_name,
    hdfs_site = hdfs_site,
    default_fs = default_fs,
    immutable_paths = get_not_managed_resources(),
    dfs_type = dfs_type
  )

if "topology" in config['configurations']:
  if 'ws://' in config['configurations']['topology']['content'] or 'wss://' in config['configurations']['topology']['content']:
    websocket_support = "true"

mount_table_xml_inclusion_file_full_path = None
mount_table_content = None
if 'viewfs-mount-table' in config['configurations']:
  xml_inclusion_file_name = 'viewfs-mount-table.xml'
  mount_table = config['configurations']['viewfs-mount-table']

  if 'content' in mount_table and mount_table['content'].strip():
    mount_table_xml_inclusion_file_full_path = os.path.join(hue_conf_dir, xml_inclusion_file_name)
    mount_table_content = mount_table['content']


# Hue Init Variable configuration generation
## [hue-ini] - Desktop Settings
hue_use_x_forwarded_host = default("/configurations/hue-ini-conf/hue_use_x_forwarded_host", "hue")
hue_app_blacklist = default("/configurations/hue-ini-conf/hue_app_blacklist", "spark,zookeeper,impala,search,pig,sqoop,security")
hue_ssl_enabled = default("/configurations/hue-env/hue_ssl_enabled", False)
if hue_ssl_enabled:
  hue_ssl_certificate = default("/configurations/hue-ini-conf/hue_ssl_certificate", "/etc/security/serverKeys/certificate.pem")
  hue_ssl_private_key = default("/configurations/hue-ini-conf/hue_ssl_private_key", "/etc/security/serverKeys/key.pem")
  hue_ssl_cert_chain = default("/configurations/hue-ini-conf/hue_ssl_cert_chain", "/etc/security/serverKeys/ca.cert.pem")
hue_http_port = default("/configurations/hue-ini-conf/hue_http_port", 8888)
hue_timezone = default("/configurations/hue-ini-conf/hue_timezone", "America/Los_Angeles")
hue_django_debug_mode = default("/configurations/hue-ini-conf/hue_django_debug_mode", False)
hue_http_500_debug_mode = default("/configurations/hue-ini-conf/hue_http_500_debug_mode", False)
hue_cherrypy_server_threads = default("/configurations/hue-ini-conf/hue_cherrypy_server_threads", 50)
hue_default_site_encoding = default("/configurations/hue-ini-conf/hue_default_site_encoding", "utf")
hue_default_collect_usage = default("/configurations/hue-ini-conf/hue_default_collect_usage", True)
hue_runtime_default_user = hue_user
if has_namenode:
  hue_runtime_default_hdfs_superuser = hdfs_user

hue_runtime_secret_key_script = format("{hue_home_dir}/bin/encrypt.sh decrypt cookie-secret-password")
hue_cookie_password = config['configurations']['hue-ini-conf']['hue_bind_dn']
loadbalancer = default("/configurations/hue-ini-conf/hue_runtime_load_balancer", None)
if loadbalancer != None:
  hue_runtime_load_balancer = loadbalancer

hue_metrics_file = default("/configurations/hue-ini-conf/hue_default_collect_usage", format("{hue_logs_dir}/metrics.log"))
hue_idle_session_timeout = config['configurations']['hue-ini-conf']['hue_idle_session_timeout']
hue_user_augmentor = config['configurations']['hue-ini-conf']['hue_user_augmentor']
hue_backend = config['configurations']['hue-ini-conf']['hue_backend']

## [hue-ini] - LDAP/AD settings
ldap_auth = (('desktop.auth.backend.LdapBackend' in hue_backend) or (hue_backend  == 'desktop.auth.backend.AllowAllBackend'))
if ldap_auth:
  hue_ldap_url = config['configurations']['hue-ini-conf']['hue_ldap_url']
  hue_search_bind_authentication = config['configurations']['hue-ini-conf']['hue_search_bind_authentication']
  hue_nt_domain = config['configurations']['hue-ini-conf']['hue_nt_domain']
  hue_create_users_on_login = config['configurations']['hue-ini-conf']['hue_create_users_on_login']
  hue_base_dn = config['configurations']['hue-ini-conf']['hue_base_dn']
  hue_bind_dn = config['configurations']['hue-ini-conf']['hue_bind_dn']
  hue_test_ldap_group = config['configurations']['hue-ini-conf']['hue_test_ldap_group']
  hue_bind_password =  config['configurations']['hue-ini-conf']['hue_bind_password']
  hue_runtime_bind_password_script = format("{hue_home_dir}/bin/encrypt.sh decrypt ldap-secret-password")

## [hue-ini] - Database settings
hue_db_engine = getDBEngine(config['configurations']['hue-env']['hue_database'])
if hue_db_engine != 'derby':
  hue_db_host = config['configurations']['hue-ini-conf']['hue_db_host']
  hue_db_port = config['configurations']['hue-ini-conf']['hue_db_port']
  hue_db_username = config['configurations']['hue-ini-conf']['hue_db_username']
  hue_db_name = config['configurations']['hue-ini-conf']['hue_db_name']
  hue_db_password = config['configurations']['hue-ini-conf']['hue_db_password']
  hue_runtime_database_password_script = format("{hue_home_dir}/bin/encrypt.sh decrypt db-secret-password")
# hue_generated_proxy_hosts =
## Hue Keytab Renewer
if security_enabled:
  hue_kerberos_ccache_path = config['configurations']['hue-ini-conf']['hue_kerberos_ccache_path']
  hue_keytab = hue_keytab_path
  hue_principal = hue_principal_name

## Hue Atlas Settings
if atlas_metadata_hosts != None:
  catalog_urls = []
  for atlas_host in atlas_metadata_hosts:
    catalog_urls.append(format('{atlas_scheme}://{atlas_host}:{atlas_ui_port}/'))
  hue_generated_urls = ','.join(catalog_urls)
  hue_catalog_kerberos_enabled = security_enabled

kerberos_enabled = security_enabled
## Hue HDFS Settings
if has_namenode:
  hue_fs_default = default_fs
  hue_hdfs_kerberos_enabled = security_enabled

hue_webhdfs_url = webhdfs_service_urls

## Hue YARN HA settings

if has_rm:
  yarn_ha_enabled = type(rm_hosts) is list and len(rm_hosts) > 1
  # configures the [[yarn]][[[default]]]
  hue_resourcemanager_host = rm_hosts[0]
  hue_resourcemanager_api_url = format('{yarn_scheme}://{hue_resourcemanager_host}:{rm_port}')
  hue_proxy_api_url = format('{yarn_scheme}://{hue_resourcemanager_host}:{rm_port}')
  hue_resourcemanager_port = rm_submit_port
  hue_history_server_api_url = mr_historyserver_address
  hue_yarn_kerberos_enabled = security_enabled
  hue_submit_to = config['configurations']['hue-env']['hue_submit_to']
  # configures the [[yarn]][[[ha]]]
  if yarn_ha_enabled:
    hue_yarnha_resourcemanager_host = rm_hosts[1]
    hue_yarnha_resourcemanager_api_url = format('{yarn_scheme}://{hue_yarnha_resourcemanager_host}:{rm_port}')
    hue_yarnha_proxy_api_url = format('{yarn_scheme}://{hue_yarnha_resourcemanager_host}:{rm_port}')
    hue_yarnha_resourcemanager_port = rm_submit_port
    hue_yarnha_history_server_api_url = mr_historyserver_address
    hue_yarnha_yarn_kerberos_enabled = security_enabled
    hue_yarnha_submit_to = config['configurations']['hue-env']['hue_submit_to']

## Hue Hive Settings
if hive_server_hosts != None:
  has_hive_hs2 = len(hive_server_hosts) > 0
  if has_hive_hs2:
    hue_beeswax_hive_server_host = hive_server_hosts[0]
    hue_beeswax_hive_server_port = hive_http_port
    has_hive_hs2_ssl = default("/configurations/hive-site/hive.server2.use.SSL", False)
    hue_beeswax_server_conn_timeout = config['configurations']['hue-ini-conf']['hue_beeswax_server_conn_timeout']
    if has_hive_hs2_ssl:
      hue_ssl_validate = config['configurations']['hue-ini-conf']['hue_ssl_validate']
else:
  has_hive_hs2 = False

## Oozie settings
if has_oozie:
  hue_remote_data_dir = config['configurations']['hue-ini-conf']['hue_remote_data_dir']
  hue_oozie_url = format('{oozie_scheme}://{oozie_server_host}:{oozie_server_port}/oozie')
  hue_oozie_kerberos_enabled = security_enabled

if has_hbase:
  hbase_thrift_http_port = default("/configurations/hbase-site/hbase.thrift.port", 9090)
  hbase_thrift_binary_port = default("/configurations/hbase-site/hbase.thrift.http.port", 9095)
  hbase_thrift_http_enabled = default("/configurations/hbase-site/'hbase.regionserver.thrift.http", 'false')
  hbase_thrift_port = hbase_thrift_binary_port if hbase_thrift_http_enabled.lower() == 'false' else hbase_thrift_http_port
  if len(hbase_thrift_hosts) > 1:
    hue_hbase_clusters = ','.join('(HBase|' + item +':'+ str(hbase_thrift_port) + ')' for item in hbase_thrift_hosts) 
  else:
    hbase_thrift_host = hbase_thrift_hosts[0]
    hue_hbase_clusters = '(HBase|' + hbase_thrift_host +':'+ str(hbase_thrift_port) + ')'
else:
  hue_app_blacklist += ",hbase"

## Zookeeper Settings
zookeeper_hosts_list = default("/clusterHostInfo/zookeeper_server_hosts", None)
has_zookeeper = zookeeper_hosts_list != None
if has_zookeeper:
  zookeeper_hosts_list.sort()
  # get comma separated list of zookeeper hosts from clusterHostInfo
  zookeeper_hosts = ",".join(zookeeper_hosts_list)

  zookeeper_port = default('/configurations/zoo.cfg/clientPort', None)
  # get comma separated list of zookeeper hosts from clusterHostInfo
  index = 0
  zookeeper_quorum = ""
  for host in config['clusterHostInfo']['zookeeper_server_hosts']:
    zookeeper_quorum += host + ":" + str(zookeeper_port)
    index += 1
    if index < len(config['clusterHostInfo']['zookeeper_server_hosts']):
      zookeeper_quorum += ","

  hue_zookeeper_hosts = zookeeper_quorum


hue_ini_content = config['configurations']['hue-ini-template']['content']
hue_log_redaction_file = format('{hue_conf_dir}/redaction-rules.json')
hue_runtime_http_host = _hostname_lowercase
hue_admin_username =  config['configurations']['hue-env']['hue_admin_username']
hue_admin_password =  config['configurations']['hue-env']['hue_admin_password']