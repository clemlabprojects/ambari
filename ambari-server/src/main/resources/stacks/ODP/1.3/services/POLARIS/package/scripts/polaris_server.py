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
from resource_management.core.logger import Logger
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions import stack_select
from resource_management.libraries.script.script import Script

from polaris import polaris, bootstrap_ozone_catalog_in_polaris
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

    app_cfg = "{0}/{1}".format(params.polaris_conf_dir, params.polaris_conf_file)
    logging_cfg = "{0}/logging.properties".format(params.polaris_conf_dir)
    quarkus_locations = "{0},{1}".format(app_cfg, logging_cfg)
    runtime_env = {
      "QUARKUS_CONFIG_LOCATIONS": quarkus_locations,
      "SMALLRYE_CONFIG_LOCATIONS": quarkus_locations,
    }

    app_props = params.application_properties
    authz_type = str(app_props.get("polaris.authorization.type", "internal")).strip().lower() or "internal"
    # Ensure runtime authorizer selection always uses the effective Ambari value.
    # This avoids accidental fallback to Polaris defaults when config source
    # precedence differs across environments.
    runtime_env["POLARIS_AUTHORIZATION_TYPE"] = authz_type
    Logger.info("Polaris authorization runtime override: POLARIS_AUTHORIZATION_TYPE={0}".format(authz_type))
    if getattr(params, "enable_ranger_polaris", False) and authz_type != "ranger":
      Logger.warning(
        "Ranger plugin is enabled but polaris.authorization.type={0}. "
        "Polaris will not use Ranger authorization until this is set to 'ranger' "
        "in Ambari configuration.".format(authz_type)
      )
    if authz_type == "ranger":
      ranger_service_name = str(
        app_props.get("polaris.authorization.ranger.service-name", "")
      ).strip() or "<unset>"
      ranger_policy_url = str(
        app_props.get("polaris.authorization.ranger.plugin.policy.rest.url", "")
      ).strip() or "<unset>"
      ranger_policy_source = str(
        app_props.get("polaris.authorization.ranger.policy-source-impl", "")
      ).strip() or "<unset>"
      ranger_cache_dir = str(
        app_props.get("polaris.authorization.ranger.plugin.policy.cache.dir", "")
      ).strip() or "<unset>"
      ranger_config_files = sorted(
        key for key in app_props.keys()
        if key.startswith("polaris.authorization.ranger.config-files[")
      )
      ranger_config_summary = ", ".join(ranger_config_files) if ranger_config_files else "<none>"
      Logger.info(
        "Polaris authorization backend: ranger (service-name={0}, policy-url={1}, policy-source={2}, cache-dir={3}, config-keys={4})".format(
          ranger_service_name,
          ranger_policy_url,
          ranger_policy_source,
          ranger_cache_dir,
          ranger_config_summary
        )
      )
      if ranger_policy_url == "<unset>":
        Logger.warning(
          "Ranger authorization is selected but policy REST URL is not set. "
          "Polaris will not be able to download Ranger policies."
        )
    else:
      Logger.info("Polaris authorization backend: {0}".format(authz_type))

    persistence_type = str(app_props.get("polaris.persistence.type", "")).strip()
    if persistence_type:
      runtime_env["POLARIS_PERSISTENCE_TYPE"] = persistence_type

    jdbc_url = str(app_props.get("quarkus.datasource.jdbc.url", "")).strip()
    jdbc_user = str(app_props.get("quarkus.datasource.username", "")).strip()
    jdbc_password = str(app_props.get("quarkus.datasource.password", "")).strip()
    if not jdbc_password:
      jdbc_password = str(getattr(params, "polaris_db_password", "")).strip()

    if jdbc_url:
      runtime_env["QUARKUS_DATASOURCE_JDBC_URL"] = jdbc_url
    if jdbc_user:
      runtime_env["QUARKUS_DATASOURCE_USERNAME"] = jdbc_user
    if jdbc_password:
      runtime_env["QUARKUS_DATASOURCE_PASSWORD"] = jdbc_password

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
            environment=runtime_env,
            not_if=no_op_test
            )

    bootstrap_ozone_catalog_in_polaris()

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
