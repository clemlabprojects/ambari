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
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.generate_logfeeder_input_config import generate_logfeeder_input_config
from resource_management import Directory, File, PropertiesFile, Template, InlineTemplate, format
from resource_management.core.resources.system import Execute, Link

def setup_livy(env, type, upgrade_type = None, action = None):
  import params

  Directory([params.livy2_pid_dir, params.livy2_log_dir],
            owner=params.livy2_user,
            group=params.user_group,
            mode=0o775,
            cd_access='a',
            create_parents=True
  )
  if type == 'server' and action == 'config':
    params.HdfsResource(params.livy2_hdfs_user_dir,
                        type="directory",
                        action="create_on_execute",
                        owner=params.livy2_user,
                        mode=0o775
    )
    params.HdfsResource(None, action="execute")

    if params.livy2_recovery_store == 'filesystem':
      params.HdfsResource(params.livy2_recovery_dir,
                          type="directory",
                          action="create_on_execute",
                          owner=params.livy2_user,
                          mode=0o700
         )
      params.HdfsResource(None, action="execute")

    generate_logfeeder_input_config('spark2', Template("input.config-spark2.json.j2", extra_imports=[default]))

  setup_symlink(params.livy2_stack_conf, params.livy2_conf)

  # create livy-env.sh in etc/conf dir
  File(os.path.join(params.livy2_conf, 'livy-env.sh'),
       owner=params.livy2_user,
       group=params.livy2_group,
       content=InlineTemplate(params.livy2_env_sh),
       mode=0o644,
  )

  # create livy-client.conf in etc/conf dir
  PropertiesFile(format("{livy2_conf}/livy-client.conf"),
                properties = params.config['configurations']['spark2-livy2-client-conf'],
                key_value_delimiter = " ",
                owner=params.livy2_user,
                group=params.livy2_group,
  )

  # create livy.conf in etc/conf dir
  PropertiesFile(format("{livy2_conf}/livy.conf"),
                properties = params.config['configurations']['spark2-livy2-conf'],
                key_value_delimiter = " ",
                owner=params.livy2_user,
                group=params.livy2_group,
  )

  # create log4j.properties in etc/conf dir
  File(os.path.join(params.livy2_conf, 'log4j.properties'),
       owner=params.livy2_user,
       group=params.livy2_group,
       content=params.livy2_log4j_properties,
       mode=0o644,
  )

  # create spark-blacklist.properties in etc/conf dir
  File(os.path.join(params.livy2_conf, 'spark-blacklist.conf'),
       owner=params.livy2_user,
       group=params.livy2_group,
       content=params.livy2_spark_blacklist_properties,
       mode=0o644,
  )

  Directory(params.livy2_logs_dir,
            owner=params.livy2_user,
            group=params.livy2_group,
            mode=0o755,
  )

def setup_symlink(stack_conf_dir = "/usr/odp/current/spark2-livy2/conf", src_livy2_conf_dir = "/etc/spark2-livy2/conf"):
  import params
  backup_folder_path = None
  backup_folder_suffix = "_tmp"

  if not os.path.exists(src_livy2_conf_dir):
    Directory(src_livy2_conf_dir,
              mode=0o750,
              cd_access='a',
              owner=params.livy2_user,
              group=params.user_group,
              create_parents = True,
              recursive_ownership = True,
    )

  if src_livy2_conf_dir != stack_conf_dir:
    if os.path.exists(stack_conf_dir) and not os.path.islink(stack_conf_dir):
      # Backup existing data before delete if config is changed repeatedly to/from default location at any point in time time, as there may be relevant contents (historic logs)
      backup_folder_path = backup_dir_contents(stack_conf_dir, backup_folder_suffix)

      Directory(stack_conf_dir,
        action="delete",
        create_parents = True)

    elif os.path.islink(stack_conf_dir) and os.path.realpath(stack_conf_dir) != src_livy2_conf_dir:
      Link(stack_conf_dir,
        action="delete")

    if not os.path.islink(stack_conf_dir):
        Link(stack_conf_dir,
                    to=src_livy2_conf_dir)

# Uses agent temp dir to store backup files
def backup_dir_contents(dir_path, backup_folder_suffix):
  import params
  backup_destination_path = params.tmp_dir + os.path.normpath(dir_path)+backup_folder_suffix
  Directory(backup_destination_path,
            mode=0o754,
            cd_access='a',
            owner=params.hbase_user,
            group=params.user_group,
            create_parents = True,
            recursive_ownership = True,
  )
  # Safely copy top-level contents to backup folder
  for file in os.listdir(dir_path):
    if os.path.isdir(os.path.join(dir_path, file)):
      Execute(('cp', '-r', os.path.join(dir_path, file), backup_destination_path),
              sudo=True)
      Execute(("chown", "-R", format("{hbase_user}:{user_group}"), os.path.join(backup_destination_path, file)),
              sudo=True)
    else:
      File(os.path.join(backup_destination_path, file),
         owner=params.hbase_user,
         content = StaticFile(os.path.join(dir_path,file)))

  return backup_destination_path

