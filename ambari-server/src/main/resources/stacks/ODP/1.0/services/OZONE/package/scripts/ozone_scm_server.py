#!/usr/bin/python2
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

from ambari_commons import OSCheck, OSConst
from ambari_commons.os_family_impl import OsFamilyImpl
from ambari_commons.constants import SERVICE
from ozone import ozone
from ozone_service import ozone_service
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Directory, Execute, File
from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions.security_commons import build_expectations, \
  cached_kinit_executor, get_params_from_filesystem, validate_security_config_properties, \
  FILE_TYPE_XML
import sys, os
import upgrade
import time
from setup_ranger_ozone import setup_ranger_ozone



class OzoneStorageContainer(Script):
  def configure(self, env):
    import params
    env.set_params(params)
    ozone(name='ozone-scm')

  def install(self, env):
    import params
    env.set_params(params)
    self.install_packages(env)

@OsFamilyImpl(os_family=OsFamilyImpl.DEFAULT)
class OzoneStorageContainerDefault(OzoneStorageContainer):
  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    upgrade.prestart(env)

  def start(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    self.configure(env) # for security
    self.format_scm(env)
    ozone_service('ozone-scm', action = 'start')
    
  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    ozone_service('ozone-scm', action = 'stop')

  def status(self, env):
    import status_params
    env.set_params(status_params)
    check_process_status(status_params.ozone_scm_pid_file)

  def security_status(self, env):
    import status_params

    env.set_params(status_params)
    if status_params.security_enabled:
      props_value_check = {"ozone.security.enabled" : "true",
                           "ozone.acl.enabled": "true",
                           "hdds.grpc.tls.enabled": "true",
                           "hadoop.security.authentication": "kerberos"}
      props_empty_check = ['hdds.scm.kerberos.principal',
                           'hdds.scm.kerberos.keytab.file']
      props_read_check = ['hdds.scm.kerberos.keytab.file']
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
               or 'hdds.scm.kerberos.keytab.file' not in security_params['ozone-site']
               or 'hdds.scm.kerberos.principal' not in security_params['ozone-site']):
            self.put_structured_out({"securityState": "UNSECURED"})
            self.put_structured_out(
              {"securityIssuesFound": "Keytab file or principal are not set property."})
            return

          cached_kinit_executor(status_params.kinit_path_local,
                                status_params.ozone_user,
                                security_params['ozone-site']['hdds.scm.kerberos.keytab.file'],
                                security_params['ozone-site']['hdds.scm.kerberos.principal'],
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
    import status_params
    return [status_params.ozone_scm_pid_file]

## formatting SCM server directories

def is_scm_server_bootstrapped():
  import params
  bootstrapped_path = os.path.join(params.ozone_scm_db_dirs, '/current/VERSION')
  if params.ozone_scm_format_disabled:
    Logger.info("ozone_scm_format_disabled is disabled in cluster-env configuration, Skipping")
    return True
  else:
    return os.path.exists(bootstrapped_path)

def is_scm_ha_enabled():
  import params
  return params.ozone_scm_ha_enabled

def get_primordial_node_id():
  import params
  return params.ozone_scm_primordial_node_id

def is_scm_server_primordial_node_id():
  import params
  return params.hostname == get_primordial_node_id()

def format_scm(force=None):
  import params
  if params.ozone_scm_ha_enabled:
    Logger.info(format("Ozone SCM Server HA is enabled. Running bootstrapping actions..."))
    if is_scm_server_bootstrapped():
      Logger.info(format("Ozone SCM Server {params.hostname} is already bootstrapped. Skipping..."))
    else:
      Logger.info(format("Ozone SCM Server {params.hostname} is not bootstrapped. Running bootstrap procedure"))
      Logger.info(format("Checking if current Ozone SCM Server is primordial node"))
      if is_scm_server_primordial_node_id():
        Logger.info(format("Ozone SCM Server {params.hostname} is the primordial node. Initializing..."))
        try:
          Execute(format("ozone scm --init"),
            user = params.ozone_user,
            path = [params.hadoop_ozone_bin_dir],
            logoutput=True
          )
        except Fail:
          # We need to clean-up directories
          for scm_db_dir in params.ozone_scm_db_dirs.split(','):
            Execute(format("rm -rf {nn_name_dir}/*"),
                    user = params.ozone_user,
            )
          raise Fail('Could Not Initialize Primordial Node')
      else:
        Logger.info(format("Ozone SCM Server {params.hostname} is not the primordial node. Waiting for primordial node to be started..."))
        # waiting primordial node ratis port to be ready 
        wait_for_primary_node_to_started()
        # bootstrapp not primary node
        try:
          Logger.info(format("Bootstrapping scm node..."))
          Execute(format("ozone scm --bootstrap"),
            user = params.ozone_user,
            path = [params.hadoop_ozone_bin_dir],
            logoutput=True
          )
        except Fail:
          for scm_db_dir in params.ozone_scm_db_dirs.split(','):
            Execute(format("rm -rf {nn_name_dir}/*"),
                    user = params.ozone_user,
            )
          raise Fail('Could not bootstrap scm node')

  else:
    Logger.info(format("Ozone SCM Server HA is not enabled. Running bootstrap actions..."))
    if is_scm_server_bootstrapped():
      Logger.info(format("Ozone SCM Server {params.hostname} is already bootstrapped. Skipping..."))
    else:
      Logger.info(format("Ozone SCM Server {params.hostname} is the primordial node. Initializing..."))
      try:
        Execute(format("ozone scm --init"),
          user = params.ozone_user,
          path = [params.hadoop_ozone_bin_dir],
          logoutput=True
        )
      except Fail:
        # We need to clean-up directories
        for scm_db_dir in params.ozone_scm_db_dirs.split(','):
          Execute(format("rm -rf {nn_name_dir}/*"),
                  user = params.ozone_user,
          )
        raise Fail('Could not initial primary scm node')

def wait_for_primary_node_to_started(ozone_binary, afterwait_sleep=0, execute_kinit=False, retries=115, sleep_seconds=10):
  """
  Wait for the primary scm server to be up and running on its ratis port 5when HA is enabled)
  """
  import params
  if not params.ozone_scm_ha_enabled:
    Logger.info("Skipping waiting for primordial node")
    return
  else:
    sleep_minutes = int(sleep_seconds * retries / 60)
    #Logger.info("Waiting up to {0} minutes for the SCM Server to leave Safemode...".format(sleep_minutes))
    if params.security_enabled and execute_kinit:
      kinit_command = format("{params.kinit_path_local} -kt {params.ozone_scm_user_keytab} {params.ozone_scm_principal_name}")
      Execute(kinit_command, user=params.ozone_user, logoutput=True)
    try:
      Execute(format("ozone admin scm roles --scm {params.hostname}:{params.ozone_scm_ha_ratis_port}"),
        user = params.ozone_user,
        path = [params.hadoop_ozone_bin_dir],
        logoutput=True
      )
      time.sleep(afterwait_sleep)
    except Fail:
      Logger.error("The Primordial SCM Server is still down. Waiting....")

def wait_ozone_scm_safemode(ozone_binary, afterwait_sleep=0, execute_kinit=False, retries=115, sleep_seconds=10):
  """
  Wait for Safe Mode Off on SCM Servers
  Instead of looping on test safe mode, we use the included ozone command wait safemode using timeout parameters.
  """
  import params
  #Logger.info("Waiting up to {0} minutes for the SCM Server to leave Safemode...".format(sleep_minutes))
  if params.security_enabled and execute_kinit:
    kinit_command = format("{params.kinit_path_local} -kt {params.ozone_scm_user_keytab} {params.ozone_scm_principal_name}")
    Execute(kinit_command, user=params.ozone_user, logoutput=True)
  timeout = retries*(sleep_seconds+afterwait_sleep)

  try:
    Execute(format("ozone admin safemode wait --timeout {timeout}"),
      user = params.ozone_user,
      path = [params.hadoop_ozone_bin_dir],
      logoutput=True
    )
    time.sleep(afterwait_sleep)
  except Fail:
    Logger.error("Ozone SCM did not leave Safe Mode after {timeout} seconds. Exiting")

if __name__ == "__main__":
  OzoneStorageContainer().execute()
