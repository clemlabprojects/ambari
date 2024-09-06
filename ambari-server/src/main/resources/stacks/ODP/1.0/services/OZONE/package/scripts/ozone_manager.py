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

import sys
from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions.security_commons import build_expectations, \
  cached_kinit_executor, get_params_from_filesystem, validate_security_config_properties, \
  FILE_TYPE_XML
from ozone import ozone
from ozone_service import ozone_service
import upgrade
from setup_ranger_ozone import setup_ranger_ozone
from ambari_commons import OSCheck, OSConst
from ambari_commons.os_family_impl import OsFamilyImpl
from ambari_commons.constants import SERVICE
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Directory, Execute, File
from resource_management.libraries.functions.setup_credential_file import setup_credential_file
import sys, os
import upgrade
import time

class OzoneManager(Script):
  def configure(self, env):
    import params
    env.set_params(params)
    ozone(name='ozone-manager')

  def install(self, env):
    import params
    env.set_params(params)
    self.install_packages(env)


@OsFamilyImpl(os_family=OsFamilyImpl.DEFAULT)
class OzoneManagerDefault(OzoneManager):
  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    upgrade.prestart(env)
    

  def start(self, env, upgrade_type=None):
    import params
    from setup_credential_ozone import setup_credential_ozone
    env.set_params(params)
    self.configure(env) # for security
    bootstrap_server(env)
    ## creating ssl keystore credential file if needed
    if params.om_ssl_enabled:
      passwords =  [ 
        {'alias': 'ssl.server.keystore.password', 'value': format('{ozone_om_tls_ssl_keystore_password}')},
        {'alias': 'ssl.server.keystore.keypassword', 'value': format('{ozone_om_tls_ssl_key_password}')},
        {'alias': 'ssl.client.truststore.password', 'value': format('{ozone_om_tls_ssl_client_truststore_password}')}
      ]
      separator = ('jceks://file')
      if params.is_hdfs_enabled:
        setup_credential_file(params.java64_home, None,
                          params.ozone_om_credential_file_path, 'ozone', params.user_group,
                          passwords, 'ozone-manager', separator )
      else:
        setup_credential_ozone(params.java64_home,
                      params.ozone_om_credential_file_path, 'ozone', params.user_group,
                      passwords, 'ozone-manager' )

      file_to_chown = params.ozone_om_credential_file_path.split(separator)[1]
      if os.path.exists(file_to_chown):
          Execute(('chown', format('{params.ozone_user}:{params.user_group}'), file_to_chown),
                  sudo=True
                  )
          Execute(('chmod', '640', file_to_chown),
                  sudo=True
                  )

    setup_ranger_ozone(upgrade_type=upgrade_type, service_name="ozone-manager")
    ozone_service('ozone-manager', action = 'start')
    
  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    ozone_service('ozone-manager', action = 'stop')

  def status(self, env):
    import params
    check_process_status(params.ozone_manager_pid_file)

  def security_status(self, env):
    import status_params
    import params
    conf_dir = os.path.join(params.ozone_base_conf_dir, params.ROLE_NAME_MAP_CONF['ozone-manager'])
    env.set_params(status_params)
    if status_params.security_enabled:
      props_value_check = {"ozone.security.enabled" : "true",
                           "ozone.acl.enabled": "true",
                           "hadoop.security.authentication": "kerberos"}
      props_empty_check = ['ozone.om.kerberos.principal',
                           'ozone.om.kerberos.keytab.file']
      props_read_check = ['ozone.om.kerberos.keytab.file']
      ozone_site_expectations = build_expectations('ozone-site', props_value_check, props_empty_check,
                                                  props_read_check)

      ozone_expectations = {}
      ozone_expectations.update(ozone_site_expectations)

      security_params = get_params_from_filesystem("{}/ozone.om/".format(status_params.ozone_conf_dir),
                                                   {'ozone-site.xml': FILE_TYPE_XML})
      result_issues = validate_security_config_properties(security_params, ozone_site_expectations)
      if not result_issues:  # If all validations passed successfully
        try:
          # Double check the dict before calling execute
          if ( 'ozone-site' not in security_params
               or 'ozone.om.kerberos.keytab.file' not in security_params['ozone-site']
               or 'ozone.om.kerberos.principal' not in security_params['ozone-site']):
            self.put_structured_out({"securityState": "UNSECURED"})
            self.put_structured_out(
              {"securityIssuesFound": "Keytab file or principal are not set property."})
            return

          cached_kinit_executor(status_params.kinit_path_local,
                                status_params.ozone_user,
                                security_params['ozone-site']['ozone.om.kerberos.keytab.file'],
                                security_params['ozone-site']['ozone.om.kerberos.principal'],
                                status_params.hostname,
                                status_params.tmp_dir)
          self.put_structured_out({"securityState": "SECURED_KERBEROS"})
        except Exception as e:
          self.put_structured_out({"securityState": "ERROR"})
          self.put_structured_out({"securityStateErrorInfo": str(e)})
      else:
        issues = []
        for cf in result_issues:
          issues.append("Configuration file %s did not pass the validation. Reason: %s" % (cf, result_issues[cf]))
        self.put_structured_out({"securityIssuesFound": ". ".join(issues)})
        self.put_structured_out({"securityState": "UNSECURED"})
    else:
      self.put_structured_out({"securityState": "UNSECURED"})
      
  def get_log_folder(self):
    import params
    return params.log_dir
  
  def get_user(self):
    import params
    return params.ozone_user

  def get_pid_files(self):
    import params
    return [params.ozone_manager_pid_file]

def prepareOzoneLayout(dirs):
  import params
  # Retrieve UID and GID if user and group are provided
  uid = pwd.getpwnam(params.ozone_user).pw_uid if isinstance(params.ozone_user, str) else params.ozone_user
  gid = grp.getgrnam(params.user_group).gr_gid if isinstance(params.user_group, str) else params.user_group
  if isinstance(dirs, list):
    to_create = dirs
  else:
    to_create = [dirs]
  for scm_path in to_create:
    Logger.info(format("Creating dir {scm_path}"))
    os.makedirs(scm_path, exist_ok=True)
    os.chmod(scm_path, 0o754)
    os.chown(scm_path, uid, gid)  # Change ownership


## formatting Ozone Manager server directories
def om_server_is_bootstrapped():
  import params
  bootstrapped_path = format("{params.ozone_manager_db_dirs}/om/current/VERSION")
  Logger.info(format("Checking if OM VERSION file exists at {bootstrapped_path}"))
  return os.path.exists(bootstrapped_path)

def wait_for_om_leader_to_be_active(ozone_binary='ozone', afterwait_sleep=0, execute_kinit=False, retries=115, sleep_seconds=10):
  import params
  """
  Wait for the primary om server to be up and running on its ratis port when HA is enabled)
  """
  cmd_env = {'JAVA_HOME': params.java_home }
  conf_dir = os.path.join(params.ozone_base_conf_dir, params.ROLE_NAME_MAP_CONF['ozone-manager'])
  if not params.ozone_om_ha_is_enabled:
    Logger.info("Skipping waiting for primordial node")
    return
  else:
    sleep_minutes = int(sleep_seconds * retries / 60)
    if params.security_enabled and execute_kinit:
      kinit_command = format("{params.kinit_path_local} -kt {params.ozone_om_user_keytab} {params.ozone_om_principal_name}")
      Execute(kinit_command, user=params.ozone_user, logoutput=True)
    while True:
      active_om = next(iter(params.ozone_ha_om_active))
      try:
        Execute(format("ozone --config {conf_dir} admin om getserviceroles -id {params.ozone_om_ha_current_cluster_nameservice} | grep '{active_om}' | grep LEADER"),
          user = params.ozone_user,
          environment = cmd_env,
          path = params.hadoop_ozone_bin_dir,
          logoutput=True
        )
        break
      except Fail:
        Logger.error("The Primordial SCM Server is still down. Waiting....")
        time.sleep(afterwait_sleep)
  return

def wait_for_om_leader_to_be_started(ozone_binary='ozone', afterwait_sleep=0, execute_kinit=False, retries=115, sleep_seconds=10):
  import params
  """
  Wait for the primary om server to be up and running on its ratis port when HA is enabled)
  """
  cmd_env = {'JAVA_HOME': params.java_home }
  conf_dir = os.path.join(params.ozone_base_conf_dir, params.ROLE_NAME_MAP_CONF['ozone-manager'])
  if not params.ozone_om_ha_is_enabled:
    Logger.info("Skipping waiting for primordial node")
    return
  else:
    sleep_minutes = int(sleep_seconds * retries / 60)
    if params.security_enabled and execute_kinit:
      kinit_command = format("{params.kinit_path_local} -kt {params.ozone_om_user_keytab} {params.ozone_om_principal_name}")
      Execute(kinit_command, user=params.ozone_user, logoutput=True)
    while True:
      active_om = next(iter(params.ozone_ha_om_active))
      try:
        Execute(format("echo > /dev/tcp/{active_om}/{ozone_om_ratis_port}"),
          user = params.ozone_user,
          environment = cmd_env,
          path = params.hadoop_ozone_bin_dir,
          logoutput=True
        )
        time.sleep(afterwait_sleep)
        break
      except Fail:
        Logger.error("The Primordial Ozone Manager is still down. Waiting....")
        
  return

def wait_ozone_scm_safemode(ozone_binary, afterwait_sleep=0, execute_kinit=False, retries=115, sleep_seconds=10):
  """
  Wait for Safe Mode Off on SCM Servers
  Instead of looping on test safe mode, we use the included ozone command wait safemode using timeout parameters.
  """
  import params
  cmd_env = {'JAVA_HOME': params.java_home }
  conf_dir = os.path.join(params.ozone_base_conf_dir, params.ROLE_NAME_MAP_CONF['ozone-manager'])
  #Logger.info("Waiting up to {0} minutes for the SCM Server to leave Safemode...".format(sleep_minutes))
  if params.security_enabled and execute_kinit:
    kinit_command = format("{params.kinit_path_local} -kt {params.ozone_scm_user_keytab} {params.ozone_scm_principal_name}")
    Execute(kinit_command, user=params.ozone_user, logoutput=True)
  timeout = retries*(sleep_seconds+afterwait_sleep)

  try:
    Execute(format("ozone --config {conf_dir} admin safemode wait --timeout {timeout}"),
      user = params.ozone_user,
      environment = cmd_env,
      path = params.hadoop_ozone_bin_dir,
      logoutput=True
    )
    time.sleep(afterwait_sleep)
  except Fail:
    Logger.error("Ozone SCM did not leave Safe Mode after {timeout} seconds. Exiting")

def bootstrap_server(env=None):
  import params
  cmd_env = {'JAVA_HOME': params.java_home }
  Directory( params.ozone_manager_db_dirs,
      owner = params.ozone_user,
      create_parents = True,
      cd_access = "a",
      mode = 0o755,
  )
  Directory( params.ozone_manager_ha_dirs,
      owner = params.ozone_user,
      create_parents = True,
      cd_access = "a",
      mode = 0o755,
  )
  Directory( params.ozone_om_snapshot_dirs,
      owner = params.ozone_user,
      create_parents = True,
      cd_access = "a",
      mode = 0o755,
  )
  
  conf_dir = os.path.join(params.ozone_base_conf_dir, params.ROLE_NAME_MAP_CONF['ozone-manager'])
  if om_server_is_bootstrapped():
    Logger.info("Ozone OM is already initialized. Skipping")
    return None
  else:
    if params.ozone_om_ha_is_enabled:
      if params.hostname.lower() in params.ozone_ha_om_active:
        Logger.info("Ozone Manager HA is enabled")
        Logger.info("Waiting for Ozone SCM primordial node before initializing om. Waiting...")
        wait_ozone_scm_safemode(params.ozone_cmd)
        Logger.info("Ozone OM is the first leader. Initializing")
         # bootstrapp the primary node
        try:
          Logger.info(format("Bootstrapping om leader node..."))
          Execute(format("ozone --config {conf_dir} om --init"),
            user = params.ozone_user,
            environment = cmd_env,
            path = params.hadoop_ozone_bin_dir,
            logoutput=True
          )
        except Fail:
          # for now disable cleanup
          # for om_db_dir in params.ozone_manager_db_dirs.split(','):

          #   Execute(format("rm -rf {om_db_dir}/om*"),
          #           user = params.ozone_user,
          #   )
          raise Fail('Could not bootstrap om node')
      else:
        Logger.info("Ozone OM is not the first leader. Waiting for leader to be active")
        wait_for_om_leader_to_be_started(env)
        # bootstrapp not primary node
        try:
          Logger.info(format("Bootstrapping om node..."))
          # In Ozone 1.4.0 no need to bootstrap the high available Ozone Manager
          Execute(format("ozone --config {conf_dir} om --init"),
            user = params.ozone_user,
            environment= cmd_env ,
            path = params.hadoop_ozone_bin_dir,
            logoutput=True
          )
        except Fail:
          # for now disable cleanup
          # for om_db_dir in params.ozone_manager_db_dirs.split(','):
          #   Execute(format("rm -rf {om_db_dir}/om*"),
          #           user = params.ozone_user,
          #   )
          raise Fail('Could not bootstrap om node')
    else:
      Logger.info("Ozone Manager HA is disabled")
      Logger.info("Waiting for Ozone SCM primordial node before initializing om. Waiting...")
      wait_ozone_scm_safemode(params.ozone_cmd)
      try:
        Logger.info(format("Bootstrapping om node..."))
        Execute(format("ozone --config {conf_dir} om --init"),
          user = params.ozone_user,
          environment = cmd_env,
          path = params.hadoop_ozone_bin_dir,
          logoutput=True
        )
      except Fail:
        # for now disable cleanup
        # for om_db_dir in params.ozone_manager_db_dirs.split(','):
        #   Execute(format("rm -rf {om_db_dir}/om*"),
        #           user = params.ozone_user,
        #   )
        raise Fail('Could not bootstrap om node')

def wait_scm_server_started():
  import params
  return True

if __name__ == "__main__":
  OzoneManager().execute()
