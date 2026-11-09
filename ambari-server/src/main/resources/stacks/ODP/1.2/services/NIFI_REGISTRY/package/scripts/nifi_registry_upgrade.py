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

NiFi Registry database of a stack upgrade. Before ODP 1.3.2.0 the H2 database URL was relative (jdbc:h2:./database/...):
the database lived in the install directory of the version (/usr/odp/<version>/nifi-registry/database), which the new
version does not have. The upgrade points the URL at nifi.registry.database.dir; copy_database copies the database
there first. The copy leaves the source in place: a downgrade uses it again.
"""

import os

from resource_management.core import sudo
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Execute
from resource_management.libraries.functions import upgrade_summary
from resource_management.libraries.script import Script


class NifiRegistryUpgrade(Script):

  def copy_database(self, env):
    import params
    version = upgrade_summary.get_source_version("NIFI_REGISTRY")
    if not version:
      Logger.info("No NiFi Registry upgrade in progress: nothing to copy.")
      return
    source = os.path.join(params.stack_root, version, "nifi-registry", "database")
    target = params.nifi_registry_database_dir
    if not sudo.path_isdir(source) or not sudo.listdir(source):
      Logger.info("No NiFi Registry database in {0}: nothing to copy.".format(source))
      return
    if sudo.path_isdir(target) and sudo.listdir(target):
      Logger.info("{0} already holds a NiFi Registry database: it is kept, {1} is not copied.".format(target, source))
      return
    Execute(("mkdir", "-p", target), sudo=True)
    Execute(("cp", "-a", "-T", source, target), sudo=True)
    Execute(("chown", "-R", "{0}:{1}".format(params.nifi_registry_user, params.nifi_registry_group), target), sudo=True)
    Logger.info("Copied the NiFi Registry database from {0} to {1}".format(source, target))


if __name__ == "__main__":
  NifiRegistryUpgrade().execute()
