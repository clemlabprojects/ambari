#!/usr/bin/env python
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
__all__ = ["setup_credential_file"]

import os
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
from resource_management.core.shell import as_user
from resource_management.core import shell

def setup_credential_file(java_home, hadoop_bin_override,
                        credential_path, credential_owner, credential_group,
                        passwords, component_select_name, separator ):

  stack_root = Script.get_stack_root()
  stack_version = get_stack_version(component_select_name)
  hadoop_bin = format('{stack_root}/{stack_version}/hadoop/bin/hadoop')
  cmd_env = {'JAVA_HOME': java_home}

  if hadoop_bin_override is not None:
    hadoop_bin = hadoop_bin_override
    Logger.info("override hadoop binary path")

  count = 0
  if passwords is None:
    Logger.info("skipping credential file creation")
  else:
    # file_to_delete = credential_path.split(separator)[1]
    # Logger.info("Deleting existing provider")
    # rm_cmd = ('rm', '-f', file_to_delete)
    # Execute(rm_cmd, environment=cmd_env, logoutput=True, sudo=True)
    # logic update to verify alias existence and using credential api to modify/update password
    for password in passwords:
      need_to_create = False
      # check that the password exists first
      Logger.info("Checking if entry \'{password.alias}\' exists in credential file \'{credential_path}\'")
      exists_cmd = format('{hadoop_bin} credential list -provider {credential_path}')
      code, output = shell.call(exists_cmd, timeout=5)
      if code == 0:
        if format('{password.alias}') in output:
          Logger.info("Entry \'{password.alias}\' exists in credential file \'{credential_path}\'")
          Logger.info("checking if it matches")
          check_match_cmd = '{0} credential check {1} -value \'{2}\' -provider {3}'.format(hadoop_bin, password['alias'], PasswordString(password['value']), credential_path)
          code, output = shell.call(check_match_cmd, timeout=5)
          if code == 0 and 'Password match success' in output:
            Logger.info("Password alias matches. Skipping...")
          elif code == 0 and 'Password match failed' in output:
            Logger.info("Password alias does not match deleting and creating. Skipping...")
            Logger.info("Deleting alias before create")
            delete_cmd = (hadoop_bin, 'credential', 'delete', password['alias'] , '-f', '-provider', credential_path)
            Execute(delete_cmd, environment=cmd_env, logoutput=True, sudo=True)
            Logger.info("Alias {password.alias} deleted")
            need_to_create = True
          else:
            need_to_create = True
        else:
          need_to_create = True
      else:
        need_to_create = True
      if need_to_create:
        Logger.info("Creating alias for entry \'{password.alias}\'")
        create_cmd = (hadoop_bin, 'credential', 'create', password['alias'], '-value', PasswordString(password['value']), '-provider', credential_path)
        Execute(create_cmd, environment=cmd_env, logoutput=True, sudo=True)
        Logger.info("Alias {password.alias} created")
