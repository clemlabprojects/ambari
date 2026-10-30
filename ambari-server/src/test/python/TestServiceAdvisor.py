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
import imp
import json
import os
from unittest import TestCase


class TestServiceAdvisor(TestCase):
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

  serviceAdvisorPath = os.path.join(resources_path, 'stacks/service_advisor.py')
  with open(serviceAdvisorPath, 'rb') as fp:
    service_advisor_impl = imp.load_module('service_advisor_impl', fp, serviceAdvisorPath, ('.py', 'rb', imp.PY_SOURCE))

  def setUp(self):
    serviceAdvisorClass = getattr(self.service_advisor_impl, 'ServiceAdvisor')
    self.serviceAdvisor = serviceAdvisorClass()

  def load_json(self, filename):
    file = os.path.join(self.test_directory, filename)
    with open(file, 'rb') as f:
      data = json.load(f)
    return data

  def test_getServiceComponentCardinalityValidations(self):
    """ Test getServiceComponentCardinalityValidations """
    services = self.load_json("hdfs.json")
    hosts = self.load_json("validation-hosts.json")

    validations = self.serviceAdvisor.getServiceComponentCardinalityValidations(services, hosts, "HDFS")
    self.assertEqual(len(validations), 1)
    expected = {
      "type": 'host-component',
      "level": 'ERROR',
      "component-name": 'DATANODE',
      "message": 'You have selected 0 DataNode components. Please consider that at least 1 DataNode components should be installed in cluster.'
    }
    self.assertEqual(validations[0], expected)

    validations = self.serviceAdvisor.getServiceComponentCardinalityValidations(services, hosts, "HBASE")
    self.assertEqual(len(validations), 0)

    services["services"][0]["components"][0]["StackServiceComponents"]["hostnames"].append("c7402.ambari.apache.org")

    validations = self.serviceAdvisor.getServiceComponentCardinalityValidations(services, hosts, "HDFS")
    self.assertEqual(len(validations), 0)

  def test_getPreferredFilesystemType(self):
    self.assertEqual("HDFS", self.serviceAdvisor.getPreferredFilesystemType({
      "services": [{"StackServices": {"service_name": "HDFS"}}]
    }))
    self.assertEqual("OZONE", self.serviceAdvisor.getPreferredFilesystemType({
      "services": [{"StackServices": {"service_name": "OZONE"}}]
    }))
    self.assertEqual("EXTERNAL", self.serviceAdvisor.getPreferredFilesystemType({
      "services": [{"StackServices": {"service_name": "YARN"}}]
    }))

  def test_getCoreFilesystemTypeFallsBackToExternalWithoutFsServices(self):
    services = {
      "services": [{"StackServices": {"service_name": "YARN"}}],
      "configurations": {
        "core-env": {
          "properties": {
            "core_filesystem_type": "HDFS"
          }
        }
      }
    }

    self.assertEqual("EXTERNAL", self.serviceAdvisor.getCoreFilesystemType({}, services))

  def test_filesystemSelectorsFallBackToOzoneWithoutHdfs(self):
    services = {
      "services": [{"StackServices": {"service_name": "OZONE"}}, {"StackServices": {"service_name": "HIVE"}}],
      "configurations": {
        "core-env": {
          "properties": {
            "core_filesystem_type": "HDFS"
          }
        },
        "hive-env": {
          "properties": {
            "hive_filesystem_type": "HDFS"
          }
        },
        "ozone-site": {
          "properties": {
            "ozone.om.service.ids": "omservice"
          }
        }
      }
    }

    self.assertEqual("OZONE", self.serviceAdvisor.getCoreFilesystemType({}, services))
    self.assertEqual("OZONE", self.serviceAdvisor.getSelectedFilesystemType({}, services, "hive-env", "hive_filesystem_type"))
    self.assertEqual("ofs://omservice", self.serviceAdvisor.getServiceDefaultFs({}, services, "hive-env", "hive_filesystem_type"))

  def test_filesystemSelectorKeepsInstalledChoice(self):
    services = {
      "services": [{"StackServices": {"service_name": "HDFS"}}, {"StackServices": {"service_name": "OZONE"}}],
      "configurations": {
        "core-env": {
          "properties": {
            "core_filesystem_type": "OZONE"
          }
        }
      }
    }

    self.assertEqual("OZONE", self.serviceAdvisor.getCoreFilesystemType({}, services))

  def test_getOzonePathProblem(self):
    problem = self.serviceAdvisor.getOzonePathProblem
    self.assertIsNone(problem("/warehouse/tablespace/managed/hive", "ofs://omservice"))
    self.assertIsNone(problem("ofs://omservice/warehouse/tablespace/external/hive", "hdfs://nn:8020"))
    self.assertIsNone(problem("o3fs://tablespace.warehouse.omservice/managed/hive", "hdfs://nn:8020"))
    self.assertIsNone(problem("/warehouse/tablespace/managed/hive", "hdfs://nn:8020"))
    self.assertIsNone(problem("s3a://bucket/warehouse", "ofs://omservice"))
    self.assertIn("must be inside an Ozone bucket", problem("/warehouse", "ofs://omservice"))
    self.assertIn("must name its Ozone bucket and volume", problem("o3fs://warehouse/managed", "hdfs://nn:8020"))
    self.assertIn("not a valid Ozone bucket name", problem("/warehouse/Table_Space/managed", "ofs://omservice"))
    self.assertIn("not a valid Ozone volume name", problem("/wh/tablespace/managed", "ofs://omservice"))

  def test_isOzoneTmpPath(self):
    self.assertTrue(self.serviceAdvisor.isOzoneTmpPath("/tmp/hive", "ofs://omservice"))
    self.assertTrue(self.serviceAdvisor.isOzoneTmpPath("ofs://omservice/tmp/hive", "hdfs://nn:8020"))
    self.assertFalse(self.serviceAdvisor.isOzoneTmpPath("/user/hive/scratch", "ofs://omservice"))
    self.assertFalse(self.serviceAdvisor.isOzoneTmpPath("/tmp/hive", "hdfs://nn:8020"))

  def test_getServiceDefaultFsForExternal(self):
    services = {
      "services": [{"StackServices": {"service_name": "YARN"}}],
      "configurations": {
        "core-env": {
          "properties": {
            "core_filesystem_type": "EXTERNAL",
            "core_external_default_fs": "s3a://warehouse"
          }
        },
        "hive-env": {
          "properties": {
            "hive_filesystem_type": "EXTERNAL"
          }
        }
      }
    }

    self.assertEqual(
      "s3a://warehouse",
      self.serviceAdvisor.getServiceDefaultFs({}, services, "hive-env", "hive_filesystem_type")
    )

  def test_qualifyPathWithFs(self):
    self.assertEqual("s3a://warehouse/user/hive", self.serviceAdvisor.qualifyPathWithFs("s3a://warehouse", "/user/hive"))
    self.assertEqual("s3a://warehouse/user/hive", self.serviceAdvisor.qualifyPathWithFs("s3a://warehouse", "hdfs://nn:8020/user/hive"))
    self.assertEqual("{{my_var}}", self.serviceAdvisor.qualifyPathWithFs("s3a://warehouse", "{{my_var}}"))
