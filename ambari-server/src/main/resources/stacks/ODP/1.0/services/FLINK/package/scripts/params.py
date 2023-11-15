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

import socket
import status_params
from urllib.parse import urlparse

from ambari_commons.constants import AMBARI_SUDO_BINARY

from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions import conf_select, stack_select
from resource_management.libraries.functions.version import format_stack_version, get_major_version
from resource_management.libraries.functions.copy_tarball import get_sysprep_skip_copy_tarballs_hdfs
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions import get_kinit_path
from resource_management.libraries.functions.get_not_managed_resources import get_not_managed_resources
from resource_management.libraries.resources.hdfs_resource import HdfsResource
from resource_management.libraries.script.script import Script
from ambari_commons.constants import AMBARI_SUDO_BINARY

sudo = AMBARI_SUDO_BINARY

# a map of the Ambari role to the component name
# for use with <stack-root>/current/<component>
SERVER_ROLE_DIRECTORY_MAP = {
  'FLINK_HISTORYSERVER' : 'flink-historyserver',
  'FLINK_CLIENT' : 'flink-client'

}
component_directory = Script.get_component_from_role(SERVER_ROLE_DIRECTORY_MAP, "FLINK_CLIENT")

config = Script.get_config()
tmp_dir = Script.get_tmp_dir()
sudo = AMBARI_SUDO_BINARY

hostname = socket.getfqdn().lower()
stack_name = status_params.stack_name
stack_root = Script.get_stack_root()
stack_version_unformatted = config['clusterLevelParams']['stack_version']
stack_version_formatted = format_stack_version(stack_version_unformatted)
major_stack_version = get_major_version(stack_version_formatted)
security_enabled = config['configurations']['cluster-env']['security_enabled']

sysprep_skip_copy_tarballs_hdfs = get_sysprep_skip_copy_tarballs_hdfs()

# New Cluster Stack Version that is defined during the RESTART of a Stack Upgrade
version = default("/commandParams/version", None)

flink_conf = '/etc/flink/conf'
flink_historyserver_conf = format("{flink_conf}/history-server.conf")
flink_restserver_conf = format("{flink_conf}/rester-server.conf")

hadoop_conf_dir = conf_select.get_hadoop_conf_dir()
hadoop_bin_dir = stack_select.get_hadoop_dir("bin")

if stack_version_formatted and check_stack_feature(StackFeature.ROLLING_UPGRADE, stack_version_formatted):
  hadoop_home = stack_select.get_hadoop_dir("home")
  flink_conf = format("{stack_root}/current/{component_directory}/conf")
  flink_log_dir = config['configurations']['flink-env']['flink_log_dir']
  flink_pid_dir = status_params.flink_pid_dir
  flink_home = format("{stack_root}/current/{component_directory}")

flink_historyserver_daemon_memory = config['configurations']['flink-env']['flink_historyserver_memory']
flink_history_server_properties = config['configurations']['flink-conf'].copy()
flink_history_server_properties['env.log.dir'] = config['configurations']['flink-env']['flink_log_dir']
flink_client_properties = config['configurations']['flink-conf'].copy()

# custom historyserver properties
flink_history_server_properties['env.pid.dir'] = status_params.flink_pid_dir

toPop = [
  'security.ssl.historyserver.truststore-password',
  'security.ssl.historyserver.keystore-password',
  'security.ssl.historyserver.key-password',
  'security.ssl.historyserver.keystore',
  'security.ssl.historyserver.truststore',
  'security.ssl.rest.truststore-password',
  'security.ssl.rest.keystore-password',
  'security.ssl.rest.key-password',
  'security.ssl.rest.keystore',
  'security.ssl.rest.truststore',
  'high-availability',
  'high-availability.storageDir',
  'high-availability.zookeeper.client.acl',
  'high-availability.zookeeper.quorum',
  'fs.default-scheme'
  ]

if security_enabled:
  toPop.append('security.kerberos.login.principal')
  toPop.append('security.kerberos.login.keytab')
  flink_history_kerberos_keytab =  config['configurations']['flink-conf']['security.kerberos.login.keytab']
  flink_history_kerberos_principal =  config['configurations']['flink-conf']['security.kerberos.login.principal']

flink_conf_file = flink_conf + "/flink-conf.yaml"
java_home = config['ambariLevelParams']['java_home']

hdfs_user = config['configurations']['hadoop-env']['hdfs_user']
flink_user = config['configurations']['flink-env']['flink_user']
hdfs_principal_name = config['configurations']['hadoop-env']['hdfs_principal_name']
hdfs_user_keytab = config['configurations']['hadoop-env']['hdfs_user_keytab']
user_group = config['configurations']['cluster-env']['user_group']

flink_user = status_params.flink_user
flink_group = status_params.flink_group
user_group = status_params.user_group
flink_hdfs_user_dir = format("/user/{flink_user}")
flink_history_dir = default('/configurations/flink-conf/historyserver.archive.fs.dir', "hdfs:///apps/odp/flink/completed-jobs/")


flink_history_server_pid_file = status_params.flink_history_server_pid_file

flink_history_server_start = format("{flink_home}/bin/historyserver.sh")
flink_history_server_stop = format("{flink_home}/bin/historyserver.sh")


# remove uneeded client configuration
for prop in toPop:
  if flink_client_properties[prop]:
    flink_client_properties.pop(prop)

flink_hadoop_lib_native = format("{stack_root}/current/hadoop-client/lib/native:{stack_root}/current/hadoop-client/lib/native/Linux-amd64-64")

flink_logback_hs_content = config['configurations']['flink-log4j-historyserver']['content']
flink_logback_rest_content = config['configurations']['flink-logback-rest']['content']

run_example_cmd = format("{flink_home}/bin/flink")
flink_smoke_example = format("{flink_home}/examples/batch/WordCount.jar")
flink_service_check_cmd = format(
  "{run_example_cmd} run-application -t yarn-application  {flink_smoke_example}")

#flink_jobhistoryserver_hosts = default("/clusterHostInfo/spark2_jobhistoryserver_hosts", [])
flink_jobhistoryserver_hosts = default("/clusterHostInfo/flink_jobhistoryserver_hosts", [])

has_historyserver = False
if len(flink_jobhistoryserver_hosts) > 0:
  has_historyserver = True
  flink_history_server_host = flink_jobhistoryserver_hosts[0]
else:
  flink_history_server_host = "localhost"

flink_client_properties['historyserver.web.address'] = flink_history_server_host
# convert value to mega - bytes
flink_client_properties['taskmanager.memory.process.size'] =  config['configurations']['flink-conf']['taskmanager.memory.process.size']+'m'
flink_client_properties['jobmanager.memory.process.size'] =  config['configurations']['flink-conf']['jobmanager.memory.process.size']+'m'
# flink-conf params
flink_historyserver_ssl_enabled = default("configurations/flink-conf/historyserver.web.ssl.enabled", False)

flink_historyServer_address = default(flink_history_server_host, "localhost")
flink_historyServer_scheme = "http"
flink_history_ui_port = config['configurations']['flink-conf']['historyserver.web.port']

if flink_historyserver_ssl_enabled:
# TODO: remove unusedconf  #spark_history_ui_port = str(int(spark_history_ui_port) + 400)
  flink_historyServer_scheme = "https"

flink_log4j_console_content_properties = config['configurations']['flink-log4j-console.properties']['content']
flink_log4j_content_properties = config['configurations']['flink-log4j']['content']
flink_log4j_historyserver_content_properties = config['configurations']['flink-log4j-historyserver']['content']
flink_root_logger = config['configurations']['flink-env']['flink_root_logger']
flink_log4j_size = config['configurations']['flink-env']['flink_log4j_size']

prefix = format("{flink_group}-{flink_user}-historyserver-{hostname}")
flink_historyserver_log4j_file_name = format("{flink_group}-{flink_user}-historyserver-{hostname}.log")
flink_historyserver_log_prefix = prefix

log4j_file_name = 'flink.log'

hadoop_conf_dir = conf_select.get_hadoop_conf_dir()
hbase_conf_dir =  default('configurations/flink-env/flink.hbase.conf.dir','/etc/hbase/conf')
hadoop_home = format('{stack_root}/current/hadoop-client')

# TODO: remove unusedconf flink_env_sh = config['configurations']['flink-env']['content']


kinit_path_local = get_kinit_path(default('/configurations/kerberos-env/executable_search_paths', None))
flink_service_kerberos_keytab =  config['configurations']['flink-conf']['security.kerberos.login.keytab']
flink_server_kerberos_principal =  config['configurations']['flink-conf']['security.kerberos.login.principal']
smoke_user = config['configurations']['cluster-env']['smokeuser']
smoke_user_keytab = config['configurations']['cluster-env']['smokeuser_keytab']
smokeuser_principal =  config['configurations']['cluster-env']['smokeuser_principal_name']


default_fs = config['configurations']['core-site']['fs.defaultFS']
hdfs_site = config['configurations']['hdfs-site']
hdfs_resource_ignore_file = "/var/lib/ambari-agent/data/.hdfs_resource_ignore"

dfs_type = default("/clusterLevelParams/dfs_type", "")

is_webhdfs_enabled = hdfs_site['dfs.webhdfs.enabled']


import functools
#create partial functions with common arguments for every HdfsResource call
#to create/delete hdfs directory/file/copyfromlocal we need to call params.HdfsResource in code
HdfsResource = functools.partial(
  HdfsResource,
  user=hdfs_user,
  hdfs_resource_ignore_file = hdfs_resource_ignore_file,
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

log4j_file_name = ''