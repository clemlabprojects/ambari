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
import os
from ambari_commons.constants import SERVICE
from resource_management.core.logger import Logger


class OzoneS3Gateway(Script):
  def configure(self, env):
    import params
    env.set_params(params)
    ozone(name='ozone-s3g')

  def install(self, env):
    import params
    env.set_params(params)
    self.install_packages(env)

@OsFamilyImpl(os_family=OsFamilyImpl.DEFAULT)
class OzoneS3GatewayDefault(OzoneS3Gateway):
  def pre_upgrade_restart(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    upgrade.prestart(env)

  def start(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    self.configure(env) # for security
    ozone_service('ozone-s3g', action = 'start')
    
  def stop(self, env, upgrade_type=None):
    import params
    env.set_params(params)
    ozone_service('ozone-s3g', action = 'stop')

  def status(self, env):
    import status_params
    import params
    env.set_params(status_params)
    check_process_status(params.ozone_s3g_pid_file)

  def get_log_folder(self):
    import params
    return params.log_dir
  
  def get_user(self):
    import params
    return params.ozone_user

  def get_pid_files(self):
    import params
    return [params.ozone_s3g_pid_file]

if __name__ == "__main__":
  OzoneS3Gateway().execute()
