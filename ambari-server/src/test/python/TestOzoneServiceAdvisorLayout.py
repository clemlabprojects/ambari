'''
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
'''

import importlib.util
import os
import sys
from unittest import TestCase


class TestOzoneServiceAdvisorLayout(TestCase):
  """
  The Ozone Manager and the Storage Container Manager must be assigned to the same hosts.
  """

  def setUp(self):
    stacks_path = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../main/resources/stacks'))
    if stacks_path not in sys.path:
      sys.path.insert(0, stacks_path)
    spec = importlib.util.spec_from_file_location('ozone_service_advisor_layout',
                                                  os.path.join(stacks_path, 'ODP/1.0/services/OZONE/service_advisor.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    self.advisor = module.OzoneServiceAdvisor()

  def colocation_errors(self, manager_hosts, scm_hosts):
    def component(name, hostnames):
      return {"StackServiceComponents": {"component_name": name, "display_name": name, "cardinality": "1+",
                                         "component_category": "MASTER", "is_master": True, "hostnames": hostnames}}
    services = {"services": [{"StackServices": {"service_name": "OZONE"},
                              "components": [component("OZONE_MANAGER", manager_hosts),
                                             component("OZONE_STORAGE_CONTAINER_MANAGER", scm_hosts)]}]}
    hosts = {"items": [{"Hosts": {"host_name": name}} for name in sorted(set(manager_hosts + scm_hosts))]}
    return sorted((item["component-name"], item["host"]) for item in self.advisor.getServiceComponentLayoutValidations(services, hosts)
                  if "same hosts" in item["message"])

  def test_same_host_is_accepted(self):
    self.assertEqual([], self.colocation_errors(["h1"], ["h1"]))

  def test_same_hosts_with_high_availability_are_accepted(self):
    self.assertEqual([], self.colocation_errors(["h1", "h2", "h3"], ["h3", "h2", "h1"]))

  def test_different_hosts_are_refused(self):
    self.assertEqual([("OZONE_MANAGER", "h1"), ("OZONE_STORAGE_CONTAINER_MANAGER", "h2")], self.colocation_errors(["h1"], ["h2"]))

  def test_one_manager_without_storage_container_manager_is_refused(self):
    self.assertEqual([("OZONE_MANAGER", "h4"), ("OZONE_STORAGE_CONTAINER_MANAGER", "h3")],
                     self.colocation_errors(["h1", "h2", "h4"], ["h1", "h2", "h3"]))

  def test_default_layout_puts_both_on_the_same_host(self):
    self.assertEqual(self.advisor.componentLayoutSchemes['OZONE_MANAGER'],
                     self.advisor.componentLayoutSchemes['OZONE_STORAGE_CONTAINER_MANAGER'])
