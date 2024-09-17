#!/usr/bin/env python3

'''
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
'''
import socket
import tarfile
import os
from contextlib import closing

from resource_management.libraries.script.script import Script
from resource_management.libraries.resources.hdfs_resource import HdfsResource
from resource_management.libraries.functions.copy_tarball import copy_to_hdfs, get_tarball_paths
from resource_management.libraries.functions import format
from resource_management.core.resources.system import File, Execute
from resource_management.libraries.functions.version import format_stack_version
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.show_logs import show_logs
from resource_management.core.shell import as_sudo


def make_tarfile(output_filename, source_dir):
  try:
    os.remove(output_filename)
  except OSError:
    pass
  parent_dir=os.path.dirname(output_filename)
  if not os.path.exists(parent_dir):
    os.makedirs(parent_dir)
  os.chmod(parent_dir, 0o711)
  with closing(tarfile.open(output_filename, "w:gz")) as tar:
    for file in os.listdir(source_dir):
      tar.add(os.path.join(source_dir,file),arcname=file)
  os.chmod(output_filename, 0o644)


def flink_service(name, upgrade_type=None, action=None):
  import params

  if action == 'start':

    effective_version = params.version if upgrade_type is not None else params.stack_version_formatted
    if effective_version:
      effective_version = format_stack_version(effective_version)
    params.HdfsResource(params.flink_hdfs_user_dir,
                       type="directory",
                       action="create_on_execute",
                       owner=params.flink_user,
                       mode=0o775
    )
    params.HdfsResource(None, action="execute")

    if name == 'historyserver' and effective_version:
      
      # create flink history dir
      params.HdfsResource(params.flink_history_dir,
                          type="directory",
                          action="create_on_execute",
                          owner=params.flink_user,
                          group=params.user_group,
                          mode=0o777,
                          recursive_chmod=True
                          )
      params.HdfsResource(None, action="execute")

    if params.security_enabled:
      flink_principal = params.flink_history_kerberos_principal.replace('_HOST', socket.getfqdn().lower())
      flink_kinit_cmd = format("{kinit_path_local} -kt {flink_history_kerberos_keytab} {flink_principal}; ")
      Execute(flink_kinit_cmd, user=params.flink_user)

    if name == 'historyserver':
      env = {'JAVA_HOME': params.java_home, 
        'FLINK_CONF_DIR': format("{params.flink_historyserver_conf}"),
        'FLINK_LOG_PREFIX': format("{params.flink_historyserver_log_prefix}"),
        'HADOOP_CONF_DIR': format("{params.hadoop_conf_dir}"),
        'HADOOP_HOME': format("{params.hadoop_home}"),
        'HBASE_CONF_DIR': format("{params.hbase_conf_dir}") }

      historyserver_no_op_test = as_sudo(["test", "-f", params.flink_history_server_pid_file]) + " && " + as_sudo(["pgrep", "-F", params.flink_history_server_pid_file])
      daemon_cmd_start = format('{params.flink_history_server_start} start historyserver')
      try:
        Execute(daemon_cmd_start,
                user=params.flink_user,
                environment=env,
                not_if=historyserver_no_op_test)
      except:
        show_logs(params.flink_log_dir, user=params.flink_user)
        raise

  elif action == 'stop':
    if name == 'historyserver':
      try:
        env = {'JAVA_HOME': params.java_home, 
        'FLINK_CONF_DIR': format("{params.flink_historyserver_conf}"), 
        'FLINK_LOG_PREFIX': format("{params.flink_historyserver_log_prefix}"),
        'HADOOP_CONF_DIR': format("{params.hadoop_conf_dir}"),
        'HADOOP_HOME': format("{params.hadoop_home}"),
        'HBASE_CONF_DIR': format("{params.hbase_conf_dir}") }
        daemon_cmd_stop = format('{params.flink_history_server_stop} stop historyserver ')
        Execute(format('{daemon_cmd_stop}'),
                user=params.flink_user,
                environment=env
        )
      except:
        show_logs(params.flink_log_dir, user=params.flink_user)
        raise
      File(params.flink_history_server_pid_file,
        action="delete"
      )


