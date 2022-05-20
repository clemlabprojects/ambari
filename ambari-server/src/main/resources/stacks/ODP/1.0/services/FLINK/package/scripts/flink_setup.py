#!/usr/bin/python
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
import fileinput
import shutil
import os
import socket

from urlparse import urlparse
from resource_management.core.exceptions import ComponentIsNotRunning
from resource_management.core.logger import Logger
from resource_management.core import shell
from resource_management.core.source import Template, InlineTemplate
from resource_management.core.resources.system import Directory, File
from resource_management.libraries.functions.generate_logfeeder_input_config import generate_logfeeder_input_config
from resource_management.libraries.resources.properties_file import PropertiesFile
from resource_management.libraries.functions.version import format_stack_version
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions import lzo_utils
from resource_management.libraries.resources.xml_config import XmlConfig

def flink_setup(env, type, upgrade_type = None, action = None):
  import params
  # creates run,log dirs
  Directory([params.flink_pid_dir, params.flink_log_dir],
            owner=params.flink_user,
            group=params.user_group,
            mode=0775,
            create_parents = True
  )
  if (type == 'server') or ( type == 'rest' ) and action == 'config':
    params.HdfsResource(params.flink_hdfs_user_dir,
                       type="directory",
                       action="create_on_execute",
                       owner=params.flink_user,
                       mode=0775
    )
    params.HdfsResource(None, action="execute")

    ## configuring REST Server
    if type == 'rest':
      Directory([params.flink_restserver_conf],
              owner=params.flink_user,
              group=params.user_group,
              mode=0750,
              create_parents = True
      )
      props = params.flink_rest_server_properties
      
      if params.security_enabled:
        props['security.kerberos.login.principal'] = props['security.kerberos.login.principal'].replace('_HOST', socket.getfqdn().lower())
      
      logback_content=InlineTemplate(params.flink_logback_rest_content)        

      PropertiesFile(format("{params.flink_restserver_conf}/flink-conf.yml"),
        properties = props,
        key_value_delimiter = ": ",
        owner=params.flink_user,
        group=params.flink_group,
        mode=0644
      )
      File(format("{params.flink_restserver_conf}/logback.xml"), content=logback_content, owner=params.flink_user, group=params.flink_group, mode=0400)

    # Configuring HistoryServer
    else:
      props = params.flink_history_server_properties
      
      if params.security_enabled:
        props['security.kerberos.login.principal'] = props['security.kerberos.login.principal'].replace('_HOST', socket.getfqdn().lower())
      
      logback_content=InlineTemplate(params.flink_logback_hs_content)
              
      Directory(params.flink_historyserver_conf,
              owner=params.flink_user,
              group=params.user_group,
              mode=0750,
              create_parents = True
      )
      PropertiesFile(format("{params.flink_historyserver_conf}/flink-conf.yml"),
        properties = props,
        key_value_delimiter = ": ",
        owner=params.flink_user,
        group=params.flink_group,
        mode=0644
      )
      File(format("{params.flink_historyserver_conf}/logback.xml"), content=logback_content, owner=params.flink_user, group=params.flink_group, mode=0400)

      generate_logfeeder_input_config('flink', Template("input.config-flink.json.j2", extra_imports=[default]))

  else :
    PropertiesFile(format("{params.flink_conf}/flink-conf.yml"),
        properties = params.flink_client_properties,
        key_value_delimiter = ": ",
        owner=params.flink_user,
        group=params.flink_group,
        mode=0644
    )

  effective_version = params.version if upgrade_type is not None else params.stack_version_formatted
  if effective_version:
    effective_version = format_stack_version(effective_version)
