"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agree in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""
import subprocess
import time
import os

from resource_management.core.exceptions import Fail
from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.format import format
from resource_management.core.resources.system import Execute
from resource_management.core.logger import Logger

CHECK_COMMAND_TIMEOUT_DEFAULT = 60.0

class FlinkServiceCheck(Script):
  def service_check(self, env):
    import params
    env.set_params(params)

    if params.security_enabled:
      flink_kinit_cmd = format("{kinit_path_local} -kt {smoke_user_keytab} {smokeuser_principal}; ")
      Execute(flink_kinit_cmd, user=params.smoke_user)
    
    # Client check application
    Execute(params.flink_service_check_cmd, user=params.smoke_user)

    if params.has_historyserver:
      Logger.info("Executing Flink History Server Check")
      live_flink_historyserver_host = ""

      for flink_historyserver in params.flink_jobhistoryserver_hosts:
        try:
          Execute(format("curl -s -o /dev/null -w'%{{http_code}}' --negotiate -u: -k {flink_historyServer_scheme}://{flink_historyserver}:{flink_history_ui_port}/config | grep 200"),
                  tries=3,
                  try_sleep=1,
                  logoutput=True,
                  user=params.smoke_user
                  )
          live_flink_historyserver_host = flink_historyserver
          break
        except:
          pass
      if len(params.flink_jobhistoryserver_hosts) > 0 and live_flink_historyserver_host == "":
        raise Fail("Connection to Flink Job history Server failed")
    

if __name__ == "__main__":
  FlinkServiceCheck().execute()

