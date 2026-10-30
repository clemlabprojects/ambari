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

import importlib.util
import os
import sys
from unittest import TestCase


class TestOdpCoreServiceAdvisor(TestCase):

  test_directory = os.path.dirname(os.path.abspath(__file__))
  resources_path = os.path.join(test_directory, '../../main/resources')

  def load_core_advisor(self):
    for name, path in (('ambari_configuration', 'stacks/ambari_configuration.py'), ('stack_advisor', 'stacks/stack_advisor.py')):
      spec = importlib.util.spec_from_file_location(name, os.path.join(self.resources_path, path))
      module = importlib.util.module_from_spec(spec)
      spec.loader.exec_module(module)
      sys.modules[name] = module
    # the stack advisor loads CORE first, before any service advisor has loaded the base module
    sys.modules.pop('service_advisor', None)
    path = os.path.join(self.resources_path, 'stacks/ODP/1.0/services/CORE/service_advisor.py')
    spec = importlib.util.spec_from_file_location('odp_core_service_advisor', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.CoreServiceAdvisor()

  def test_loads_and_recommends_the_ozone_default_fs_without_hdfs(self):
    advisor = self.load_core_advisor()
    services = {
      "services": [{"StackServices": {"service_name": "CORE"}}, {"StackServices": {"service_name": "OZONE"}}],
      "configurations": {
        "core-env": {"properties": {"core_filesystem_type": "HDFS"}},
        "core-site": {"properties": {"fs.defaultFS": "hdfs://localhost:8020"}},
        "ozone-site": {"properties": {"ozone.om.service.ids": ""}}
      }
    }
    configurations = {}

    advisor.recommendCoreConfigurations(configurations, {}, services, {})

    self.assertEqual("OZONE", configurations["core-env"]["properties"]["core_filesystem_type"])
    self.assertEqual("ofs://omservice", configurations["core-site"]["properties"]["fs.defaultFS"])

  def test_keeps_a_viewfs_default_fs_on_hdfs(self):
    advisor = self.load_core_advisor()
    services = {
      "services": [{"StackServices": {"service_name": "CORE"}}, {"StackServices": {"service_name": "HDFS"}}],
      "configurations": {
        "core-env": {"properties": {"core_filesystem_type": "HDFS"}},
        "core-site": {"properties": {"fs.defaultFS": "viewfs://cluster"}}
      }
    }
    configurations = {}

    advisor.recommendCoreConfigurations(configurations, {}, services, {})

    self.assertNotIn("fs.defaultFS", configurations.get("core-site", {}).get("properties", {}))
