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
import re

from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions.constants import Direction
from resource_management.libraries.functions.security_commons import build_expectations
from resource_management.libraries.functions.security_commons import cached_kinit_executor
from resource_management.libraries.functions.security_commons import validate_security_config_properties
from resource_management.libraries.functions.security_commons import get_params_from_filesystem
from resource_management.libraries.functions.security_commons import FILE_TYPE_XML
from resource_management.libraries.functions.show_logs import show_logs
from resource_management.core.resources.system import Directory, File, Execute, Link
from resource_management.core.resources.service import Service
from resource_management.core.logger import Logger
from resource_management.core.shell import as_user
from resource_management.core import shell
from resource_management import InlineTemplate, Template
from resource_management.core.exceptions import Fail
from resource_management.libraries.functions.decorator import retry

from ambari_commons import OSConst, OSCheck
from ambari_commons.os_family_impl import OsFamilyImpl

import time
import upgrade

class HueGateway(Script):
  def install(self, env):
    import params
    env.set_params(params)
    self.install_packages(env)

  def configure(self, env, upgrade_type=None):
    import params
    env.set_params(params)

@OsFamilyImpl(os_family=OsFamilyImpl.DEFAULT)
class HueGatewayDefault(HueGateway):

  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    stack_select.select_packages(params.version)

    # seed the new Knox data directory with the keystores of yesteryear
    if params.upgrade_direction == Direction.UPGRADE:
      upgrade.seed_current_data_directory()


  def start(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    self.configure(env)

    ## create layout directories
    Directory([params.hue_logs_dir, params.hue_pid_dir, params.hue_conf_dir],
              owner = params.hue_user,
              group = params.hue_group,
              create_parents = True,
              cd_access = "a",
              mode = 0o755,
              recursive_ownership = True,
    )

    ## Render Hue ini configuration
    File(format("{params.hue_conf_dir}/hue.ini"),
         mode=0o644,
         group=params.hue_group,
         owner=params.hue_user,
         content=InlineTemplate(params.hue_ini_content)
    )
    File(format("{params.hue_log_redaction_file}"),
      mode=0o644,
      group=params.hue_group,
      owner=params.hue_user,
      content=Template("redaction-rules.json")
    )
    

    # generate secret password and set permission to Hue with RO
    cmd = format('{params.hue_home_dir}/bin/secret.sh')
    Execute(cmd,
            user=params.hue_user,
            environment={'HUE_CONF_DIR': params.hue_conf_dir}
    )
    command = "chown {0}:{1} {2}/.secret".format(params.hue_user, params.hue_group, params.hue_conf_dir)
    Execute(command)
    command = "chmod 0400 {0}/.secret".format(params.hue_conf_dir)
    Execute(command)

    ## encrypt database password
    cmd = format('{params.hue_home_dir}/bin/encrypt.sh encrypt db-secret-password {hue_db_password!p}')
    Execute(cmd,
            user=params.hue_user,
            environment={'HUE_CONF_DIR': params.hue_conf_dir}
    )
    command = "chown {0}:{1} {2}/db-secret-password".format(params.hue_user, params.hue_group, params.hue_conf_dir)
    Execute(command)
    command = "chmod 0600 {0}/db-secret-password".format(params.hue_conf_dir)
    Execute(command)
    
    if params.ldap_auth:
      ## encrypt ldap password
      cmd = format('{params.hue_home_dir}/bin/encrypt.sh encrypt ldap-secret-password {hue_bind_password!p}')
      Execute(cmd,
              user=params.hue_user,
              environment={'HUE_CONF_DIR': params.hue_conf_dir}
      )
      command = "chown {0}:{1} {2}/ldap-secret-password".format(params.hue_user, params.hue_group, params.hue_conf_dir)
      Execute(command)
      command = "chmod 0600 {0}/ldap-secret-password".format(params.hue_conf_dir)
      Execute(command)

    ## encrypt Cookie password
    cmd = format('{params.hue_home_dir}/bin/encrypt.sh encrypt cookie-secret-password {hue_cookie_password!p}')
    Execute(cmd,
            user=params.hue_user,
            environment={'HUE_CONF_DIR': params.hue_conf_dir}
    )
    command = "chown {0}:{1} {2}/cookie-secret-password".format(params.hue_user, params.hue_group, params.hue_conf_dir)
    Execute(command)
    command = "chmod 0600 {0}/cookie-secret-password".format(params.hue_conf_dir)
    Execute(command)


    ## Run HUE DB migration script
    try:
      Execute((format('{params.hue_home_dir}/build/env/bin/hue'), 'migrate'))
    except:
      show_logs(params.hue_logs_dir, params.hue_user)
    first_time = False
    ## Create the default admin password ## need to change password before creating user
    create_cmd = format('{params.hue_home_dir}/build/env/bin/hue createsuperuser --username {params.hue_admin_username}  --email {params.hue_admin_username}@hadoop.com --noinput')
    change_password_cmd = format('export HUE_HOME_DIR={params.hue_home_dir}; {params.hue_home_dir}/bin/changepassword.sh {params.hue_admin_username} {params.hue_admin_password!p}')
    try:
      Logger.info(format('Changing password for Hue {params.hue_admin_username} super admin user'))
      code, output = shell.call(change_password_cmd, timeout=20)
      if code == 0:
        Logger.info(format('Password successfully changed'))
      elif code == 1:
        pattern = u"CommandError: user '\\w+' does not exist"
        if re.search(pattern, output):
            Logger.info(format('Superuser {params.hue_admin_username} does not exist. Creating it...'))
            Execute(create_cmd,
              user=params.hue_user,
              environment={'DJANGO_SUPERUSER_PASSWORD': params.hue_admin_password},
            )
            first_time = True
            # need to sleep on the first time
            time.sleep(5)
        else:
          Logger.error(format('Failed to Change Password / Create superadmin user'))
    except:
      show_logs(params.hue_logs_dir, params.hue_user)
      raise    ## Start Hue using Supervisord
    daemon_cmd = format('{params.hue_home_dir}/build/env/bin/supervisor -d -p {params.hue_pid_dir}/hue.pid')
    no_op_test = format('ls {params.hue_pid_file} >/dev/null 2>&1 && ps -p `cat {params.hue_pid_file}` >/dev/null 2>&1')
    try:
      # need to sleep on the first time
      if first_time:
        time.sleep(10)
      Execute(daemon_cmd,
              user=params.hue_user,
              environment={},
              not_if=no_op_test
      )
      # need to sleep on the first time
      if first_time:
        time.sleep(5)
    except:
      show_logs(params.hue_logs_dir, params.hue_user)
      raise

  @retry(times=5, sleep_time=5, err_class=Fail)
  def post_start(self, env=None):
    return super(HueGatewayDefault, self).post_start(env)


  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    check_process = as_user(format("ls {hue_pid_file} > /dev/null 2>&1 && ps -p `cat {hue_pid_file}` > /dev/null 2>&1"), user=params.hue_user)
    code, out = shell.call(check_process)
    if code == 0:
      Logger.debug("Hue Supervisor is running and will be killed.")
      kill_command = format("kill -15 `cat {hue_pid_file}`")
      Execute(kill_command,
              user=params.hue_user
      )
      File(params.hue_pid_file,
            action = "delete",
            )
      return True

  def status(self, env):
    import status_params
    env.set_params(status_params)
    check_process_status(status_params.hue_pid_file)

  def get_log_folder(self):
    import params
    return params.hue_logs_dir
  
  def get_user(self):
    import params
    return params.hue_user

  def get_pid_files(self):
    import status_params
    return [status_params.hue_pid_file]


if __name__ == "__main__":
  HueGateway().execute()
