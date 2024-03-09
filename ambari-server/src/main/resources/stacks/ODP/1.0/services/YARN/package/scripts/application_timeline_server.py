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

from resource_management.libraries.script.script import Script
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions import check_process_status
from resource_management.libraries.functions.security_commons import build_expectations, \
  cached_kinit_executor, get_params_from_filesystem, validate_security_config_properties,\
  FILE_TYPE_XML
from resource_management.libraries.functions.format import format
from resource_management.core.shell import as_user
from resource_management.core import shell
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Execute
from resource_management.core.exceptions import Fail

from yarn import yarn
from service import service
from ambari_commons import OSConst
from ambari_commons.os_family_impl import OsFamilyImpl
from setup_ranger_yarn import setup_ranger_yarn


import time
class ApplicationTimelineServer(Script):
  def install(self, env):
    self.install_packages(env)

  def start(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    self.configure(env) # FOR SECURITY
    # setup security before start or YARN ATS will not start
    if params.enable_ranger_yarn and params.is_supported_yarn_ranger:
      setup_ranger_yarn() #Ranger Yarn Plugin related calls

    wait_yarn_fast_launch_acls()
    service('timelineserver', action='start')


  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    service('timelineserver', action='stop')

  def configure(self, env):
    import params
    env.set_params(params)
    yarn(name='apptimelineserver')

def wait_yarn_fast_launch_acls(afterwait_sleep=0, execute_kinit=True, retries=60, sleep_seconds=10):
  """
  Wait for Yarn to access right on FastLaunch directory
  """
  import params

  if params.security_enabled and execute_kinit:
    kinit_command = format("{params.kinit_path_local} -kt {params.rm_keytab} {params.rm_principal_name}")
    Execute(kinit_command, user=params.yarn_user, logoutput=True)

  yarn_fastlaunch_is_enabled = False
  counter = 0
  while not yarn_fastlaunch_is_enabled:
    try:
      enable_cmd = as_user(format("yarn app -enableFastLaunch"), user=params.yarn_user)
      code, output = shell.call(enable_cmd, timeout=60)
      if code == 0:
        Logger.info(format('Yarn FastLaunch Enabled Successfully'))
        yarn_fastlaunch_is_enabled = True
        return True
      else:
        yarn_fastlaunch_is_enabled = False
        if counter < retries:
          Logger.info("Waiting up to {0} seconds to HDFS to grant access ...".format(sleep_seconds))
          time.sleep(sleep_seconds)
        else:
          Logger.info("Failed to Enable Yarn FastLaunch")
    except Fail:
      Logger.error("Failed for HDFS to grant YARN FAstLaunch ACL")                
    counter += 1

@OsFamilyImpl(os_family=OSConst.WINSRV_FAMILY)
class ApplicationTimelineServerWindows(ApplicationTimelineServer):
  def status(self, env):
    service('timelineserver', action='status')


@OsFamilyImpl(os_family=OsFamilyImpl.DEFAULT)
class ApplicationTimelineServerDefault(ApplicationTimelineServer):
  def pre_upgrade_restart(self, env, upgrade_type=None):
    Logger.info("Executing Stack Upgrade pre-restart")
    import params
    env.set_params(params)

    if params.version and check_stack_feature(StackFeature.ROLLING_UPGRADE, params.version):
      stack_select.select_packages(params.version)

  def status(self, env):
    import status_params
    env.set_params(status_params)
    check_process_status(status_params.yarn_historyserver_pid_file)

  def get_log_folder(self):
    import params
    return params.yarn_log_dir
  
  def get_user(self):
    import params
    return params.yarn_user

  def get_pid_files(self):
    import status_params
    Execute(format("mv {status_params.yarn_historyserver_pid_file_old} {status_params.yarn_historyserver_pid_file}"),
            only_if = format("test -e {status_params.yarn_historyserver_pid_file_old}", user=status_params.yarn_user))
    return [status_params.yarn_historyserver_pid_file]

if __name__ == "__main__":
  ApplicationTimelineServer().execute()
