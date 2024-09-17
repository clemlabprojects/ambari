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
import os
import sys
from ambari_commons.exceptions import FatalException
from resource_management.libraries.script.script import Script
from resource_management.libraries.resources.xml_config import XmlConfig
from resource_management.libraries.resources.template_config import TemplateConfig
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions import lzo_utils
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.generate_logfeeder_input_config import generate_logfeeder_input_config
from resource_management.core.source import Template, InlineTemplate
from resource_management.libraries.functions.mounted_dirs_helper import handle_mounted_dirs
from resource_management.core.resources import Package
from resource_management.core.resources.service import ServiceConfig
from resource_management.core.resources.system import Directory, Execute, File
from ambari_commons.os_family_impl import OsFamilyFuncImpl, OsFamilyImpl
from ambari_commons import OSConst
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.core.logger import Logger

def create_dirs(data_dir):
  """
  :param data_dir: The directory to create
  :param params: parameters
  """
  import params
  Logger.info(format("Creating ozone datanode data directories"))
  Directory(data_dir,
            create_parents = True,
            cd_access="a",
            mode=0o755,
            owner=params.ozone_user,
            group=params.user_group,
            ignore_failures=True
  )

@OsFamilyFuncImpl(os_family=OsFamilyImpl.DEFAULT)
def ozone(name=None):
  import params

  
  # get dict object base on component
  ozone_env_dict = getDictObjectForComponent(name, params, 'env')
  ozone_log4j_props = getDictObjectForComponent(name, params, 'log4j')
  conf_dir = ozone_env_dict['ozone_conf_dir']

  Directory( params.etc_prefix_dir,
      mode=0o755
  )
  Directory(ozone_env_dict['ozone_conf_dir'],
      group = params.user_group,
      create_parents = True
  )
   
  Directory(params.java_io_tmpdir,
      create_parents = True,
      mode=0o777
  )
  
  if name == "ozone-manager":
    if params.om_ssl_enabled:
      XmlConfig("ssl-server.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.om_ssl_server_dict,
        owner = params.ozone_user,
        group = params.user_group
      )
      XmlConfig("ssl-client.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.om_ssl_client_dict,
        owner = params.ozone_user,
        group = params.user_group
      )
        # creating fallback metadata dir
    Directory( params.ozone_manager_metadata_dir,
      owner = params.ozone_user,
      create_parents = True,
      cd_access = "a",
      mode = 0o750,
    )

  if name == "ozone-httpfs":
    XmlConfig("httpfs-site.xml",
      conf_dir = ozone_env_dict['ozone_conf_dir'],
      configurations = params.ozone_httpfs_properties,
      owner = params.ozone_user,
      group = params.user_group
    )

    File(os.path.join(ozone_env_dict['ozone_conf_dir'], 'httpfs-env.sh'),
        owner=params.ozone_user,  group=params.user_group,
        content=InlineTemplate(params.ozone_httpfs_env_sh_template)
    )
    generate_logfeeder_input_config('ozone', Template("input.config-ozone.json.j2", extra_imports=[default]))

  ## Servers side tasks
  if name != "ozone-client":
    Directory( params.pid_dir,
      owner = params.ozone_user,
      create_parents = True,
      cd_access = "a",
      mode = 0o755,
    )
    Directory (params.log_dir,
      owner = params.ozone_user,
      create_parents = True,
      cd_access = "a",
      mode = 0o755,
    )
    Directory (params.ozone_http_dir,
      owner = params.ozone_user,
      create_parents = True,
      cd_access = "a",
      mode = 0o754,
    )
    # On some OS this folder could be not exists, so we will create it before pushing there files
    Directory(params.limits_conf_dir,
            create_parents = True,
            owner='root',
            group='root'
    )
    # creating fallback metadata dir
    Directory( params.ozone_datanode_metadata_dir,
      owner = params.ozone_user,
      create_parents = True,
      cd_access = "a",
      mode = 0o750,
    )
    File(os.path.join(params.limits_conf_dir, 'ozone.conf'),
        owner='root',
        group='root',
        mode=0o644,
        content=Template("ozone.conf.j2")
        )
    ## ssl properties
    if params.om_ssl_enabled:
      XmlConfig("ssl-server.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.om_ssl_server_dict,
        owner = params.ozone_user,
        group = params.user_group
      )
      XmlConfig("ssl-client.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.om_ssl_client_dict,
        owner = params.ozone_user,
        group = params.user_group
      )
    ## ssl properties
    if params.dn_ssl_enabled and name == "ozone-datanode":
      XmlConfig("ssl-server.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.dn_ssl_server_dict,
        owner = params.ozone_user,
        group = params.user_group
      )
      XmlConfig("ssl-client.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.dn_ssl_client_dict,
        owner = params.ozone_user,
        group = params.user_group
      )
    ## ssl properties
    if params.scm_ssl_enabled and name == "ozone-scm":
      XmlConfig("ssl-server.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.scm_ssl_server_dict,
        owner = params.ozone_user,
        group = params.user_group
      )
      XmlConfig("ssl-client.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.scm_ssl_client_dict,
        owner = params.ozone_user,
        group = params.user_group
      )
    ## ssl properties
    if params.recon_ssl_enabled and name == "ozone-recon":
      XmlConfig("ssl-server.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.recon_ssl_server_dict,
        owner = params.ozone_user,
        group = params.user_group
      )
      XmlConfig("ssl-client.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.recon_ssl_client_dict,
        owner = params.ozone_user,
        group = params.user_group
      )
    ## ssl properties
    if params.s3g_ssl_enabled and name == "ozone-s3g":
      XmlConfig("ssl-server.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.s3g_ssl_server_dict,
        owner = params.ozone_user,
        group = params.user_group
      )
      XmlConfig("ssl-client.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.s3g_ssl_client_dict,
        owner = params.ozone_user,
        group = params.user_group
      )

    ## ssl properties
    if params.httpfs_ssl_enabled and name == "ozone-httpfs":
      XmlConfig("ssl-server.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.httpfs_ssl_server_dict,
        owner = params.ozone_user,
        group = params.user_group
      )
      XmlConfig("ssl-client.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.httpfs_ssl_client_dict,
        owner = params.ozone_user,
        group = params.user_group
      )
    ## Server Side Properties
    # always render core-site in /etc/hadoop/conf/{component_name}/core-site.xml
    if params.is_hdfs_enabled:
      XmlConfig("core-site.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.core_site,
        configuration_attributes=params.config['configurationAttributes']['core-site'],
        owner = params.ozone_user,
        group = params.user_group
      )
    else:
      XmlConfig("core-site.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.ozone_core_site,
        configuration_attributes=params.config['configurationAttributes']['ozone-core-site'],
        owner = params.ozone_user,
        group = params.user_group
      )
  else:
    if params.is_hdfs_enabled:
      Logger.info("Skipping rendering of /etc/hadoop/conf/core-site.xml from OZONE as HDFS is installed")
    else:
      XmlConfig("core-site.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.ozone_core_site,
        configuration_attributes=params.config['configurationAttributes']['ozone-core-site'],
        owner = params.ozone_user,
        group = params.user_group
      )
      XmlConfig("ssl-client.xml",
        conf_dir = ozone_env_dict['ozone_conf_dir'],
        configurations = params.ozone_ssl_client,
        configuration_attributes=params.config['configurationAttributes']['ozone-ssl-client'],
        owner = params.ozone_user,
        group = params.user_group
      )
    

  if name == 'ozone-datanode':
      Logger.info(format("Handling Datanode Data dir creation"))
      # handle_mounted_dirs ensures that we don't create dfs data dirs which are temporary unavailable (unmounted), and intended to reside on a different mount.
      data_dir_to_mount_file_content = handle_mounted_dirs(create_dirs, params.dfs_data_dirs, params.data_dir_mount_file, params)
      # create a history file used by handle_mounted_dirs
      File(params.data_dir_mount_file,
          owner=params.ozone_user,
          group=params.user_group,
          mode=0o644,
          content=data_dir_to_mount_file_content
      )
      generate_logfeeder_input_config('ozone', Template("input.config-ozone.json.j2", extra_imports=[default]))
      

  # preparing dictionnary props
  for key in ozone_env_dict.keys():
    setattr(params, key, ozone_env_dict[key])

  XmlConfig("ozone-site.xml",
    conf_dir = ozone_env_dict['ozone_conf_dir'],
    configurations = params.ROLE_CONF_MAP[name],
    configuration_attributes=params.config['configurationAttributes']['ozone-site'],
    owner = params.ozone_user,
    group = params.user_group
  )

  content_env = InlineTemplate(params.ozone_env_sh_template,
    ozone_conf_dir = ozone_env_dict['ozone_conf_dir'],
    ozone_log_level = ozone_env_dict['ozone_log_level'],
    jsvc_path = ozone_env_dict['jsvc_path'],
    log4j_content = ozone_env_dict['log4j_content'],
    ozone_security_log_max_backup_size = ozone_env_dict['ozone_security_log_max_backup_size'],
    ozone_new_generation_size = ozone_env_dict['ozone_new_generation_size'],
    ozone_log_max_backup_size = ozone_env_dict['ozone_log_max_backup_size'],
    ozone_max_new_generation_size = ozone_env_dict['ozone_max_new_generation_size'],
    ozone_java_opts = ozone_env_dict['ozone_java_opts'],
    ozone_security_log_number_of_backup_files = ozone_env_dict['ozone_security_log_number_of_backup_files'],
    ozone_log_dir_prefix = ozone_env_dict['ozone_log_dir_prefix'],
    ozone_heapsize = ozone_env_dict['ozone_heapsize'],
    ozone_max_perm_size = ozone_env_dict['ozone_max_perm_size'],
    ozone_home = ozone_env_dict['ozone_home'],
    ozone_perm_size = ozone_env_dict['ozone_perm_size'],
    ozone_pid_dir_prefix = ozone_env_dict['ozone_pid_dir_prefix'],
    ozone_log_number_of_backup_files = ozone_env_dict['ozone_log_number_of_backup_files'],
    ozone_secure_dn_user = ozone_env_dict['ozone_secure_dn_user'],
    java_home = ozone_env_dict['java_home'],
    role = name
  )

  File(format("{conf_dir}/ozone-env.sh"),
    owner = params.ozone_user,
    content= content_env,
    group = params.user_group
  )
  content_log4j_env = InlineTemplate(ozone_env_dict['log4j_content'],
    ozone_conf_dir = ozone_env_dict['ozone_conf_dir'],
    ozone_log_level = ozone_env_dict['ozone_log_level'],
    jsvc_path = ozone_env_dict['jsvc_path'],
    log4j_content = ozone_env_dict['log4j_content'],
    ozone_security_log_max_backup_size = ozone_env_dict['ozone_security_log_max_backup_size'],
    ozone_new_generation_size = ozone_env_dict['ozone_new_generation_size'],
    ozone_log_max_backup_size = ozone_env_dict['ozone_log_max_backup_size'],
    ozone_max_new_generation_size = ozone_env_dict['ozone_max_new_generation_size'],
    ozone_java_opts = ozone_env_dict['ozone_java_opts'],
    ozone_security_log_number_of_backup_files = ozone_env_dict['ozone_security_log_number_of_backup_files'],
    ozone_log_dir_prefix = ozone_env_dict['ozone_log_dir_prefix'],
    ozone_heapsize = ozone_env_dict['ozone_heapsize'],
    ozone_max_perm_size = ozone_env_dict['ozone_max_perm_size'],
    ozone_home = ozone_env_dict['ozone_home'],
    ozone_perm_size = ozone_env_dict['ozone_perm_size'],
    ozone_pid_dir_prefix = ozone_env_dict['ozone_pid_dir_prefix'],
    ozone_log_number_of_backup_files = ozone_env_dict['ozone_log_number_of_backup_files'],
    ozone_secure_dn_user = ozone_env_dict['ozone_secure_dn_user'],
    java_home = ozone_env_dict['java_home']  
  )
  # render specific configuration files
  File(format("{conf_dir}/log4j.properties"),
        mode=0o644,
        group=params.user_group,
        owner=params.ozone_user,
        content=content_log4j_env
  )
  if name == 'ozone-httpfs':
    content_httpfs_log4j_env = InlineTemplate(ozone_env_dict['httpfs_log4j_content'],
      ozone_conf_dir = ozone_env_dict['ozone_conf_dir'],
      ozone_log_level = ozone_env_dict['ozone_log_level'],
      jsvc_path = ozone_env_dict['jsvc_path'],
      log4j_content = ozone_env_dict['log4j_content'],
      ozone_security_log_max_backup_size = ozone_env_dict['ozone_security_log_max_backup_size'],
      ozone_new_generation_size = ozone_env_dict['ozone_new_generation_size'],
      ozone_log_max_backup_size = ozone_env_dict['ozone_log_max_backup_size'],
      ozone_max_new_generation_size = ozone_env_dict['ozone_max_new_generation_size'],
      ozone_java_opts = ozone_env_dict['ozone_java_opts'],
      ozone_security_log_number_of_backup_files = ozone_env_dict['ozone_security_log_number_of_backup_files'],
      ozone_log_dir_prefix = ozone_env_dict['ozone_log_dir_prefix'],
      ozone_heapsize = ozone_env_dict['ozone_heapsize'],
      ozone_max_perm_size = ozone_env_dict['ozone_max_perm_size'],
      ozone_home = ozone_env_dict['ozone_home'],
      ozone_perm_size = ozone_env_dict['ozone_perm_size'],
      ozone_pid_dir_prefix = ozone_env_dict['ozone_pid_dir_prefix'],
      ozone_log_number_of_backup_files = ozone_env_dict['ozone_log_number_of_backup_files'],
      ozone_secure_dn_user = ozone_env_dict['ozone_secure_dn_user'],
      java_home = ozone_env_dict['java_home']  
    )
    File(format("{conf_dir}/httpfs-log4j.properties"),
          mode=0o644,
          group=params.user_group,
          owner=params.ozone_user,
          content=content_httpfs_log4j_env
    )

  if params.security_enabled:
    ozone_TemplateConfig(name, params, conf_dir)

def renderJaas(jaas_name, conf_dir):
  import params
  TemplateConfig(format("{conf_dir}/{jaas_name}"),
      owner = params.ozone_user
  )

def ozone_TemplateConfig(name, params=None, conf_dir=None):
  if name == 'ozone-manager':
    jaas_name = params.ozone_om_jaas_config_file 
    renderJaas(jaas_name, conf_dir)
  elif name == 'ozone-scm':
    jaas_name = params.ozone_scm_jaas_config_file 
    renderJaas(jaas_name, conf_dir)
  elif name == 'ozone-datanode':
    jaas_name = params.ozone_dn_jaas_config_file
    renderJaas(jaas_name, conf_dir)
  elif name == 'ozone-recon':
    jaas_name = params.ozone_recon_jaas_config_file
    renderJaas(jaas_name, conf_dir)
  elif name == 'ozone-s3g':
    jaas_name = params.ozone_s3g_jaas_config_file
    renderJaas(jaas_name, conf_dir)
  else:
    pass

def getDictObjectForComponent(name=None, params=None, type="env"):
  if type == "env":
    conf_dir = params.ozone_base_conf_dir
    ozone_home =  format('{params.stack_root}/current/ozone-client')
    ozone_heapsize = params.ozone_client_heapsize
    ozone_java_opts = params.ozone_client_java_opts
    ozone_log_level = 'INFO'
    ozone_security_log_max_backup_size = 256
    ozone_security_log_number_of_backup_files = 20
    ozone_log_max_backup_size = 256
    ozone_log_number_of_backup_files = 20
    ozone_new_generation_size = 256
    ozone_max_new_generation_size = 256
    ozone_perm_size = 256
    ozone_max_perm_size = 256
    ozone_log4j_content = params.ozone_log4j_content
    if name != 'ozone-client':
      conf_dir = os.path.join(params.ozone_base_conf_dir, params.ROLE_NAME_MAP_CONF[name])
    if name == 'ozone-manager':
      ozone_home =  format('{params.stack_root}/current/ozone-manager')
      ozone_heapsize = params.ozone_manager_heapsize
      ozone_log_level = params.ozone_manager_log_level
      ozone_security_log_max_backup_size = params.ozone_manager_security_log_max_backup_size
      ozone_security_log_number_of_backup_files = params.ozone_manager_security_log_number_of_backup_files
      ozone_log_max_backup_size = params.ozone_manager_ozone_log_max_backup_size
      ozone_log_number_of_backup_files = params.ozone_manager_ozone_log_number_of_backup_files
      ozone_java_opts = params.ozone_manager_java_opts
      ozone_new_generation_size = params.ozone_manager_opt_newsize
      ozone_max_new_generation_size = params.ozone_manager_opt_maxnewsize
      ozone_perm_size = params.ozone_manager_opt_permsize
      ozone_max_perm_size = params.ozone_manager_opt_maxpermsize
      ozone_log4j_content = params.ozone_manager_log4j_content
    elif name == 'ozone-scm':
      ozone_home =  format('{params.stack_root}/current/ozone-scm')
      ozone_heapsize = params.ozone_scm_heapsize
      ozone_log_level = params.ozone_scm_log_level
      ozone_security_log_max_backup_size = params.ozone_scm_security_log_max_backup_size
      ozone_security_log_number_of_backup_files = params.ozone_scm_security_log_number_of_backup_files
      ozone_log_max_backup_size = params.ozone_scm_ozone_log_max_backup_size
      ozone_log_number_of_backup_files = params.ozone_scm_ozone_log_number_of_backup_files
      ozone_java_opts = params.ozone_scm_java_opts
      ozone_new_generation_size = params.ozone_scm_opt_newsize
      ozone_max_new_generation_size = params.ozone_scm_opt_maxnewsize
      ozone_perm_size = params.ozone_scm_opt_permsize
      ozone_max_perm_size = params.ozone_scm_opt_maxpermsize
      ozone_log4j_content = params.ozone_scm_log4j_content
    elif name == 'ozone-recon':
      ozone_home =  format('{params.stack_root}/current/ozone-recon')
      ozone_heapsize = params.ozone_recon_heapsize
      ozone_log_level = params.ozone_recon_log_level
      ozone_security_log_max_backup_size = params.ozone_recon_security_log_max_backup_size
      ozone_security_log_number_of_backup_files = params.ozone_recon_security_log_number_of_backup_files
      ozone_log_max_backup_size = params.ozone_recon_ozone_log_max_backup_size
      ozone_log_number_of_backup_files = params.ozone_recon_ozone_log_number_of_backup_files
      ozone_java_opts = params.ozone_recon_java_opts
      ozone_new_generation_size = params.ozone_recon_opt_newsize
      ozone_max_new_generation_size = params.ozone_recon_opt_maxnewsize
      ozone_perm_size = params.ozone_recon_opt_permsize
      ozone_max_perm_size = params.ozone_recon_opt_maxpermsize
      ozone_log4j_content = params.ozone_recon_log4j_content
    elif name == 'ozone-httpfs':
      ozone_home =  format('{params.stack_root}/current/ozone-httpfs')
      ozone_heapsize = params.ozone_httpfs_heapsize
      ozone_log_level = params.ozone_httpfs_log_level
      ozone_security_log_max_backup_size = params.ozone_httpfs_security_log_max_backup_size
      ozone_security_log_number_of_backup_files = params.ozone_httpfs_security_log_number_of_backup_files
      ozone_log_max_backup_size = params.ozone_httpfs_ozone_log_max_backup_size
      ozone_log_number_of_backup_files = params.ozone_httpfs_ozone_log_number_of_backup_files
      ozone_java_opts = params.ozone_httpfs_java_opts
      ozone_new_generation_size = params.ozone_httpfs_opt_newsize
      ozone_max_new_generation_size = params.ozone_httpfs_opt_maxnewsize
      ozone_perm_size = params.ozone_httpfs_opt_permsize
      ozone_max_perm_size = params.ozone_httpfs_opt_maxpermsize
      ozone_httpfs_log4j_content = params.ozone_httpfs_log4j_content
      ozone_log4j_content = params.ozone_log4j_content
    elif name == 'ozone-datanode':
      ozone_home =  format('{params.stack_root}/current/ozone-datanode')
      ozone_heapsize = params.ozone_datanode_heapsize
      ozone_log_level = params.ozone_manager_log_level
      ozone_security_log_max_backup_size = params.ozone_datanode_security_log_max_backup_size
      ozone_security_log_number_of_backup_files = params.ozone_datanode_security_log_number_of_backup_files
      ozone_log_max_backup_size = params.ozone_datanode_ozone_log_max_backup_size
      ozone_log_number_of_backup_files = params.ozone_datanode_ozone_log_number_of_backup_files
      ozone_java_opts = params.ozone_datanode_java_opts
      ozone_new_generation_size = params.ozone_datanode_opt_newsize
      ozone_max_new_generation_size = params.ozone_datanode_opt_maxnewsize
      ozone_perm_size = params.ozone_datanode_opt_permsize
      ozone_max_perm_size = params.ozone_datanode_opt_maxpermsize
      ozone_log4j_content = params.ozone_datanode_log4j_content
    elif name == 'ozone-s3g':
      ozone_home =  format('{params.stack_root}/current/ozone-s3g')
      ozone_heapsize = params.ozone_s3g_heapsize
      ozone_log_level = params.ozone_s3g_log_level
      ozone_security_log_max_backup_size = params.ozone_s3g_security_log_max_backup_size
      ozone_security_log_number_of_backup_files = params.ozone_s3g_security_log_number_of_backup_files
      ozone_log_max_backup_size = params.ozone_s3g_ozone_log_max_backup_size
      ozone_log_number_of_backup_files = params.ozone_s3g_ozone_log_number_of_backup_files
      ozone_java_opts = params.ozone_s3g_java_opts
      ozone_new_generation_size = params.ozone_s3g_opt_newsize
      ozone_max_new_generation_size = params.ozone_s3g_opt_maxnewsize
      ozone_perm_size = params.ozone_s3g_opt_permsize
      ozone_max_perm_size = params.ozone_s3g_opt_maxpermsize
      ozone_log4j_content = params.ozone_s3g_log4j_content
    ozone_env_dict = {
      "java_home": params.java64_home,
      "ozone_log_dir_prefix": params.log_dir,
      "ozone_pid_dir_prefix": params.pid_dir,
      "jsvc_path": params.jsvc_path,
      "ozone_secure_dn_user": params.ozone_secure_dn_user
    }
    ozone_env_dict['ozone_home'] = ozone_home
    if 'ozone_conf_dir' not in ozone_env_dict:
      ozone_env_dict['ozone_conf_dir'] = conf_dir
    ozone_env_dict['ozone_heapsize'] = ozone_heapsize
    ozone_env_dict['ozone_log_level'] = ozone_log_level
    ozone_env_dict['ozone_security_log_max_backup_size'] = ozone_security_log_max_backup_size
    ozone_env_dict['ozone_security_log_number_of_backup_files'] = ozone_security_log_number_of_backup_files
    ozone_env_dict['ozone_log_max_backup_size'] = ozone_log_max_backup_size
    ozone_env_dict['ozone_log_number_of_backup_files'] = ozone_log_number_of_backup_files
    ozone_env_dict['ozone_java_opts'] = ozone_java_opts
    ozone_env_dict['ozone_new_generation_size'] = ozone_new_generation_size
    ozone_env_dict['ozone_max_new_generation_size'] = ozone_max_new_generation_size
    ozone_env_dict['ozone_perm_size'] = ozone_perm_size
    ozone_env_dict['ozone_max_perm_size'] = ozone_max_perm_size
    ozone_env_dict['log4j_content'] = ozone_log4j_content
    if name == 'ozone-httpfs':
      ozone_env_dict['httpfs_log4j_content'] = ozone_httpfs_log4j_content
    return ozone_env_dict
  else:
    Logger.info(format("{name} component is not supported"))