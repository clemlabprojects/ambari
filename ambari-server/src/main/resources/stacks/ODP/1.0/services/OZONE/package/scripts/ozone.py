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
import os
import sys
from resource_management.libraries.script.script import Script
from resource_management.libraries.resources.xml_config import XmlConfig
from resource_management.libraries.resources.template_config import TemplateConfig
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions import lzo_utils
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.generate_logfeeder_input_config import generate_logfeeder_input_config
from resource_management.core.source import Template, InlineTemplate
from resource_management.core.resources import Package
from resource_management.core.resources.service import ServiceConfig
from resource_management.core.resources.system import Directory, Execute, File
from ambari_commons.os_family_impl import OsFamilyFuncImpl, OsFamilyImpl
from ambari_commons import OSConst
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.core.logger import Logger

@OsFamilyFuncImpl(os_family=OsFamilyImpl.DEFAULT)
def ozone(name=None):
  import params


  # get dict object base on component
  ozone_env_dict = getDictObjectForComponent(name, params, 'env')
  ozone_log4j_props = getDictObjectForComponent(name, params, 'log4j')
  conf_dir = ozone_env_dict["conf_dir"]

  Directory( params.etc_prefix_dir,
      mode=0755
  )
  Directory(conf_dir,
      group = params.user_group,
      create_parents = True
  )
   
  Directory(params.java_io_tmpdir,
      create_parents = True,
      mode=0777
  )
  
  if name != "ozone-client":
    Directory( params.pid_dir,
      owner = params.ozone_user,
      create_parents = True,
      cd_access = "a",
      mode = 0755,
    )
    Directory (params.log_dir,
      owner = params.ozone_user,
      create_parents = True,
      cd_access = "a",
      mode = 0755,
    )
    # On some OS this folder could be not exists, so we will create it before pushing there files
    Directory(params.limits_conf_dir,
            create_parents = True,
            owner='root',
            group='root'
    )
  
    File(os.path.join(params.limits_conf_dir, 'ozone.conf'),
        owner='root',
        group='root',
        mode=0644,
        content=Template("ozone.conf.j2")
        )
  
  XmlConfig("ozone-site.xml",
    conf_dir =  conf_dir,
    configurations = params.config['configurations']['ozone-site'],
    configuration_attributes=params.config['configurationAttributes']['ozone-site'],
    owner = params.ozone_user,
    group = params.user_group
  )
  File(format("{conf_dir}/ozone-env.sh"),
    owner = params.ozone_user,
    content=InlineTemplate(params.ozone_env_sh_template, ozone_env_dict),
    group = params.user_group,
    variables = {}
  )


  # render specific configuration files
  File(format("{conf_dir}/log4j.properties"),
        mode=0644,
        group=params.user_group,
        owner=params.ozone_user,
        content=InlineTemplate((ozone_env_dict['content'], ozone_env_dict)
  ))

  if params.security_enabled:
    ozone_TemplateConfig(name, params, conf_dir)


def ozone_TemplateConfig(name, params=None, conf_dir=None):
  if name == 'ozone-manager':
    jaas_name = params.ozone_om_jaas_config_file 
  elif name == 'ozone-scm':
    jaas_name = params.ozone_scm_jaas_config_file 
  elif name == 'ozone-datanode':
    jaas_name = params.ozone_dn_jaas_config_file
  elif name == 'ozone-s3g':
    jaas_name = params.ozone_s3g_jaas_config_file
  else:
    pass
  TemplateConfig( format("{conf_dir}/{jaas_name}"),
      owner = params.ozone_user
  )
  
def getDictObjectForComponent(name=None, params=None, type="env"):
  if type == "env":
    conf_dir = params.ozone_conf_dir
    ozone_home =  format('{params.stack_root}/current/ozone-client')
    ozone_heapsize = params.ozone_heapsize
    ozone_java_opts = params.ozone_java_opts
    ozone_log_level = 'INFO'
    ozone_security_log_max_backup_size = 256
    ozone_security_log_number_of_backup_files = 20
    ozone_ozone_log_max_backup_size = 256
    ozone_ozone_log_number_of_backup_files = 20
    ozone_new_generation_size = 256
    ozone_max_new_generation_size = 256
    ozone_perm_size = 256
    ozone_max_perm_size = 256
    conf_dir = params.ozone_conf_dir.join(params.ROLE_NAME_MAP_CONF(name))
    if name == 'ozone-manager':
      ozone_home =  format('{params.stack_root}/current/ozone-manager')
      ozone_heapsize = params.ozone_manager_heapsize
      ozone_log_level = params.ozone_manager_log_level
      ozone_security_log_max_backup_size = params.ozone_manager_security_log_max_backup_size
      ozone_security_log_number_of_backup_files = params.ozone_manager_security_log_number_of_backup_files
      ozone_ozone_log_max_backup_size = params.ozone_manager_ozone_log_max_backup_size
      ozone_ozone_log_number_of_backup_files = params.ozone_manager_ozone_log_number_of_backup_files
      ozone_java_opts = params.ozone_manager_java_opts
      ozone_new_generation_size = params.ozone_manager_opt_newsize
      ozone_max_new_generation_size = params.ozone_manager_opt_maxnewsize
      ozone_perm_size = params.ozone_manager_opt_permsize
      ozone_max_perm_size = params.ozone_manager_opt_maxpermsize
    elif name == 'ozone-scm':
      ozone_home =  format('{params.stack_root}/current/ozone-scm')
      ozone_heapsize = params.ozone_scm_heapsize
      ozone_log_level = params.ozone_scm_log_level
      ozone_security_log_max_backup_size = params.ozone_scm_security_log_max_backup_size
      ozone_security_log_number_of_backup_files = params.ozone_scm_security_log_number_of_backup_files
      ozone_ozone_log_max_backup_size = params.ozone_scm_ozone_log_max_backup_size
      ozone_ozone_log_number_of_backup_files = params.ozone_scm_ozone_log_number_of_backup_files
      ozone_java_opts = params.ozone_scm_java_opts
      ozone_new_generation_size = params.ozone_scm_opt_newsize
      ozone_max_new_generation_size = params.ozone_scm_opt_maxnewsize
      ozone_perm_size = params.ozone_scm_opt_permsize
      ozone_max_perm_size = params.ozone_scm_opt_maxpermsize
    elif name == 'ozone-recon':
      ozone_home =  format('{params.stack_root}/current/ozone-recon')
      ozone_heapsize = params.ozone_recon_heapsize
      ozone_log_level = params.ozone_recon_log_level
      ozone_security_log_max_backup_size = params.ozone_recon_security_log_max_backup_size
      ozone_security_log_number_of_backup_files = params.ozone_recon_security_log_number_of_backup_files
      ozone_ozone_log_max_backup_size = params.ozone_recon_ozone_log_max_backup_size
      ozone_ozone_log_number_of_backup_files = params.ozone_recon_ozone_log_number_of_backup_files
      ozone_java_opts = params.ozone_recon_java_opts
      ozone_new_generation_size = params.ozone_recon_opt_newsize
      ozone_max_new_generation_size = params.ozone_recon_opt_maxnewsize
      ozone_perm_size = params.ozone_recon_opt_permsize
      ozone_max_perm_size = params.ozone_recon_opt_maxpermsize
    elif name == 'ozone-datanode':
      ozone_home =  format('{params.stack_root}/current/ozone-datanode')
      ozone_heapsize = params.ozone_datanode_heapsize
      ozone_log_level = params.ozone_manager_log_level
      ozone_security_log_max_backup_size = params.ozone_datanode_security_log_max_backup_size
      ozone_security_log_number_of_backup_files = params.ozone_datanode_security_log_number_of_backup_files
      ozone_ozone_log_max_backup_size = params.ozone_datanode_ozone_log_max_backup_size
      ozone_ozone_log_number_of_backup_files = params.ozone_datanode_ozone_log_number_of_backup_files
      ozone_java_opts = params.ozone_datanode_java_opts
      ozone_new_generation_size = params.ozone_datanode_opt_newsize
      ozone_max_new_generation_size = params.ozone_datanode_opt_maxnewsize
      ozone_perm_size = params.ozone_datanode_opt_permsize
      ozone_max_perm_size = params.ozone_datanode_opt_maxpermsize
    elif name == 'ozone-s3g':
      ozone_home =  format('{params.stack_root}/current/ozone-s3g')
      ozone_heapsize = params.ozone_s3g_heapsize
      ozone_log_level = params.ozone_s3g_log_level
      ozone_security_log_max_backup_size = params.ozone_s3g_security_log_max_backup_size
      ozone_security_log_number_of_backup_files = params.ozone_s3g_security_log_number_of_backup_files
      ozone_ozone_log_max_backup_size = params.ozone_s3g_ozone_log_max_backup_size
      ozone_ozone_log_number_of_backup_files = params.ozone_s3g_ozone_log_number_of_backup_files
      ozone_java_opts = params.ozone_s3g_java_opts
      ozone_new_generation_size = params.ozone_s3g_opt_newsize
      ozone_max_new_generation_size = params.ozone_s3g_opt_maxnewsize
      ozone_perm_size = params.ozone_s3g_opt_permsize
      ozone_max_perm_size = params.ozone_s3g_opt_maxpermsize
    ozone_env_dict = {
      "java_home": params.java64_home,
      "ozone_log_dir_prefix": params.log_dir,
      "ozone_pid_dir_prefix": params.pid_dir,
      "jsvc_path": params.jsvc_path,
      "ozone_secure_dn_user": params.secu
    }
    ozone_env_dict['ozone_home'] = ozone_home
    ozone_env_dict['ozone_conf_dir'] = conf_dir
    ozone_env_dict['ozone_heapsize'] = ozone_heapsize
    ozone_env_dict['ozone_log_level'] = ozone_log_level
    ozone_env_dict['ozone_security_log_max_backup_size'] = ozone_security_log_max_backup_size
    ozone_env_dict['ozone_security_log_number_of_backup_files'] = ozone_security_log_number_of_backup_files
    ozone_env_dict['ozone_ozone_log_max_backup_size'] = ozone_ozone_log_max_backup_size
    ozone_env_dict['ozone_ozone_log_number_of_backup_files'] = ozone_ozone_log_number_of_backup_files
    ozone_env_dict['ozone_java_opts'] = ozone_java_opts
    ozone_env_dict['ozone_new_generation_size'] = ozone_new_generation_size
    ozone_env_dict['ozone_max_new_generation_size'] = ozone_max_new_generation_size
    ozone_env_dict['ozone_perm_size'] = ozone_perm_size
    ozone_env_dict['ozone_max_perm_size'] = ozone_max_perm_size
    return ozone_env_dict
  else:
    Logger.info(format("{name} component is not supported"))
    raise