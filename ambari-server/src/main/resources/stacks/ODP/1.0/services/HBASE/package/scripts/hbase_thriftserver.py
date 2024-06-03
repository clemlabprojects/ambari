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
from hbase import hbase
from hbase_service import hbase_service
from hbase_decommission import hbase_decommission
import upgrade
from setup_ranger_hbase import setup_ranger_hbase
from ambari_commons import OSCheck, OSConst
from ambari_commons.os_family_impl import OsFamilyImpl
import os
from resource_management.libraries.functions.setup_atlas_hook import has_atlas_in_cluster, setup_atlas_hook
from ambari_commons.constants import SERVICE
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Execute
from resource_management.libraries.functions.setup_credential_file import setup_credential_file



class HbaseThriftServer(Script):
  def configure(self, env):
    import params
    env.set_params(params)
    hbase(name='thrift')

  def install(self, env):
    import params
    env.set_params(params)
    self.install_packages(env)

  def decommission(self, env):
    import params
    env.set_params(params)
    hbase_decommission(env)


@OsFamilyImpl(os_family=OSConst.WINSRV_FAMILY)
class HbaseThriftServerWindows(HbaseThriftServer):
  def start(self, env):
    import status_params
    self.configure(env)
    Service(status_params.hbase_thrift_win_service_name, action="start")

  def stop(self, env):
    import status_params
    env.set_params(status_params)
    Service(status_params.hbase_thrift_win_service_name, action="stop")

  def status(self, env):
    import status_params
    env.set_params(status_params)
    check_windows_service_status(status_params.hbase_thrift_win_service_name)



@OsFamilyImpl(os_family=OsFamilyImpl.DEFAULT)
class HbaseThriftServerDefault(HbaseThriftServer):
  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    upgrade.prestart(env)

  def start(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    self.configure(env) # for security
    if params.hbase_thrift_stack_enabled:
      if params.thrift_ssl_enabled:
        Logger.info("TLS/SSL is enabled on HBase Thrift Server. Setting up keystore")
        ## creating ssl keystore credential file if needed
        if params.thrift_ssl_enabled is not None and params.thrift_ssl_enabled:
          passwords =  [ {'alias': 'hbase.thrift.ssl.keystore.password', 'value': format('{thrift_ssl_keystore_password}')},
            {'alias': 'hbase.thrift.ssl.keystore.keypassword', 'value': format('{thrift_ssl_keystore_keypassword}')}
          ]
          separator = ('localjceks://file')
          setup_credential_file(params.java64_home, None,
                            params.hbase_thrift_creds_file, 'hbase', params.user_group,
                            passwords, 'hbase-thrift', separator )
          file_to_chown = params.hbase_thrift_creds_file.split(separator)[1]
          if os.path.exists(file_to_chown):
              Execute(('chown', format('{params.hbase_user}:{params.user_group}'), file_to_chown),
                      sudo=True
                      )
              Execute(('chmod', '640', file_to_chown),
                      sudo=True
                      )
    else:
      Logger.info("TLS/SSL is disabled on HBase Thrift Server. Skipping")
    hbase_service('thrift', action = 'start')
    
  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    hbase_service('thrift', action = 'stop')

  def status(self, env):
    import status_params
    env.set_params(status_params)

    check_process_status(status_params.hbase_thrift_pid_file)

  def security_status(self, env):
    import status_params

    env.set_params(status_params)
    if status_params.security_enabled:
      props_value_check = {"hbase.security.authentication" : "kerberos",
                           "hbase.security.authorization": "true"}
      props_empty_check = ['hbase.thrift.keytab.file',
                           'hbase.thrift.kerberos.principal']
      props_read_check = ['hbase.thrift.keytab.file']
      hbase_site_expectations = build_expectations('hbase-site', props_value_check, props_empty_check,
                                                  props_read_check)

      hbase_expectations = {}
      hbase_expectations.update(hbase_site_expectations)

      security_params = get_params_from_filesystem(status_params.hbase_conf_dir,
                                                   {'hbase-site.xml': FILE_TYPE_XML})
      result_issues = validate_security_config_properties(security_params, hbase_expectations)
      if not result_issues:  # If all validations passed successfully
        try:
          # Double check the dict before calling execute
          if ( 'hbase-site' not in security_params
               or 'hbase.thrift.keytab.file' not in security_params['hbase-site']
               or 'hbase.thrift.kerberos.principal' not in security_params['hbase-site']):
            self.put_structured_out({"securityState": "UNSECURED"})
            self.put_structured_out(
              {"securityIssuesFound": "Keytab file or principal are not set property."})
            return

          cached_kinit_executor(status_params.kinit_path_local,
                                status_params.hbase_user,
                                security_params['hbase-site']['hbase.thrift.keytab.file'],
                                security_params['hbase-site']['hbase.thrift.kerberos.principal'],
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
    return params.hbase_user

  def get_pid_files(self):
    import status_params
    return [status_params.hbase_thrift_pid_file]

if __name__ == "__main__":
  HbaseThriftServer().execute()
