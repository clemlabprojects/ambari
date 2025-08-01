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

from ambari_commons.constants import  UPGRADE_TYPE_NON_ROLLING, UPGRADE_TYPE_ROLLING

from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.show_logs import show_logs
from resource_management.core.shell import as_sudo
from resource_management.core.resources.system import Execute, File, Directory

import os

def ozone_service(
  name,
  action = 'start'): # 'start' or 'stop' or 'status'
    
    import params
    Directory( params.ozone_hdds_metadata_dir,
      owner = params.ozone_user,
      create_parents = True,
      cd_access = "a",
      mode = 0o755,
    )
    conf_dir = os.path.join(params.ozone_base_conf_dir, params.ROLE_NAME_MAP_CONF[name])
    role = params.ROLE_NAME_MAP_DAEMON[name]
    cmd = format("{daemon_script} --config {conf_dir}")
    pid_file = format("{pid_dir}/ozone-{ozone_user}-{role}.pid")
    pid_expression = as_sudo(["cat", pid_file])
    no_op_test = as_sudo(["test", "-f", pid_file]) + format(" && ps -p `{pid_expression}` >/dev/null 2>&1")
    
    if action == 'start':
      suffix_upgrade = ""
      if name == "ozone-manager":
        if params.upgrade_type is not None:
          if params.upgrade_type == UPGRADE_TYPE_ROLLING:
            raise Fail("Rolling upgrade is not supported for Ozone Manager")
          if params.upgrade_type == UPGRADE_TYPE_NON_ROLLING:
            suffix_upgrade = "--upgrade"

      daemon_cmd = format("{cmd} --daemon start {role} {suffix_upgrade}")
      
      try:
        Execute ( daemon_cmd,
          not_if = no_op_test,
          user = params.ozone_user
        )
      except:
        show_logs(params.log_dir, params.ozone_user)
        raise
    elif action == 'stop':
      daemon_cmd = format("{cmd} --daemon stop {role}")

      try:
        Execute ( daemon_cmd,
          user = params.ozone_user,
          only_if = no_op_test,
          on_timeout = format("! ( {no_op_test} ) || {sudo} -H -E kill -9 `{pid_expression}`"),
        )
      except:
        show_logs(params.log_dir, params.ozone_user)
        raise
      
      File(pid_file,
           action = "delete",
      )
