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
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Execute
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.script.script import Script

import amoro
import amoro_access
import amoro_catalogs

# Main class of the optimizer JVMs the local container of the server launches (bin/optimizer.sh).
LOCAL_OPTIMIZER_CLASS = "org.apache.amoro.optimizer.standalone.StandaloneOptimizer"


class AmoroServer(Script):
  def install(self, env):
    import params
    env.set_params(params)
    self.install_packages(env)

  def configure(self, env, upgrade_type=None, config_dir=None):
    import params
    env.set_params(params)
    amoro.amoro()

  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)

    if check_stack_feature(StackFeature.ROLLING_UPGRADE, params.version_for_stack_feature_checks):
      stack_select.select_packages(params.version)

  def start(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    self.configure(env)
    amoro.setup_database()

    pid_file = params.amoro_pid_file
    Execute(format("{amoro_home}/bin/ams.sh start"),
            user=params.amoro_user,
            environment=amoro.amoro_environment(),
            not_if=format("test -f {pid_file} && ps -p `cat {pid_file}` >/dev/null 2>&1"))

    amoro.wait_for_amoro()
    try:
      amoro.ensure_optimizer_group()
      amoro.ensure_optimizer()
    except Fail as error:
      Logger.warning("Amoro is running but its optimizer could not be set up: {0}".format(error))
    amoro_access.setup_access()
    try:
      amoro_catalogs.register_catalogs()
    except Fail as error:
      Logger.warning("Amoro is running but its catalogs could not be registered (retry with the "
                     "Register Catalogs action): {0}".format(error))

  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)

    Execute(format("{amoro_home}/bin/ams.sh stop"),
            user=params.amoro_user,
            environment=amoro.amoro_environment(),
            only_if=format("test -f {amoro_pid_file}"))
    # The optimizers of the local container outlive the server: stop them with it. The server
    # starts a new one at its next start.
    Execute(format("pkill -u {amoro_user} -f {LOCAL_OPTIMIZER_CLASS}"),
            only_if=format("pgrep -u {amoro_user} -f {LOCAL_OPTIMIZER_CLASS}"))

  def register_catalogs(self, env):
    """REGISTER_CATALOGS: registers the Hive Metastore and Polaris Ozone catalogs missing in Amoro."""
    import params
    env.set_params(params)
    amoro.wait_for_amoro(timeout=60)
    amoro_catalogs.register_catalogs(required=True)

  def setup_access(self, env):
    """SETUP_ACCESS: Ranger policies giving the Amoro user the data of the tables it maintains."""
    import params
    env.set_params(params)
    amoro_access.setup_access(required=True)

  def pause_optimizing(self, env):
    """PAUSE_OPTIMIZING: releases the optimizers and cancels the running optimizing processes (stack upgrade)."""
    import params
    env.set_params(params)
    amoro.wait_for_amoro(timeout=60)
    amoro.pause_optimizing()

  def resume_optimizing(self, env):
    """RESUME_OPTIMIZING: starts the optimizer again."""
    import params
    env.set_params(params)
    amoro.wait_for_amoro(timeout=60)
    amoro.resume_optimizing()

  def check_optimizing_paused(self, env):
    """Stack upgrade stage: fails unless the optimizing is paused."""
    import params
    env.set_params(params)
    amoro.wait_for_amoro(timeout=60)
    amoro.check_optimizing_paused()

  def status(self, env):
    import status_params
    env.set_params(status_params)
    check_process_status(status_params.amoro_pid_file)

  def get_log_folder(self):
    import params
    return params.amoro_log_dir

  def get_user(self):
    import params
    return params.amoro_user

  def get_pid_files(self):
    import status_params
    return [status_params.amoro_pid_file]


if __name__ == "__main__":
  AmoroServer().execute()
