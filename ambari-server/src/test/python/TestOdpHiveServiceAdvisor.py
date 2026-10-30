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
import imp
import os
from unittest import TestCase


class TestOdpHiveServiceAdvisor(TestCase):

  test_directory = os.path.dirname(os.path.abspath(__file__))
  resources_path = os.path.join(test_directory, '../../main/resources')

  ambari_configuration_path = os.path.abspath(os.path.join(resources_path, 'stacks/ambari_configuration.py'))
  with open(ambari_configuration_path, 'rb') as fp:
    spec = importlib.util.spec_from_file_location('ambari_configuration', ambari_configuration_path)
    ambari_configuration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ambari_configuration)

  stack_advisor_path = os.path.join(resources_path, 'stacks/stack_advisor.py')
  with open(stack_advisor_path, 'rb') as fp:
    spec = importlib.util.spec_from_file_location('stack_advisor', stack_advisor_path)
    stack_advisor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(stack_advisor)

  hive_service_advisor_path = os.path.join(
    resources_path, 'stacks/ODP/1.0/services/HIVE/service_advisor.py')
  with open(hive_service_advisor_path, 'rb') as fp:
    service_advisor_impl = imp.load_module(
      'odp_hive_service_advisor_impl', fp, hive_service_advisor_path, ('.py', 'rb', imp.PY_SOURCE))

  hosts = {"items": [{"Hosts": {"host_name": "h1"}}, {"Hosts": {"host_name": "h2"}}]}

  def services(self, service_names, hive_components):
    def component(name, hostnames):
      return {"StackServiceComponents": {"component_name": name, "display_name": name, "cardinality": "0+",
                                         "hostnames": hostnames}}
    services = [{"StackServices": {"service_name": name}, "components": []} for name in service_names]
    services.append({"StackServices": {"service_name": "HIVE"},
                     "components": [component(name, hostnames) for name, hostnames in hive_components.items()]})
    return {"services": services, "configurations": {}}

  def layout_errors(self, services):
    advisor = self.service_advisor_impl.HiveServiceAdvisor()
    return [item for item in advisor.getServiceComponentLayoutValidations(services, self.hosts)
            if item["level"] == "ERROR"]

  def test_metastore_only_hive_needs_neither_yarn_nor_tez(self):
    services = self.services(["CORE", "ZOOKEEPER", "OZONE"],
                             {"HIVE_METASTORE": ["h1"], "HIVE_SERVER": [], "HIVE_CLIENT": ["h1", "h2"]})

    self.assertEqual([], self.layout_errors(services))

  def test_hiveserver2_requires_yarn_and_tez(self):
    services = self.services(["CORE", "ZOOKEEPER", "OZONE"],
                             {"HIVE_METASTORE": ["h1"], "HIVE_SERVER": ["h2"], "HIVE_CLIENT": ["h1", "h2"]})

    errors = self.layout_errors(services)
    self.assertEqual(["HIVE_SERVER"], [error["component-name"] for error in errors])
    self.assertIn("YARN and TEZ", errors[0]["message"])

  def test_hiveserver2_is_not_placed_without_yarn_and_tez(self):
    advisor = self.service_advisor_impl.HiveServiceAdvisor()
    services = self.services(["CORE", "ZOOKEEPER", "OZONE"], {"HIVE_SERVER": []})
    hive_server = services["services"][-1]["components"][0]

    self.assertEqual([], advisor.getHostsForMasterComponent(services, self.hosts, hive_server, ["h1", "h2"]))
    hive_server["StackServiceComponents"]["hostnames"] = ["h2"]
    self.assertEqual(["h2"], advisor.getHostsForMasterComponent(services, self.hosts, hive_server, ["h1", "h2"]))

  def test_hiveserver2_is_placed_with_yarn_and_tez(self):
    advisor = self.service_advisor_impl.HiveServiceAdvisor()
    services = self.services(["CORE", "ZOOKEEPER", "HDFS", "YARN", "TEZ"], {"HIVE_SERVER": []})
    hive_server = services["services"][-1]["components"][0]

    self.assertEqual(1, len(advisor.getHostsForMasterComponent(services, self.hosts, hive_server, ["h1", "h2"])))

  def scratchdir_services(self, service_names, hive_filesystem_type, scratchdir):
    services = self.services(service_names, {"HIVE_METASTORE": ["h1"]})
    services["configurations"] = {
      "core-env": {"properties": {"core_filesystem_type": hive_filesystem_type}},
      "hive-env": {"properties": {"hive_filesystem_type": hive_filesystem_type, "hive_user": "hive"}},
      "hive-site": {"properties": {"hive.exec.scratchdir": scratchdir}},
      "ozone-site": {"properties": {"ozone.om.service.ids": "omservice"}},
    }
    return services

  def test_scratchdir_moves_from_ozone_tmp_into_the_hive_bucket(self):
    recommender = self.service_advisor_impl.HiveRecommender()
    services = self.scratchdir_services(["CORE", "ZOOKEEPER", "OZONE"], "OZONE", "/tmp/hive")
    self.assertEqual("/user/hive/scratch", recommender.getHiveOzoneScratchDir({}, services))

  def test_scratchdir_chosen_by_the_user_or_on_hdfs_is_kept(self):
    recommender = self.service_advisor_impl.HiveRecommender()
    services = self.scratchdir_services(["CORE", "ZOOKEEPER", "OZONE"], "OZONE", "/warehouse/tablespace/scratch")
    self.assertIsNone(recommender.getHiveOzoneScratchDir({}, services))
    services = self.scratchdir_services(["CORE", "ZOOKEEPER", "HDFS"], "HDFS", "/tmp/hive")
    self.assertIsNone(recommender.getHiveOzoneScratchDir({}, services))

  def test_hiveserver2_with_yarn_and_tez_is_valid(self):
    services = self.services(["CORE", "ZOOKEEPER", "HDFS", "YARN", "TEZ"],
                             {"HIVE_METASTORE": ["h1"], "HIVE_SERVER": ["h2"], "HIVE_CLIENT": ["h1", "h2"]})

    self.assertEqual([], self.layout_errors(services))
