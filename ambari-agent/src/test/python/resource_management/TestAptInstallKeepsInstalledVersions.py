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

Installing a stack version leaves the packages of the installed versions alone: apt must not upgrade the
installed packages built from the same source package (the unversioned zookeeper with zookeeper-<version>),
which removes the packages of the installed version pinned to their exact version.
"""

from unittest import TestCase
from mock.mock import patch

from ambari_commons import shell
from ambari_commons.repo_manager.apt_manager import AptManager
from resource_management.core.logger import Logger

Logger.initialize_logger()

SOURCE_PACKAGE_OPTION = "APT::Get::Upgrade-By-Source-Package=false"


class TestAptInstallKeepsInstalledVersions(TestCase):

  def install(self, context):
    manager = AptManager()
    commands = []
    with patch.object(AptManager, "_check_existence", return_value=False), \
         patch.object(shell, "repository_manager_executor", side_effect=lambda cmd, *args, **kwargs: commands.append(cmd)):
      manager.install_package("zookeeper-1-3-2-0-55", context)
    return commands[0]

  def test_a_stack_version_install_keeps_the_installed_versions(self):
    cmd = self.install(shell.RepoCallContext(keep_installed_versions=True))
    self.assertEqual(["-o", SOURCE_PACKAGE_OPTION], cmd[1:3])
    self.assertEqual(["install", "zookeeper-1-3-2-0-55"], cmd[-2:])

  def test_other_installs_keep_the_apt_defaults(self):
    self.assertNotIn(SOURCE_PACKAGE_OPTION, self.install(shell.RepoCallContext()))
