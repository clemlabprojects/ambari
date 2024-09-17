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

from ambari_commons import OSCheck, OSConst
from ambari_commons.os_family_impl import OsFamilyImpl
from ambari_commons.constants import SERVICE
from ozone import ozone
from ozone_service import ozone_service
from resource_management.core.exceptions import Fail
from resource_management.core.source import Template, InlineTemplate
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Directory, Execute, File
from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions.security_commons import build_expectations, \
  cached_kinit_executor, get_params_from_filesystem, validate_security_config_properties, \
  FILE_TYPE_XML
import sys, os
import pwd
import grp
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
    from setup_credential_ozone import setup_credential_ozone
    self.configure(env) # for security
    File(os.path.join(params.ozone_topology_file),
        owner='root',
        group='root',
        mode=0o755,
        content=Template("ozone_topology_script.py")
    )
    if params.scm_ssl_enabled:
      passwords =  [
        {'alias': 'ssl.server.keystore.password', 'value': format('{ozone_scm_tls_ssl_keystore_password}')},
        {'alias': 'ssl.server.keystore.keypassword', 'value': format('{ozone_scm_tls_ssl_key_password}')},
        {'alias': 'ssl.client.truststore.password', 'value': format('{ozone_scm_tls_ssl_client_truststore_password}')}
      ]
      separator = ('jceks://file')
      if params.is_hdfs_enabled:
        setup_credential_file(params.java64_home, None,
                          params.ozone_scm_credential_file_path, 'ozone', params.user_group,
                          passwords, 'ozone-scm', separator )
      else:
        setup_credential_ozone(params.java64_home,
                      params.ozone_scm_credential_file_path, 'ozone', params.user_group,
                      passwords, 'ozone-storage-container-manager' )

      file_to_chown = params.ozone_scm_credential_file_path.split(separator)[1]
      if os.path.exists(file_to_chown):
          Execute(('chown', format('{params.ozone_user}:{params.user_group}'), file_to_chown),
                  sudo=True
                  )
          Execute(('chmod', '640', file_to_chown),
                  sudo=True
                  )

    format_scm(env)
    ozone_service('ozone-scm', action = 'start')
    
  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    ozone_service('ozone-scm', action = 'stop')

  def status(self, env):
    import params
    check_process_status(params.ozone_scm_pid_file)

  def security_status(self, env):
    import status_params

    env.set_params(status_params)
    if status_params.security_enabled:
      props_value_check = {"ozone --config {conf_dir}.security.enabled" : "true",
                           "ozone --config {conf_dir}.acl.enabled": "true",
                           "hdds.grpc.tls.enabled": "true",
                           "hadoop.security.authentication": "kerberos"}
      props_empty_check = ['hdds.scm.kerberos.principal',
                           'hdds.scm.kerberos.keytab.file']
      props_read_check = ['hdds.scm.kerberos.keytab.file']
      ozone_site_expectations = build_expectations('ozone-site', props_value_check, props_empty_check,
                                                  props_read_check)

      ozone_expectations = {}
      ozone_expectations.update(ozone_site_expectations)

      security_params = get_params_from_filesystem("{}/ozone.scm/".format(status_params.ozone_conf_dir),
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
    import params
    return [params.ozone_scm_pid_file]

## formatting SCM server directories

def is_scm_server_bootstrapped():
  import params
  bootstrapped_path = format("{params.ozone_scm_db_dirs}/scm/current/VERSION")
  Logger.info(format("Checking if SCM VERSION file exists at {bootstrapped_path}"))
  if params.ozone_scm_format_disabled:
    Logger.info("ozone_scm_format_disabled is disabled in cluster-env configuration, Skipping")
    return True
  else:
    return os.path.exists(bootstrapped_path)

def is_scm_ha_autoca_bootstrapped():
  import params
  exists = False
  certpaths = []

  for certpath in [
    format("{params.ozone_scm_hdds_metadata_dir}/scm/sub-ca/{params.ozone_scm_hdds_x509_dir}"),
    format("{params.ozone_scm_hdds_metadata_dir}/scm/ca/{params.ozone_scm_hdds_x509_dir}")]:
    bootstrapped_path = certpath
    Logger.info(format("Checking if SCM HA CA cert dir exists at {bootstrapped_path}"))
    exists = (exists and os.path.exists(bootstrapped_path))
  return exists

def is_scm_ha_enabled():
  import params
  return params.ozone_scm_ha_enabled

def get_primordial_node_id():
  import params
  return params.ozone_scm_primordial_node_id

def is_scm_server_primordial_node_id():
  import params
  return params.hostname == get_primordial_node_id()

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

def format_scm(force=None):
  import params
  conf_dir = os.path.join(params.ozone_base_conf_dir, params.ROLE_NAME_MAP_CONF['ozone-scm'])
  cmd_env = {'JAVA_HOME': params.java_home }
  prepareOzoneLayout(params.ozone_scm_db_dirs)
  prepareOzoneLayout(params.ozone_scm_metadata_dir)
  prepareOzoneLayout(params.ozone_scm_hdds_metadata_dir)
  if params.ozone_scm_ha_enabled:
    # format dir ozone_scm_ha_dirs
    prepareOzoneLayout(params.ozone_scm_ha_dirs)
    Logger.info(format("Ozone SCM Server HA is enabled. Running bootstrapping actions..."))
    if is_scm_server_bootstrapped():
      Logger.info(format("Ozone SCM Server {params.hostname} is already bootstrapped."))
      ## when Ozone SCM Server is bootstrapped and Kerberos + TLS is enabled we need to init/bootstrap again according to
      # hadoop-hdds/framework/src/main/java/org/apache/hadoop/hdds/security/x509/certificate/authority/DefaultCAServer.java#456
      # if security enabled and mTLS is enabled (internal encryption)
      if False:
      # if params.ozone_security_enabled and params.ozone_scm_ha_tls_enabled:
        Logger.info(format("Ozone SCM HA is enabled checking if certs need to be generated"))
        initcmd = format("ozone --config {conf_dir} ") + "scm {arg}".format(arg="--init" if is_scm_server_primordial_node_id() else "--bootstrap --force")
        if is_scm_ha_autoca_bootstrapped:
          if not is_scm_server_primordial_node_id:
            wait_for_primary_node_to_started()
          else:
            try:
              Execute(initcmd,
                user = params.ozone_user,
                path = [params.hadoop_ozone_bin_dir],
                environment = cmd_env,
                logoutput=True
              )
            except Fail:
              raise Fail('Could Not Initialize SCM HA SSL/TLS Primordial Node')
      else:
        Logger.info(format("Ozone SCM HA is disabled. Skipping"))
    else:
      Logger.info(format("Ozone SCM Server {params.hostname} is not bootstrapped. Running bootstrap procedure"))
      Logger.info(format("Checking if current Ozone SCM Server is primordial node"))
      if is_scm_server_primordial_node_id():
        Logger.info(format("Ozone SCM Server {params.hostname} is the primordial node. Initializing..."))
        try:
          Execute(format("ozone --config {conf_dir} scm --init"),
            user = params.ozone_user,
            path = [params.hadoop_ozone_bin_dir],
            environment = cmd_env,
            logoutput=True
          )
        except Fail:
          # for now disable cleanup of directories
          # We need to clean-up directories
          # for scm_db_dir in params.ozone_scm_db_dirs.split(','):
          #   Execute(format("rm -rf {scm_db_dir}/*"),
          #           user = params.ozone_user,
          #   )
          raise Fail('Could Not Initialize Primordial Node')
      else:
        Logger.info(format("Ozone SCM Server {params.hostname} is not the primordial node. Waiting for primordial node to be started..."))
        # waiting primordial node ratis port to be ready 
        wait_for_primary_node_to_started()
        # bootstrapp not primary node
        try:
          Logger.info(format("Bootstrapping scm node..."))
          Execute(format("ozone --config {conf_dir} scm --bootstrap"),
            user = params.ozone_user,
            path = [params.hadoop_ozone_bin_dir],
            environment = cmd_env,
            logoutput=True
          )
        except Fail:
          for scm_db_dir in params.ozone_scm_db_dirs.split(','):
            Execute(format("rm -rf {scm_db_dir}/*"),
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
        Execute(format("ozone --config {conf_dir} scm --init"),
          user = params.ozone_user,
          path = [params.hadoop_ozone_bin_dir],
          environment = cmd_env,
          logoutput=True
        )
      except Fail:
        # We need to clean-up directories
        for scm_db_dir in params.ozone_scm_db_dirs.split(','):
          Execute(format("rm -rf {scm_db_dir}/*"),
                  user = params.ozone_user,
          )
        raise Fail('Could not initial primary scm node')

def wait_for_primary_node_to_started(ozone_binary='ozone', afterwait_sleep=0, execute_kinit=False, retries=115, sleep_seconds=10):
  """
  Wait for the primary scm server to be up and running on its ratis port when HA is enabled)
  """
  import params
  cmd_env = {'JAVA_HOME': params.java_home }
  conf_dir = os.path.join(params.ozone_base_conf_dir, params.ROLE_NAME_MAP_CONF['ozone-scm'])
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
      Execute(format("ozone --config {conf_dir} admin scm roles --scm {params.hostname}:{params.ozone_scm_ha_ratis_port}"),
        user = params.ozone_user,
        path = [params.hadoop_ozone_bin_dir],
        environment = cmd_env,
        logoutput=True
      )
      time.sleep(afterwait_sleep)
    except Fail:
      Logger.error("The Primordial SCM Server is still down. Waiting....")

def wait_ozone_scm_safemode(ozone_binary='ozone', afterwait_sleep=0, execute_kinit=False, retries=115, sleep_seconds=10):
  """
  Wait for Safe Mode Off on SCM Servers
  Instead of looping on test safe mode, we use the included ozone command wait safemode using timeout parameters.
  """
  import params
  cmd_env = {'JAVA_HOME': params.java_home }
  conf_dir = os.path.join(params.ozone_base_conf_dir, params.ROLE_NAME_MAP_CONF['ozone-scm'])
  #Logger.info("Waiting up to {0} minutes for the SCM Server to leave Safemode...".format(sleep_minutes))
  if params.security_enabled and execute_kinit:
    kinit_command = format("{params.kinit_path_local} -kt {params.ozone_scm_user_keytab} {params.ozone_scm_principal_name}")
    Execute(kinit_command, user=params.ozone_user, logoutput=True)
  timeout = retries*(sleep_seconds+afterwait_sleep)

  try:
    Execute(format("ozone --config {conf_dir} admin safemode wait --timeout {timeout}"),
      user = params.ozone_user,
      path = [params.hadoop_ozone_bin_dir],
      environment = cmd_env,
      logoutput=True
    )
    time.sleep(afterwait_sleep)
  except Fail:
    Logger.error("Ozone SCM did not leave Safe Mode after {timeout} seconds. Exiting")

if __name__ == "__main__":
  OzoneStorageContainer().execute()
