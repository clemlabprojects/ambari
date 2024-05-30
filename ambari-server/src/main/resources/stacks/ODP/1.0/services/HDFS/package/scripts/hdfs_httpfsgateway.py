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
from resource_management.core.resources.system import Directory, Execute, File
from resource_management.libraries.functions.generate_logfeeder_input_config import generate_logfeeder_input_config
from resource_management.libraries.functions.mounted_dirs_helper import handle_mounted_dirs
from resource_management.libraries.functions.default import default
from resource_management.libraries.resources.xml_config import XmlConfig

from resource_management.core.source import Template, InlineTemplate
from utils import service, PidFiles
from ambari_commons.os_family_impl import OsFamilyImpl, OsFamilyFuncImpl

from ambari_commons import OSConst
from utils import initiate_safe_zkfc_failover, get_hdfs_binary, get_dfsadmin_base_command
import os

@OsFamilyFuncImpl(os_family=OsFamilyImpl.DEFAULT)
def httpfsgateway(action=None):
  if action == "configure":
    import params
    if params.httpfs_stack_enabled:
      XmlConfig("httpfs-site.xml",
              conf_dir=params.hadoop_conf_dir,
              configurations=params.httpfs_properties,
              configuration_attributes=params.config['configurationAttributes']['httpfs-site'],
              mode=0o644,
              owner=params.httpfs_user,
              group=params.user_group
      )
      File(os.path.join(params.hadoop_conf_dir, 'httpfs-env.sh'),
         owner=params.httpfs_user,  group=params.user_group,
         content=InlineTemplate(params.httpfs_env_sh_template)
      )
      generate_logfeeder_input_config('hdfs', Template("input.config-hdfs.json.j2", extra_imports=[default]))

  elif action == "start" or action == "stop":
    hdfs_binary = get_hdfs_binary("hadoop-hdfs-client")
    import params
    if params.httpfs_stack_enabled:
      service(
        action=action, name="httpfs",
        user=params.hdfs_user,
        create_pid_dir=True,
        create_log_dir=True
      )
  elif action == "status":
    import params
    if params.httpfs_stack_enabled:
      PidFiles(params.httpfs_pid_file).check_status()
