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

class OzoneDatanode(Script):
  def configure(self, env):
    import params
    env.set_params(params)
    ozone(name='ozone-datanode')

  def install(self, env):
    import params
    env.set_params(params)
    self.install_packages(env)

@OsFamilyImpl(os_family=OsFamilyImpl.DEFAULT)
class OzoneDatanodeDefault(OzoneDatanode):
  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    upgrade.prestart(env)

  def start(self, env, upgrade_type=None):
    import params
    from setup_credential_ozone import setup_credential_ozone
    env.set_params(params)
    self.configure(env) # for security
    Directory( params.ozone_dn_ratis_dir,
      owner = params.ozone_user,
      create_parents = True,
      cd_access = "a",
      mode = 0o750,
    )
    if params.dn_ssl_enabled:
      passwords =  [
        {'alias': 'ssl.server.keystore.password', 'value': format('{ozone_dn_tls_ssl_keystore_password}')},
        {'alias': 'ssl.server.keystore.keypassword', 'value': format('{ozone_dn_tls_ssl_key_password}')},
        {'alias': 'ssl.client.truststore.password', 'value': format('{ozone_dn_tls_ssl_client_truststore_password}')}
      ]
      separator = ('jceks://file')
      if params.is_hdfs_enabled:
        setup_credential_file(params.java64_home, None,
                          params.ozone_dn_credential_file_path, 'ozone', params.user_group,
                          passwords, 'ozone-datanode', separator)
      else:
        setup_credential_ozone(params.java64_home,
                      params.ozone_dn_credential_file_path, 'ozone', params.user_group,
                      passwords, 'ozone-datanode')
      file_to_chown = params.ozone_dn_credential_file_path.split(separator)[1]
      if os.path.exists(file_to_chown):
          Execute(('chown', format('{params.ozone_user}:{params.user_group}'), file_to_chown),
                  sudo=True
                  )
          Execute(('chmod', '640', file_to_chown),
                  sudo=True
                  )

    ozone_service('ozone-datanode', action = 'start')
    
  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    ozone_service('ozone-datanode', action = 'stop')

  def status(self, env):
    import params
    check_process_status(params.ozone_datanode_pid_file)

  def get_log_folder(self):
    import params
    return params.log_dir
  
  def get_user(self):
    import params
    return params.ozone_user

  def get_pid_files(self):
    import params
    return [params.ozone_datanode_pid_file]

if __name__ == "__main__":
  OzoneDatanode().execute()
