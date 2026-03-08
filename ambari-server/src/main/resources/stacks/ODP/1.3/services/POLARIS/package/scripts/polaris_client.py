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
from resource_management.core.exceptions import ClientComponentHasNoStatus, Fail
from resource_management.core.resources.system import Execute
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions import stack_select
from resource_management.libraries.script.script import Script

from polaris import configure_polaris


class PolarisClient(Script):
  def install(self, env):
    self.install_packages(env)
    self.configure(env)

  def configure(self, env, upgrade_type=None, config_dir=None):
    import params
    env.set_params(params)
    configure_polaris('client')

  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)

    if check_stack_feature(StackFeature.ROLLING_UPGRADE, params.version_for_stack_feature_checks):
      stack_select.select_packages(params.version)

  def status(self, env):
    raise ClientComponentHasNoStatus()

  def tools_smoke_check(self, env):
    import params
    env.set_params(params)

    checks = [
      ("tools home", format("test -d {polaris_tools_home}")),
      ("iceberg-catalog-migrator wrapper", format("test -x {polaris_tools_home}/bin/iceberg-catalog-migrator")),
      ("polaris-synchronizer wrapper", format("test -x {polaris_tools_home}/bin/polaris-synchronizer")),
      ("polaris-mcp wrapper", format("test -x {polaris_tools_home}/bin/polaris-mcp")),
      ("polaris-console wrapper", format("test -x {polaris_tools_home}/bin/polaris-console")),
      ("polaris-benchmarks wrapper", format("test -x {polaris_tools_home}/bin/polaris-benchmarks")),
      ("iceberg-catalog-migrator JAR", format("test -r {polaris_tools_home}/lib/iceberg-catalog-migrator-cli.jar")),
      ("polaris-synchronizer JAR", format("test -r {polaris_tools_home}/lib/polaris-synchronizer-cli.jar")),
      ("MCP vendor module", format("test -d {polaris_tools_home}/mcp/vendor/polaris_mcp")),
      ("console dist assets", format("test -d {polaris_tools_home}/console/dist")),
      ("benchmarks wrapper target", format("test -x {polaris_tools_home}/benchmarks/gradlew")),
      ("polaris-console --path", format("{polaris_tools_home}/bin/polaris-console --path >/dev/null")),
      ("polaris-benchmarks --help", format("{polaris_tools_home}/bin/polaris-benchmarks --help >/dev/null")),
    ]

    for label, cmd in checks:
      try:
        Execute(cmd, user=params.smoke_test_user)
      except Exception as err:
        raise Fail("Polaris tools smoke check failed for {0}: {1}".format(label, err))


if __name__ == "__main__":
  PolarisClient().execute()
