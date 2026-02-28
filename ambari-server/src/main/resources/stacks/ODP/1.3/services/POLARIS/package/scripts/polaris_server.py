#!/usr/bin/env python
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

from resource_management.core.resources.system import Execute, File
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions import stack_select
from resource_management.libraries.script.script import Script

from polaris import polaris
from setup_ranger_polaris import setup_ranger_polaris


class PolarisServer(Script):
  def install(self, env):
    import params
    env.set_params(params)
    self.install_packages(env)

  def configure(self, env, upgrade_type=None, config_dir=None):
    import params
    env.set_params(params)
    polaris('server')
    setup_ranger_polaris()

  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)

    if check_stack_feature(StackFeature.ROLLING_UPGRADE, params.version_for_stack_feature_checks):
      stack_select.select_packages(params.version)

  def start(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    self.configure(env)

    no_op_test = format('test -f {polaris_pid_file} && ps -p `cat {polaris_pid_file}` >/dev/null 2>&1')
    # Polaris runtime launcher is foreground; daemonize it for Ambari service control.
    start_cmd = format(
      'source {polaris_conf_dir}/polaris-env.sh; '
      'nohup {polaris_start_command} >> {polaris_log_dir}/polaris-bootstrap.log 2>&1 & '
      'echo $! > {polaris_pid_file}; '
      'sleep 2; '
      'ps -p `cat {polaris_pid_file}` >/dev/null 2>&1'
    )
    Execute(start_cmd,
            user=params.polaris_user,
            not_if=no_op_test
            )

  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)

    if params.polaris_stop_command:
      Execute(format('source {polaris_conf_dir}/polaris-env.sh; {polaris_stop_command}'),
              user=params.polaris_user,
              ignore_failures=True
              )
    else:
      if os.path.isfile(params.polaris_pid_file):
        Execute(format("kill `cat {polaris_pid_file}`"),
                user=params.polaris_user,
                ignore_failures=True
                )

    File(params.polaris_pid_file, action="delete")

  def status(self, env):
    import status_params
    env.set_params(status_params)
    check_process_status(status_params.polaris_pid_file)


if __name__ == "__main__":
  PolarisServer().execute()
