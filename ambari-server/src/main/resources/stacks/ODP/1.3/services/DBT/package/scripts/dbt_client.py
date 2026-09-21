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

from resource_management.core.exceptions import ClientComponentHasNoStatus
from resource_management.core.resources.system import Directory, File
from resource_management.core.source import InlineTemplate, Template
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.constants import StackFeature
from resource_management.core.logger import Logger
from resource_management.libraries.script.script import Script


class DbtClient(Script):
  """dbt has no daemon: installing it means putting the tool and its connection profile on the
  host. There is nothing to start, stop or monitor, which is why this is a client component."""

  def install(self, env):
    self.install_packages(env)
    self.configure(env)

  def configure(self, env, upgrade_type=None, config_dir=None):
    import params
    env.set_params(params)

    Directory([params.dbt_conf_dir, params.dbt_log_dir],
              owner=params.dbt_user,
              group=params.user_group,
              create_parents=True,
              mode=0o755)

    # The connection profile carries a password or a token in two of the four modes, so it is
    # readable by the group that runs dbt and by nobody else.
    #
    # Ambari owns this file only while a Trino host is configured. With no host there is nothing to
    # connect to, so it writes a placeholder once and then keeps its hands off: an operator running
    # dbt against another warehouse writes their own profile there, and it has to survive every
    # later configure. Setting a Trino host hands the file back to Ambari, which overwrites it.
    profile_path = format("{dbt_conf_dir}/profiles.yml")
    if params.trino_host or not os.path.exists(profile_path):
      File(profile_path,
           content=Template("profiles.yml.j2"),
           owner=params.dbt_user,
           group=params.user_group,
           mode=0o640)
    else:
      Logger.info("No Trino host is configured and %s already exists; leaving it as it is."
                  % profile_path)

    File(format("{dbt_conf_dir}/dbt-env.sh"),
         content=InlineTemplate(params.dbt_env_content),
         owner=params.dbt_user,
         group=params.user_group,
         mode=0o755)

  def status(self, env):
    raise ClientComponentHasNoStatus()

  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    # The packages to switch come from the stack definition (stack_packages.json -> DBT/DBT_CLIENT),
    # which names dbt-client; odp-select moves the symlink. Same call POLARIS_CLIENT makes.
    if params.stack_version_formatted and check_stack_feature(StackFeature.ROLLING_UPGRADE, params.stack_version_formatted):
      stack_select.select_packages(params.version)


if __name__ == "__main__":
  DbtClient().execute()
