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

class OzoneHTTPFS(Script):
  def configure(self, env):
    import params
    env.set_params(params)
    ozone(name='ozone-httpfs')

  def install(self, env):
    import params
    env.set_params(params)
    self.install_packages(env)

@OsFamilyImpl(os_family=OsFamilyImpl.DEFAULT)
class OzoneHTTPFSDefault(OzoneHTTPFS):
  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    upgrade.prestart(env)

  def start(self, env, upgrade_type=None):
    import params
    from setup_credential_ozone import setup_credential_ozone
    env.set_params(params)
    self.configure(env) # for security
    if params.httpfs_ssl_enabled:
      passwords =  [
        {'alias': 'ssl.server.keystore.password', 'value': format('{ozone_httpfs_tls_ssl_keystore_password}')},
        {'alias': 'ssl.server.keystore.keypassword', 'value': format('{ozone_httpfs_tls_ssl_key_password}')},
        {'alias': 'ssl.client.truststore.password', 'value': format('{ozone_httpfs_tls_ssl_client_truststore_password}')}
      ]
      separator = ('jceks://file')
      if params.is_hdfs_enabled:
        setup_credential_file(params.java64_home, None,
                          params.ozone_httpfs_credential_file_path, 'ozone', params.user_group,
                          passwords, 'ozone-httpfs', separator)
      else:
        setup_credential_ozone(params.java64_home,
                      params.ozone_httpfs_credential_file_path, 'ozone', params.user_group,
                      passwords, 'ozone-httpfs')
      file_to_chown = params.ozone_httpfs_credential_file_path.split(separator)[1]
      if os.path.exists(file_to_chown):
          Execute(('chown', format('{params.ozone_user}:{params.user_group}'), file_to_chown),
                  sudo=True
                  )
          Execute(('chmod', '640', file_to_chown),
                  sudo=True
                  )

    ozone_service('ozone-httpfs', action = 'start')
    
  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    ozone_service('ozone-httpfs', action = 'stop')

  def status(self, env):
    import params
    check_process_status(params.ozone_httpfs_pid_file)

  def get_log_folder(self):
    import params
    return params.log_dir
  
  def get_user(self):
    import params
    return params.ozone_user

  def get_pid_files(self):
    import params
    return [params.ozone_httpfs_pid_file]

if __name__ == "__main__":
  OzoneHTTPFS().execute()
