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
from resource_management.core.resources.packaging import Package
from resource_management.core import shell
from ambari_commons.os_check import OSCheck
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions import stack_select
from resource_management.libraries.script.script import Script

from polaris import configure_polaris


POLICY_RC_D = '/usr/sbin/policy-rc.d'


def install_console_nginx():
  """
  The Console runs its own nginx process (nginx -c nginx-console.conf on polaris_console_port): it only needs the
  nginx program. The deb package also enables and starts the system nginx.service with the default site on port 80
  (and [::]:80, which fails where IPv6 is disabled), so on Debian/Ubuntu a fresh install runs with policy-rc.d denying
  service starts, then the system service is disabled. An nginx already installed on the host is left as it is.
  RPM installs never start or enable services.
  """
  if not OSCheck.is_ubuntu_family():
    Package('nginx')
    return
  code, out = shell.call("dpkg-query -W -f='${Status}' nginx", quiet=True)
  if code == 0 and 'install ok installed' in out:
    return
  created = False
  if not os.path.exists(POLICY_RC_D):
    File(POLICY_RC_D, content="#!/bin/sh\n# Polaris Console install: do not start services\nexit 101\n", mode=0o755)
    created = True
  try:
    Package('nginx')
  finally:
    if created:
      File(POLICY_RC_D, action='delete')
  Execute('systemctl disable nginx.service', only_if='systemctl cat nginx.service')


class PolarisConsole(Script):
  def install(self, env):
    import params
    env.set_params(params)
    self.install_packages(env)
    install_console_nginx()

  def configure(self, env, upgrade_type=None, config_dir=None):
    import params
    env.set_params(params)
    configure_polaris('console')

  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)

    if check_stack_feature(StackFeature.ROLLING_UPGRADE, params.version_for_stack_feature_checks):
      stack_select.select_packages(params.version)

  def start(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    self.configure(env)

    # Verify the PEM cert/key the nginx process will bind to exist on disk.  Done at
    # start (not configure) so service installation doesn't fail when the operator
    # hasn't yet laid down the cert material.
    from polaris import verify_console_tls_at_start
    verify_console_tls_at_start()

    if params.polaris_console_start_command:
      no_op_test = format('test -f {polaris_console_pid_file} && ps -p `cat {polaris_console_pid_file}` >/dev/null 2>&1')
      Execute(
        format('source {polaris_conf_dir}/polaris-env.sh; {polaris_console_start_command}'),
        user=params.polaris_user,
        not_if=no_op_test
      )
    else:
      no_op_test = format('test -f {polaris_console_pid_file} && ps -p `cat {polaris_console_pid_file}` >/dev/null 2>&1')
      Execute(
        format('nginx -c {polaris_console_nginx_conf}'),
        user=params.polaris_user,
        not_if=no_op_test
      )

  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)

    if params.polaris_console_stop_command:
      Execute(format('source {polaris_conf_dir}/polaris-env.sh; {polaris_console_stop_command}'),
              user=params.polaris_user,
              ignore_failures=True
              )
    elif os.path.isfile(params.polaris_console_pid_file):
      Execute(
        format('nginx -c {polaris_console_nginx_conf} -s quit'),
        user=params.polaris_user,
        ignore_failures=True
      )

    File(params.polaris_console_pid_file, action="delete")

  def status(self, env):
    import params
    env.set_params(params)
    check_process_status(params.polaris_console_pid_file)


if __name__ == "__main__":
  PolarisConsole().execute()
