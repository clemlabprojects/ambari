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

# Python Imports
import os
import math
import re
import time

# Ambari Commons & Resource Management Imports
from ambari_commons.constants import UPGRADE_TYPE_ROLLING
from resource_management.core import shell
from resource_management.core import utils
from resource_management.core.exceptions import ComponentIsNotRunning, Fail
from resource_management.core.logger import Logger
from resource_management.core.resources.system import File, Execute
from resource_management.core.shell import as_user, quote_bash_args
from resource_management.libraries.functions import get_user_call_output
from resource_management.libraries.functions import StackFeature
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions.decorator import retry
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.show_logs import show_logs
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.version_select_util import get_component_version_from_symlink


def hive_service(name, action='start', upgrade_type=None):

  import params
  import status_params
  hadoop_home = params.hadoop_home
  hive_bin = "hive"
  environment = { 
      'HADOOP_HOME': hadoop_home,
      'JAVA_HOME': params.java64_home,
      'HIVE_BIN': hive_bin,
      'TEZ_CONF_DIR': params.tez_conf_dir
  }
  if name == 'metastore':
    pid_file = status_params.hive_metastore_pid
    cmd = format("{start_metastore_path} {hive_log_dir}/hive.out {hive_log_dir}/hive.err {pid_file}")
    if check_stack_feature(StackFeature.HIVE_SEPARATED_CONF_DIR_HS2_AND_METASTORE, params.version_for_stack_feature_checks):
      environment['HIVE_CONF_DIR'] = params.hive_metastore_conf_dir
    else:
      environment['HIVE_CONF_DIR'] = params.hive_server_conf_dir
  elif name == 'hiveserver2':
    pid_file = status_params.hive_pid
    cmd = format("{start_hiveserver2_path} {hive_log_dir}/hive-server2.out {hive_log_dir}/hive-server2.err {pid_file}")
    environment['HIVE_CONF_DIR'] = params.hive_server_conf_dir

    if params.security_enabled and check_stack_feature(StackFeature.HIVE_SERVER2_KERBERIZED_ENV, params.version_for_stack_feature_checks):
      hive_kinit_cmd = format("{kinit_path_local} -kt {hive_server2_keytab} {hive_principal}; ")
      Execute(hive_kinit_cmd, user=params.hive_user)

  pid = get_user_call_output.get_user_call_output(format("cat {pid_file}"), user=params.hive_user, is_checked_call=False)[1]
  process_id_exists_command = format("ls {pid_file} >/dev/null 2>&1 && ps -p {pid} >/dev/null 2>&1")

  if action == 'start':
    if name == 'hiveserver2':
      check_fs_root(params.hive_server_conf_dir, params.execute_path)

    daemon_cmd = cmd

    # upgrading hiveserver2 (rolling_restart) means that there is an existing,
    # de-registering hiveserver2; the pid will still exist, but the new
    # hiveserver is spinning up on a new port, so the pid will be re-written
    if upgrade_type == UPGRADE_TYPE_ROLLING:
      process_id_exists_command = None

      if params.version and params.stack_root:
        hadoop_home = format("{stack_root}/{version}/hadoop")
        hive_bin = os.path.join(params.hive_bin, hive_bin)
      
    Execute(daemon_cmd, 
      user = params.hive_user,
      environment = environment,
      path = params.execute_path,
      not_if = process_id_exists_command)

    if params.hive_jdbc_driver == "com.mysql.jdbc.Driver" or \
       params.hive_jdbc_driver == "org.postgresql.Driver" or \
       params.hive_jdbc_driver == "oracle.jdbc.driver.OracleDriver":

      validation_called = False

      if params.hive_jdbc_target is not None:
        validation_called = True
        validate_connection(params.hive_jdbc_target, params.hive_lib)

      if not validation_called:
        emessage = "ERROR! DB connection check should be executed at least one time!"
        Logger.error(emessage)
    
    if name == 'hiveserver2':
      wait_for_znode()

  elif action == 'stop':

    graceful_timeout = None
    if name == 'hiveserver2':
      pid = pid.strip()
      if not pid:
        File(pid_file, action="delete")
        return
      if not pid.isdigit() or int(pid) <= 1:
        raise Fail("Invalid HiveServer2 PID in {0}: {1}".format(pid_file, pid))
      # Hive may remove its PID file while draining; wait for the process itself.
      process_id_exists_command = format("ps -p {pid} >/dev/null 2>&1")
      if shell.call(process_id_exists_command, quiet=True)[0] != 0:
        File(pid_file, action="delete")
        return
      graceful_timeout = hiveserver2_stop_timeout()

    daemon_kill_cmd = format("{sudo} kill {pid}")
    daemon_hard_kill_cmd = format("{sudo} kill -9 {pid}")

    Execute(daemon_kill_cmd,
      not_if = format("! ({process_id_exists_command})")
    )

    if graceful_timeout is not None:
      # Hive's --graceful_stop launcher uses this same SIGTERM shutdown hook.
      Logger.info("Waiting up to {0}s for HiveServer2 PID {1} to drain".format(graceful_timeout, pid))
      deadline = time.monotonic() + graceful_timeout
      while shell.call(process_id_exists_command, quiet=True)[0] == 0:
        if time.monotonic() >= deadline:
          Logger.warning("HiveServer2 PID {0} exceeded its graceful shutdown timeout; forcing stop".format(pid))
          break
        time.sleep(1)

    wait_time = 5 if graceful_timeout is None else 0
    Execute(daemon_hard_kill_cmd,
      not_if = format("! ({process_id_exists_command}) || ( sleep {wait_time} && ! ({process_id_exists_command}) )"),
      ignore_failures = True
    )

    try:
      # check if stopped the process, else fail the task
      Execute(format("! ({process_id_exists_command})"),
        tries=20,
        try_sleep=3,
      )
    except:
      show_logs(params.hive_log_dir, params.hive_user)
      raise

    File(pid_file,
         action = "delete"
    )


def hiveserver2_stop_timeout():
  """Use the selected HS2 binaries, not an upgrade command's target version."""
  import params

  running_version = get_component_version_from_symlink(params.stack_name, "hive-server2")
  if not check_stack_feature(StackFeature.HIVE_SERVER2_GRACEFUL_SHUTDOWN, running_version):
    return None

  key = "hive.server2.graceful.stop.timeout"
  configurations = params.config["configurations"]
  value = configurations.get("hiveserver2-site", {}).get(
    key, configurations.get("hive-site", {}).get(key, "1800s"))
  match = re.fullmatch(r"\s*(\d+)\s*([a-z]*)\s*", str(value).lower())
  units = {
    "": 1, "s": 1, "sec": 1, "second": 1, "seconds": 1,
    "m": 60, "min": 60, "minute": 60, "minutes": 60,
    "h": 3600, "hour": 3600, "hours": 3600,
    "d": 86400, "day": 86400, "days": 86400,
    "ms": .001, "msec": .001, "millisecond": .001, "milliseconds": .001,
    "us": .000001, "usec": .000001, "microsecond": .000001, "microseconds": .000001,
    "ns": .000000001, "nsec": .000000001, "nanosecond": .000000001, "nanoseconds": .000000001,
  }
  if match is None or match.group(2) not in units:
    raise Fail("Invalid {0}: {1}".format(key, value))
  # Hive reserves 30s for cleanup even when draining is disabled with zero.
  timeout = max(30, int(math.ceil(int(match.group(1)) * units[match.group(2)]))) + 5
  command_timeout = int(params.config.get("commandParams", {}).get("command_timeout", 3600))
  if timeout + 120 > command_timeout:
    raise Fail("HiveServer2 graceful shutdown needs {0}s plus command overhead, but Ambari's "
               "command timeout is {1}s. Increase the command timeout or reduce {2}.".format(
                 timeout, command_timeout, key))
  return timeout


def validate_connection(target_path_to_jdbc, hive_lib_path):
  import params

  path_to_jdbc = target_path_to_jdbc
  if not params.jdbc_jar_name:
    path_to_jdbc = format("{hive_lib_path}/") + \
                   params.default_connectors_map[params.hive_jdbc_driver] if params.hive_jdbc_driver in params.default_connectors_map else None
    if not os.path.isfile(path_to_jdbc):
      path_to_jdbc = format("{hive_lib_path}/") + "*"
      error_message = "Error! Sorry, but we can't find jdbc driver with default name " + params.default_connectors_map[params.hive_jdbc_driver] + \
                      " in hive lib dir. So, db connection check can fail. Please run 'ambari-server setup --jdbc-db={db_name} --jdbc-driver={path_to_jdbc} on server host.'"
      Logger.error(error_message)

  db_connection_check_command = format(
    "{ambari_java_home}/bin/java -cp {check_db_connection_jar}:{path_to_jdbc} org.apache.ambari.server.DBConnectionVerification '{hive_jdbc_connection_url}' {hive_metastore_user_name} {hive_metastore_user_passwd!p} {hive_jdbc_driver}")

  try:
    Execute(db_connection_check_command,
            path='/usr/sbin:/sbin:/usr/local/bin:/bin:/usr/bin', tries=5, try_sleep=10)
  except:
    show_logs(params.hive_log_dir, params.hive_user)
    raise


def check_fs_root(conf_dir, execution_path):
  import params

  if not params.fs_root.startswith("hdfs://"):
    Logger.info("Skipping fs root check as fs_root does not start with hdfs://")
    return

  metatool_cmd = format("hive --config {conf_dir} --service metatool")
  cmd = as_user(format("{metatool_cmd} -listFSRoot", env={'PATH': execution_path}), params.hive_user) \
        + format(" 2>&1 /dev/null | grep hdfs:// | cut -f1,2,3 -d '/' | grep -v '[main]' | grep -v '{fs_root}' | head -1")
  code, out = shell.call(cmd)

  if code == 0 and out.strip() != "" and params.fs_root.strip() != out.strip():
    out = out.strip()
    cmd = format("{metatool_cmd} -updateLocation {fs_root} {out}")
    Execute(cmd,
            user = params.hive_user,
            environment = {'PATH': execution_path}
    )

@retry(times=30, sleep_time=10, err_class=Fail)
def wait_for_znode():
  import params
  import status_params
  
  try:
    check_process_status(status_params.hive_pid)
  except ComponentIsNotRunning:
    raise Exception(format("HiveServer2 is no longer running, check the logs at {hive_log_dir}"))
  
  cmd = format("{zk_bin}/zkCli.sh -server {zk_quorum} ls /{hive_server2_zookeeper_namespace} | grep 'serverUri='")
  code, out = shell.call(cmd)
  if code == 1:
    raise Fail(format("ZooKeeper node /{hive_server2_zookeeper_namespace} is not ready yet"))
