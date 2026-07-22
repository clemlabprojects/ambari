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

from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions.constants import Direction
from resource_management.libraries.functions.security_commons import build_expectations
from resource_management.libraries.functions.security_commons import cached_kinit_executor
from resource_management.libraries.functions.security_commons import validate_security_config_properties
from resource_management.libraries.functions.security_commons import get_params_from_filesystem
from resource_management.libraries.functions.security_commons import FILE_TYPE_XML
from resource_management.libraries.functions.show_logs import show_logs
from resource_management.core.resources.system import File, Execute, Link
from resource_management.core.resources.service import Service
from resource_management.core.logger import Logger


from ambari_commons import OSConst, OSCheck
from ambari_commons.os_family_impl import OsFamilyImpl

if OSCheck.is_windows_family():
  from resource_management.libraries.functions.windows_service_utils import check_windows_service_status

import upgrade
from knox import knox, update_knox_logfolder_permissions
from knox_ldap import ldap
from setup_ranger_knox import setup_ranger_knox


class KnoxGateway(Script):
  def install(self, env):
    import params
    env.set_params(params)
    self.install_packages(env)

    File(os.path.join(params.knox_conf_dir, 'topologies', 'sandbox.xml'),
         action = "delete",
    )

  def configure(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    knox()
    ldap()

  def configureldap(self, env):
    import params
    env.set_params(params)
    ldap()



@OsFamilyImpl(os_family=OSConst.WINSRV_FAMILY)
class KnoxGatewayWindows(KnoxGateway):
  def start(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    self.configure(env)
    # setup_ranger_knox(env)
    Service(params.knox_gateway_win_service_name, action="start")

  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    Service(params.knox_gateway_win_service_name, action="stop")

  def status(self, env):
    import status_params
    env.set_params(status_params)
    check_windows_service_status(status_params.knox_gateway_win_service_name)

  def startdemoldap(self, env):
    import params
    env.set_params(params)
    self.configureldap(env)
    Service(params.knox_ldap_win_service_name, action="start")

  def stopdemoldap(self, env):
    import params
    env.set_params(params)
    Service(params.knox_ldap_win_service_name, action="stop")



@OsFamilyImpl(os_family=OsFamilyImpl.DEFAULT)
class KnoxGatewayDefault(KnoxGateway):

  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)

    # backup the data directory to /tmp/knox-upgrade-backup/knox-data-backup.tar just in case
    # something happens; Knox is interesting in that they re-generate missing files like
    # keystores which can cause side effects if the upgrade goes wrong
    if params.upgrade_direction and params.upgrade_direction == Direction.UPGRADE:
      absolute_backup_dir = upgrade.backup_data()
      Logger.info("Knox data was successfully backed up to {0}".format(absolute_backup_dir))

    stack_select.select_packages(params.version)

    # seed the new Knox data directory with the keystores of yesteryear
    if params.upgrade_direction == Direction.UPGRADE:
      upgrade.seed_current_data_directory()


  def start(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    self.configure(env)
    daemon_cmd = format('{knox_bin} start')
    no_op_test = format('ls {knox_pid_file} >/dev/null 2>&1 && ps -p `cat {knox_pid_file}` >/dev/null 2>&1')
    setup_ranger_knox(upgrade_type=upgrade_type)
    # Used to setup symlink, needed to update the knox managed symlink, in case of custom locations
    if os.path.islink(params.knox_managed_pid_symlink):
      Link(params.knox_managed_pid_symlink,
           to = params.knox_pid_dir,
      )

    update_knox_logfolder_permissions()

    # gateway.sh start (TEST_APP_STATUS=true) confirms readiness by polling
    # $KNOX_GATEWAY_DATA_DIR/gatewayServer.status for "STARTED", defaulting KNOX_GATEWAY_DATA_DIR to
    # $KNOX_HOME/data when unset. The gateway writes that status file under gateway-site's
    # gateway.data.dir -- pinned to /var/lib/knox/gateway/data by the CDPTOODP takeover to preserve the
    # CDP master secret, which != $KNOX_HOME/data. gateway.sh also defaults APP_LOG_DIR/APP_PID_DIR to
    # $KNOX_HOME/{logs,pids} (root-owned) unless exported. It sources bin/knox-env.sh for these, but that
    # file is rpm-owned (ships them commented out) and is overwritten on every knox rpm upgrade -- so
    # export them from here instead. Without KNOX_GATEWAY_DATA_DIR the readiness poll never finds the
    # status file and gateway.sh blocks until timeout -> the Ambari Execute hangs.
    knox_gateway_data_dir = default("/configurations/gateway-site/gateway.data.dir", params.knox_data_dir)

    try:
      Execute(daemon_cmd,
              user=params.knox_user,
              environment={'JAVA_HOME': params.java_home, 'HADOOP_CONF_DIR': '/etc/hadoop/conf',
                           'KNOX_GATEWAY_LOG_DIR': params.knox_logs_dir,
                           'KNOX_GATEWAY_PID_DIR': params.knox_pid_dir,
                           'KNOX_GATEWAY_DATA_DIR': knox_gateway_data_dir},
              not_if=no_op_test
      )
    except:
      show_logs(params.knox_logs_dir, params.knox_user)
      raise

    # Consolidate the pid file with the REAL gateway JVM. knox-functions.sh records $! at launch,
    # but its error/cleanup paths `rm -f $APP_PID_FILE` and a concurrent second invocation (e.g. a
    # port-bind race during an upgrade restart) can leave {knox_pid_dir}/gateway.pid holding a dead
    # pid while a perfectly healthy gateway keeps serving -> Ambari status = INSTALLED though Knox
    # is up (seen repeatedly on takeover+upgrade restarts). After the readiness-polled start
    # returns, rewrite the pid file from the live process list. Prefer the JVM whose environ pins
    # KNOX_GATEWAY_DATA_DIR to our data dir (discriminates a co-resident IDBroker, which runs the
    # same gateway.jar); fall back to the sole gateway.jar process of the knox user.
    Execute(format(
        "kpid=''; "
        "for p in $(pgrep -f 'bin/gateway.jar' -u {knox_user}); do "
        "  if tr '\\0' '\\n' < /proc/$p/environ 2>/dev/null | grep -q '^KNOX_GATEWAY_DATA_DIR={knox_gateway_data_dir}$'; then kpid=$p; break; fi; "
        "done; "
        "[ -z \"$kpid\" ] && [ \"$(pgrep -f 'bin/gateway.jar' -u {knox_user} | wc -l)\" = '1' ] && kpid=$(pgrep -f 'bin/gateway.jar' -u {knox_user}); "
        "if [ -n \"$kpid\" ]; then echo $kpid > {knox_pid_dir}/gateway.pid; chown {knox_user}:{knox_group} {knox_pid_dir}/gateway.pid; fi"),
        tries=3, try_sleep=5)

  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    daemon_cmd = format('{knox_bin} stop')

    update_knox_logfolder_permissions()

    try:
      Execute(daemon_cmd,
              environment={'JAVA_HOME': params.java_home},
              user=params.knox_user,
      )
    except:
      show_logs(params.knox_logs_dir, params.knox_user)
      raise
    
    File(params.knox_pid_file,
         action="delete",
    )

  def status(self, env):
    import status_params
    env.set_params(status_params)
    check_process_status(status_params.knox_pid_file)

  def startdemoldap(self, env):
    import params
    env.set_params(params)
    self.configureldap(env)
    daemon_cmd = format('{ldap_bin} start')
    no_op_test = format('ls {ldap_pid_file} >/dev/null 2>&1 && ps -p `cat {ldap_pid_file}` >/dev/null 2>&1')
    Execute(daemon_cmd,
            user=params.knox_user,
            environment={'JAVA_HOME': params.java_home},
            not_if=no_op_test
    )

  def stopdemoldap(self, env):
    import params
    env.set_params(params)
    self.configureldap(env)
    daemon_cmd = format('{ldap_bin} stop')
    Execute(daemon_cmd,
            environment={'JAVA_HOME': params.java_home},
            user=params.knox_user,
            )
    File(params.ldap_pid_file,
      action = "delete"
    )
      
  def get_log_folder(self):
    import params
    return params.knox_logs_dir
  
  def get_user(self):
    import params
    return params.knox_user

  def get_pid_files(self):
    import status_params
    return [status_params.knox_pid_file]


if __name__ == "__main__":
  KnoxGateway().execute()
