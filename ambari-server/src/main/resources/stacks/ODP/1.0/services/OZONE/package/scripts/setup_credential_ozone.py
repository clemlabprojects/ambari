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
__all__ = ["setup_credential_ozone"]

import os
import subprocess
import ambari_simplejson as json
from datetime import datetime
from resource_management.libraries.functions.ranger_functions import Rangeradmin
from resource_management.core.resources import File, Directory, Execute
from resource_management.libraries.resources.xml_config import XmlConfig
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.get_stack_version import get_stack_version
from resource_management.core.logger import Logger
from resource_management.core.source import DownloadSource, InlineTemplate
from resource_management.libraries.functions.ranger_functions_v2 import RangeradminV2
from resource_management.core.utils import PasswordString
from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.default import default

# TODO: this module should be in resource functions but is specific only for Ozone when running in place of HDFS
def setup_credential_ozone(java_home,
                        credential_path, credential_owner, credential_group,
                        passwords, component_select_name ):

  stack_root = Script.get_stack_root()
  stack_version = get_stack_version(component_select_name)
  cmd_env = {'JAVA_HOME': java_home}

  count = 0
  if passwords is None:
    Logger.info("skipping credential file creation")
  else:

    separator = ('jceks://file')
    file_to_delete = credential_path.split(separator)[1]
    Logger.info("Deleting existing provider")
    rm_cmd = ('rm', '-f', file_to_delete)
    Execute(rm_cmd, environment=cmd_env, logoutput=True, sudo=True)
    if component_select_name == 'ozone-storage-container-manager':
      classpath_name = 'hdds-server-scm'
    elif component_select_name == 'ozone-s3-gateway':
      classpath_name = 'ozone-s3gateway'
    else:
      classpath_name = component_select_name
    ozone_classpath = subprocess.check_output(['ozone', 'classpath', classpath_name]).decode('utf-8')
    for password in passwords:
      Logger.info("Adding entry \'{password.alias}\' in credential file \'{credential_path}\'")
      cmd = ('java', '-cp', ozone_classpath, 'org.apache.hadoop.security.alias.CredentialShell', 'create', password['alias'], '-value', PasswordString(password['value']), '-provider', credential_path)
      Execute(cmd, environment=cmd_env, logoutput=True, sudo=True)
      count = count+1
    Logger.info("Credential Provider file {credential_path} successfully created")



    
        
  