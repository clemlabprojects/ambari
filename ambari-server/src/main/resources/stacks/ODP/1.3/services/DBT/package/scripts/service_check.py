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
from resource_management.core.resources.system import Execute
from resource_management.libraries.functions.format import format
from resource_management.libraries.script.script import Script


class DbtServiceCheck(Script):
  """Proves the tool is installed and its profile parses. It deliberately does not run a model:
  a service check must not write to anyone's warehouse."""

  def service_check(self, env):
    import params
    env.set_params(params)

    Execute(format("{dbt_home}/bin/dbt --version"),
            user=params.dbt_user,
            logoutput=True,
            tries=1)

    # Checking the profile only makes sense when there is one. A service installed for a warehouse
    # other than Trino, or ahead of the engine existing, has no connection configured, and a check
    # that insisted on one would fail for a service that is in fact fine.
    if params.trino_host:
      # debug --config-dir only reads the profile and reports where it looked; no connection is made.
      Execute(format("{dbt_home}/bin/dbt debug --config-dir"),
              environment={"DBT_PROFILES_DIR": params.dbt_conf_dir},
              user=params.dbt_user,
              logoutput=True,
              tries=1)


if __name__ == "__main__":
  DbtServiceCheck().execute()
