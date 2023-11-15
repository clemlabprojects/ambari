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

from resource_management.core.resources.system import Execute, File
from resource_management.libraries.functions.format import format

def hbase_service(
  name,
  action = 'start'): # 'start' or 'stop' or 'status'
    
    import params
  
    role = name
    cmd = format("{daemon_script} --config {hbase_conf_dir}")
    pid_file = format("{hbase_pid_dir}/hbase-{hbase_user}-{role}.pid")
    no_op_test = format("ls {pid_file} >/dev/null 2>&1 && ps `cat {pid_file}` >/dev/null 2>&1")
    
    if action == 'start':

      if(params.create_hbase_jna_symlink):
        setup_symlink(params.target_hbase_jna_dir, params.src_hbase_jna_dir)
      daemon_cmd = format("{cmd} start {role}")
      
      Execute ( daemon_cmd,
        not_if = no_op_test,
        user = params.hbase_user
      )
    elif action == 'stop':
      daemon_cmd = format("{cmd} stop {role}")

      Execute ( daemon_cmd,
        user = params.hbase_user,
        # BUGFIX: hbase regionserver sometimes hangs when nn is in safemode
        timeout = params.hbase_regionserver_shutdown_timeout,
        on_timeout = format("{no_op_test} && {sudo} -H -E kill -9 `{sudo} cat {pid_file}`")
      )
      
      File(pid_file,
        action = "delete",
      )


# Used to workaround the hardcoded pid/log dir used on the kafka bash process launcher
def setup_symlink(target_hbase_jna_dir = '/tmp/hbase',src_hbase_jna_dir = '/run/hbase'):
  import params
  backup_folder_path = None
  backup_folder_suffix = "_tmp"
  if not os.path.exists(target_hbase_jna_dir):
    Directory(target_hbase_jna_dir,
              mode=0o750,
              cd_access='a',
              owner=params.hbase_user,
              group=params.user_group,
              create_parents = True,
              recursive_ownership = True,
    )
  if os.path.exists(src_hbase_jna_dir) and not os.path.islink(src_hbase_jna_dir):

    # Backup existing data before delete if config is changed repeatedly to/from default location at any point in time time, as there may be relevant contents (historic logs)
    backup_folder_path = backup_dir_contents(src_hbase_jna_dir, backup_folder_suffix)

    Directory(src_hbase_jna_dir,
              action="delete",
              create_parents = True)

  elif os.path.islink(src_hbase_jna_dir) and os.path.realpath(src_hbase_jna_dir) != target_hbase_jna_dir:
    Link(src_hbase_jna_dir,
          action="delete")

  if not os.path.islink(src_hbase_jna_dir):
    Link(src_hbase_jna_dir,
          to=target_hbase_jna_dir)

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
