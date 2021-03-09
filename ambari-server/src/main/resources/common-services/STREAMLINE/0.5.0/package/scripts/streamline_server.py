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
from resource_management import *
from resource_management import Script
from resource_management.core import shell, sudo
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Execute, File, Directory
from resource_management.libraries.functions import conf_select
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions import Direction
from resource_management.libraries.functions import default
from resource_management.libraries.functions.version import format_stack_version
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions import StackFeature
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.show_logs import show_logs
from resource_management.core.shell import as_sudo
from resource_management.core import sudo
from resource_management.core.utils import PasswordString

import os, time, glob
from streamline import ensure_base_directories
from streamline import streamline, wait_until_server_starts


class StreamlineServer(Script):

  def get_component_name(self):
    stack_name = default("/clusterLevelParams/stack_name", None)
    if stack_name == "HDP":
      return None
    return "streamline"

  def execute_bootstrap(self, params):

    # If Current version >= 3.1, migrate else create
    try:
      if params.stack_sam_support_schema_migrate:
        Execute(params.bootstrap_storage_run_cmd + ' migrate',
              user=params.streamline_user)
      else:
        Execute(params.bootstrap_storage_run_cmd + ' create',
              user=params.streamline_user)
    except:
      show_logs(params.streamline_log_dir, params.streamline_user)
      raise

  def execute_bootstrap_storage_env(self, params):
    from urlparse import urlparse
    try:
      # Getting hostname where streamline's database would be installed.
      streamline_storage_database_hostname = urlparse(urlparse(params.streamline_storage_connector_connectorURI)[2])[1].split(":")[0]
      database_admin_jdbc_url = params.database_admin_jdbc_url
      if params.streamline_storage_type == 'postgresql':
        database_admin_jdbc_url = database_admin_jdbc_url + '/postgres'
      bootstrap_storage_initevn_db_cmd = database_admin_jdbc_url + ' ' + params.database_admin_user_name + ' ' + PasswordString(params.database_admin_password) + ' ' + params.streamline_storage_connector_user + ' ' + PasswordString(params.streamline_storage_connector_password) + ' ' + params.streamline_storage_database
      Execute(params.bootstrap_storage_initevn_run_cmd + ' ' + bootstrap_storage_initevn_db_cmd, user='root')
    except:
      show_logs(params.streamline_log_dir, params.streamline_user)
      raise

  def install(self, env):
    import params
    self.install_packages(env)
    self.configure(env)
    if params.stack_streamline_support_db_user_creation:
      if params.database_create_db_dbuser == "true":
        self.execute_bootstrap_storage_env(params)
    if not params.stack_sam_support_schema_migrate:
      self.execute_bootstrap(params)

  def configure(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    streamline(env, upgrade_type=None)

  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    if not params.stack_sam_support_schema_migrate:
      if params.upgrade_direction == Direction.UPGRADE:
        Logger.info("Executing bootstrap_storage as it is upgrade")
        self.execute_bootstrap(params)
      else:
        Logger.info("Not executing bootstrap_storage as it is downgrade")

    if params.version and check_stack_feature(StackFeature.ROLLING_UPGRADE, format_stack_version(params.version)):
      stack_select.select("streamline", params.version)
    if params.version and check_stack_feature(StackFeature.CONFIG_VERSIONING, params.version):
      conf_select.select(params.stack_name, "streamline", params.version)

  def kerberos_server_start(self):
    import params
    if params.security_enabled:
      kinit_cmd = format("{kinit_path_local} -kt {params.streamline_keytab_path} {params.streamline_jaas_principal};")
      return_code, out = shell.checked_call(kinit_cmd,
                                            path = '/usr/sbin:/sbin:/usr/local/bin:/bin:/usr/bin',
                                            user = params.streamline_user)

  def start(self, env, upgrade_type=None):
    import params
    import status_params
    env.set_params(params)
    self.configure(env)

    self.kerberos_server_start()

    if params.stack_sam_support_schema_migrate:
      self.execute_bootstrap(params)

    daemon_cmd = format('source {params.conf_dir}/streamline-env.sh ; {params.streamline_bin} start')
    no_op_test = format('ls {status_params.streamline_pid_file} >/dev/null 2>&1 && ps -p `cat {status_params.streamline_pid_file}` >/dev/null 2>&1')

    try:
      Execute(daemon_cmd,
              user="root",
              not_if=no_op_test
      )
    except:
      show_logs(params.streamline_log_dir, params.streamline_user)
      raise

    try:
      wait_until_server_starts()

      #Check to see if bootstrap_done file exists or not.
      if os.path.isfile(params.bootstrap_file):
        if params.stack_sam_support_schema_migrate:
          File(params.bootstrap_file, owner=params.streamline_user, group=params.user_group, mode=0644)
          Execute(params.bootstrap_run_cmd + ' migrate', user=params.streamline_user)
      else:
        if params.stack_sam_support_schema_migrate:
          File(params.bootstrap_file, owner=params.streamline_user, group=params.user_group, mode=0644)
          Execute(params.bootstrap_run_cmd + ' migrate', user=params.streamline_user)
        else:
          File(params.bootstrap_file, owner=params.streamline_user, group=params.user_group, mode=0644)
          Execute(params.bootstrap_run_cmd, user=params.streamline_user)
    except:
      show_logs(params.streamline_log_dir, params.streamline_user)
      raise

  def stop(self, env, upgrade_type=None):
    import params
    import status_params
    env.set_params(params)
    ensure_base_directories()

    daemon_cmd = format('source {params.conf_dir}/streamline-env.sh; {params.streamline_bin} stop')
    try:
      Execute(daemon_cmd,
              user=params.streamline_user,
      )
    except:
      show_logs(params.streamline_log_dir, params.streamline_user)
      raise
    File(status_params.streamline_pid_file,
          action = "delete"
    )

  def status(self, env):
    import status_params
    check_process_status(status_params.streamline_pid_file)
    
  def get_log_folder(self):
    import params
    return params.streamline_log_dir
  
  def get_user(self):
    import params
    return params.streamline_user

  def get_pid_files(self):
    import status_params
    return [status_params.streamline_pid_file]

  def create_config_version(self, env):
    import params
    for package_name, directories in conf_select.get_package_dirs().iteritems():
      if package_name == 'streamline':
        conf_select.convert_conf_directories_to_symlinks(package_name, params.current_version, directories)

  def create_30_config_version(self, env):
    package_name = 'streamline'
    stack_root = Script.get_stack_root()
    current_dir = "{0}/current/streamline/conf".format(stack_root)
    directories = [{"conf_dir": "/etc/streamline/conf","current_dir": current_dir}]
    stack_version = stack_select.get_stack_version_before_install(package_name)
    conf_dir = "/etc/streamline/conf"
    if stack_version:
      try:
        #Check if broken symbolic links issue exists
        os.stat(conf_dir)
        conf_select.convert_conf_directories_to_symlinks(package_name, stack_version, directories)
        cp_cmd = as_sudo(["cp","-a","-f","/etc/streamline/conf.backup/.","/etc/streamline/conf"])
        Execute(cp_cmd,logoutput = True)
      except OSError as e:
        Logger.warning("Detected broken symlink : {0}. Attempting to repair.".format(str(e)))
        #removing symlink conf directory
        sudo.unlink(conf_dir)
        #make conf dir again
        sudo.makedirs(conf_dir,0755)
        #copy all files
        for files in glob.glob("/etc/streamline/conf.backup/*"):
          cp_cmd = as_sudo(["cp","-r",files,conf_dir])
          Execute(cp_cmd,logoutput = True)
        conf_select.convert_conf_directories_to_symlinks(package_name, stack_version, directories)

if __name__ == "__main__":
  StreamlineServer().execute()