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

Flow backup of a NiFi node around a stack upgrade. NiFi 2 rewrites flow.json.gz on its first start, which NiFi 1 cannot
read back: the flow, configuration and local state directories are copied before the upgrade (backup_flow), into a
directory named after the version they belong to, and copied back before NiFi restarts on that version when the upgrade
is downgraded (restore_flow).
"""

import os

from resource_management.core import sudo
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Execute
from resource_management.libraries.functions import upgrade_summary
from resource_management.libraries.script import Script

BACKUP_ROOT = "upgrade-backup"


def backup_dir(internal_dir, version):
  return os.path.join(internal_dir, BACKUP_ROOT, version)


def flow_dirs(params):
  """The directories of the node to keep, without duplicates (the state directory may sit in the flow one)."""
  dirs = []
  for path in (params.nifi_flow_config_dir, params.nifi_config_dir, params.nifi_state_dir):
    path = os.path.realpath(path) if path else path
    if path and sudo.path_isdir(path) and path not in dirs and not any(path.startswith(d + os.sep) for d in dirs):
      dirs.append(path)
  return dirs


class NifiUpgrade(Script):

  def backup_flow(self, env):
    import params
    version = upgrade_summary.get_source_version("NIFI")
    if not version:
      Logger.info("No NiFi upgrade in progress: nothing to back up.")
      return
    target = backup_dir(params.nifi_internal_dir, version)
    Execute(("rm", "-rf", target), sudo=True)
    Execute(("mkdir", "-p", target), sudo=True)
    for path in flow_dirs(params):
      # the copy keeps the absolute path under the backup directory
      Execute(("cp", "-a", "--parents", path, target), sudo=True)
      Logger.info("Backed up {0} to {1}".format(path, target))

  def restore_flow(self, env):
    import params
    # on a downgrade, the target is the version the backup was taken from
    version = upgrade_summary.get_target_version("NIFI")
    source = backup_dir(params.nifi_internal_dir, version) if version else None
    if not source or not sudo.path_isdir(source):
      Logger.warning("No NiFi backup for version {0}: the flow is left as it is.".format(version))
      return
    for entry in sudo.listdir(source):
      # each backed up directory is <backup>/<absolute path>: copy it back over the live one
      Execute(("cp", "-a", "-T", os.path.join(source, entry), os.path.join(os.sep, entry)), sudo=True)
    Logger.info("Restored the NiFi flow, configuration and state of version {0} from {1}".format(version, source))


if __name__ == "__main__":
  NifiUpgrade().execute()
