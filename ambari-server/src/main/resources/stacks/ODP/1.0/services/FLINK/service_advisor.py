#!/usr/bin/env ambari-python-wrap
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

# Python imports
import importlib.util
import os
import traceback
import re
import socket
import fnmatch
import xml.etree.ElementTree as ET


from resource_management.core.logger import Logger

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STACKS_DIR = os.path.join(SCRIPT_DIR, '../../../../../stacks/')
PARENT_FILE = os.path.join(STACKS_DIR, 'service_advisor.py')

try:
  if "BASE_SERVICE_ADVISOR" in os.environ:
    PARENT_FILE = os.environ["BASE_SERVICE_ADVISOR"]
  with open(PARENT_FILE, 'rb') as fp:
    spec = importlib.util.spec_from_file_location('service_advisor', PARENT_FILE)
    service_advisor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(service_advisor)
except Exception as e:
  traceback.print_exc()
  print("Failed to load parent")

class FlinkServiceAdvisor(service_advisor.ServiceAdvisor):

  def __init__(self, *args, **kwargs):
    self.as_super = super(FlinkServiceAdvisor, self)
    self.as_super.__init__(*args, **kwargs)

    # Always call these methods
    self.modifyMastersWithMultipleInstances()
    self.modifyCardinalitiesDict()
    self.modifyHeapSizeProperties()
    self.modifyNotValuableComponents()
    self.modifyComponentsNotPreferableOnServer()
    self.modifyComponentLayoutSchemes()

  def modifyMastersWithMultipleInstances(self):
    """
    Modify the set of masters with multiple instances.
    Must be overriden in child class.
    """
    # Nothing to do
    pass

  def modifyCardinalitiesDict(self):
    """
    Modify the dictionary of cardinalities.
    Must be overriden in child class.
    """
    # Nothing to do
    pass

  def modifyHeapSizeProperties(self):
    """
    Modify the dictionary of heap size properties.
    Must be overriden in child class.

    """
    self.heap_size_properties = {"FLINK_JOBHISTORYSERVER":
                                   [{"config-name": "flink-conf",
                                     "property": "flink_daemon_memory",
                                     "default": "2048m"}]}

  def modifyNotValuableComponents(self):
    """
    Modify the set of components whose host assignment is based on other services.
    Must be overriden in child class.
    """
    # Nothing to do
    pass

  def modifyComponentsNotPreferableOnServer(self):
    """
    Modify the set of components that are not preferable on the server.
    Must be overriden in child class.
    """
    # Nothing to do
    pass

  def modifyComponentLayoutSchemes(self):
    """
    Modify layout scheme dictionaries for components.
    The scheme dictionary basically maps the number of hosts to
    host index where component should exist.
    Must be overriden in child class.
    """
    # Nothing to do
    pass

  def getServiceComponentLayoutValidations(self, services, hosts):
    """
    Get a list of errors.
    Must be overriden in child class.
    """

    return self.getServiceComponentCardinalityValidations(services, hosts, "SPARK2")

  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
    """
    Entry point.
    Must be overriden in child class.
    """
    #Logger.info("Class: %s, Method: %s. Recommending Service Configurations." %
    #            (self.__class__.__name__, inspect.stack()[0][3]))

    recommender = FlinkRecommender()
    recommender.recommendFlinkConfigurationFromODP(configurations, clusterData, services, hosts)



  def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
    """
    Entry point.
    Validate configurations for the service. Return a list of errors.
    The code for this function should be the same for each Service Advisor.
    """
    #Logger.info("Class: %s, Method: %s. Validating Configurations." %
    #            (self.__class__.__name__, inspect.stack()[0][3]))

    validator = FlinkValidator()
    # Calls the methods of the validator using arguments,
    # method(siteProperties, siteRecommendations, configurations, services, hosts)
    return validator.validateListOfConfigUsingMethod(configurations, recommendedDefaults, services, hosts, validator.validators)

  def isComponentUsingCardinalityForLayout(self, componentName):
    return componentName in ('SPARK2_THRIFTSERVER', 'SPARK2_LIVY2_SERVER','SPARK3_LIVY2_SERVER')

  @staticmethod
  def isKerberosEnabled(services, configurations):
    """
    Determines if security is enabled by testing the value of flink-conf/security.kerberos.login.principal enabled.
    If the property exists and is equal to "true", then is it enabled; otherwise is it assumed to be
    disabled.

    :type services: dict
    :param services: the dictionary containing the existing configuration values
    :type configurations: dict
    :param configurations: the dictionary containing the updated configuration values
    :rtype: bool
    :return: True or False
    """
    if configurations["flink-conf"]:
      if configurations and "flink-conf" in configurations and \
              "security.kerberos.login.principal" in configurations["flink-conf"]["properties"]:
        return True
      else:
        return False
    else:
      return False


class FlinkRecommender(service_advisor.ServiceAdvisor):
  """
  Flink Recommender suggests properties when adding the service for the first time or modifying configs via the UI.
  """

  def __init__(self, *args, **kwargs):
    self.as_super = super(FlinkRecommender, self)
    self.as_super.__init__(*args, **kwargs)

  def recommendFlinkConfigurationFromODP(self, configurations, clusterData, services, hosts):
    """
    :type configurations dict
    :type clusterData dict
    :type services dict
    :type hosts dict
    """
    putClusterProperties = self.putProperty(configurations, "flink-conf", services)
    putFlinkEnvProperty = self.putProperty(configurations, "flink-env", services)
    #Logger.info("No Recommendation vailable for Flink Recommendation Stack advisor")
    #            (self.__class__.__name__, inspect.stack()[0][3]))

    preferred_fs_type = self.getCoreFilesystemType(configurations, services)
    putFlinkEnvProperty("flink_filesystem_type", preferred_fs_type)

    if 'flink-conf' in services["configurations"]:
      defaultFs = self.getServiceDefaultFs(configurations, services, "flink-env", "flink_filesystem_type")
      completedJobs = self.getConfigProperty(configurations, services, "flink-conf", "jobmanager.archive.fs.dir", "/apps/flink/completed-jobs")
      checkpointsDir = self.getConfigProperty(configurations, services, "flink-conf", "state.checkpoints.dir", "/apps/odp/flink/flink-checkpoints")
      savepointsDir = self.getConfigProperty(configurations, services, "flink-conf", "state.savepoints.dir", "/apps/odp/flink/flink-checkpoints")

      putClusterProperties("fs.default-scheme", defaultFs)
      putClusterProperties("jobmanager.archive.fs.dir", self.qualifyPathWithFs(defaultFs, completedJobs))
      putClusterProperties("state.checkpoints.dir", self.qualifyPathWithFs(defaultFs, checkpointsDir))
      putClusterProperties("state.savepoints.dir", self.qualifyPathWithFs(defaultFs, savepointsDir))

      ## configure HighAvailability with Zookeeper
      zk_host_port = self.getZKHostPortString(services)
      putClusterProperties("high-availability.zookeeper.quorum", zk_host_port)

    if 'yarn-env' in services["configurations"]:
      yarn_user = 'yarn'
      if "yarn_user" in services["configurations"]['yarn-env']["properties"]:
        yarn_user = services["configurations"]['yarn-env']["properties"]["yarn_user"]
      putClusterProperties("security.kerberos.token.provider.hadoopfs.renewer", yarn_user)

    self.putProperty(configurations, "flink-conf", services)


class FlinkValidator(service_advisor.ServiceAdvisor):
  """
  Flink Validator checks the correctness of properties whenever the service is first added or the user attempts to
  change configs via the UI.
  """

  def __init__(self, *args, **kwargs):
    self.as_super = super(FlinkValidator, self)
    self.as_super.__init__(*args, **kwargs)
    self.validators = []
    self.validators = [("flink-conf", self.validateFlinkConfFromODP10)]


  def validateFlinkConfFromODP10(self, properties, recommendedDefaults, configurations, services, hosts):
    servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
    include_hdfs = "HDFS" in servicesList
    defaultFS = services["configurations"]['flink-conf']["properties"]["fs.default-scheme"]
    flinkFsType = self.getConfigProperty(configurations, services, "flink-env", "flink_filesystem_type", self.getCoreFilesystemType(configurations, services))
    validationItems = []
    if include_hdfs and str(flinkFsType).upper() == "HDFS" and defaultFS.startswith('file:///'):
      # TODO: Memory Settings Recommendation
      validationItems.extends([
        {
          "config-name": 'fs.default-scheme',
          "item": self.getWarnItem("You should use hdfs:// FS for Production Flink Cluster ")
        }
      ])
    if str(flinkFsType).upper() == "HDFS" and not include_hdfs:
      validationItems.extends([
        {
          "config-name": 'flink_filesystem_type',
          "item": self.getWarnItem("HDFS is not installed. Select OZONE or EXTERNAL for flink-env/flink_filesystem_type.")
        }
      ])
    return self.toConfigurationValidationProblems(validationItems, "flink-conf")
