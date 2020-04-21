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
import imp
import os
import traceback
import re
import socket
import fnmatch
import json

from resource_management.libraries.functions import format

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STACKS_DIR = os.path.join(SCRIPT_DIR, "../../../../")
PARENT_FILE = os.path.join(STACKS_DIR, "service_advisor.py")

try:
  if "BASE_SERVICE_ADVISOR" in os.environ:
    PARENT_FILE = os.environ["BASE_SERVICE_ADVISOR"]
  with open(PARENT_FILE, "rb") as fp:
    service_advisor = imp.load_module("service_advisor", fp, PARENT_FILE, (".py", "rb", imp.PY_SOURCE))
except Exception as e:
  traceback.print_exc()
  print "Failed to load parent"

class DruidServiceAdvisor(service_advisor.ServiceAdvisor):

  def __init__(self, *args, **kwargs):
    self.as_super = super(DruidServiceAdvisor, self)
    self.as_super.__init__(*args, **kwargs)

    self.initialize_logger("DruidServiceAdvisor")

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
    pass

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
    pass

  def getServiceComponentLayoutValidations(self, services, hosts):
    """
    Get a list of errors.
    Must be overriden in child class.
    """

    return self.getServiceComponentCardinalityValidations(services, hosts, "DRUID")

  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
    """
    Entry point.
    Must be overriden in child class.
    """
    self.logger.info("Class: %s, Method: %s. Recommending Service Configurations." %
                (self.__class__.__name__, inspect.stack()[0][3]))

    recommender = DruidRecommender()
    recommender.recommendDruidConfigurationsFromHDP30(configurations, clusterData, services, hosts)



  def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
    """
    Entry point.
    Validate configurations for the service. Return a list of errors.
    The code for this function should be the same for each Service Advisor.
    """
    self.logger.info("Class: %s, Method: %s. Validating Configurations." %
                (self.__class__.__name__, inspect.stack()[0][3]))

    validator = DruidValidator()
    # Calls the methods of the validator using arguments,
    # method(siteProperties, siteRecommendations, configurations, services, hosts)
    return validator.validateListOfConfigUsingMethod(configurations, recommendedDefaults, services, hosts, validator.validators)



class DruidRecommender(service_advisor.ServiceAdvisor):
  """
  Druid Recommender suggests properties when adding the service for the first time or modifying configs via the UI.
  """

  def __init__(self, *args, **kwargs):
    self.as_super = super(DruidRecommender, self)
    self.as_super.__init__(*args, **kwargs)


  def recommendDruidConfigurationsFromHDP30(self, configurations, clusterData, services, hosts):

      # druid is not in list of services to be installed
      if 'druid-common' not in services['configurations']:
        return

      componentsListList = [service["components"] for service in services["services"]]
      componentsList = [item["StackServiceComponents"] for sublist in componentsListList for item in sublist]
      servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
      putCommonProperty = self.putProperty(configurations, "druid-common", services)

      putCommonProperty('druid.zk.service.host', self.getZKHostPortString(services))
      self.recommendDruidMaxMemoryLimitConfigurations(configurations, clusterData, services, hosts)

      # recommending the metadata storage uri
      database_name = services['configurations']["druid-common"]["properties"]["database_name"]
      metastore_hostname = services['configurations']["druid-common"]["properties"]["metastore_hostname"]
      database_type = services['configurations']["druid-common"]["properties"]["druid.metadata.storage.type"]
      metadata_storage_port = "1527"
      mysql_module_name = "mysql-metadata-storage"
      postgres_module_name = "postgresql-metadata-storage"
      extensions_load_list = services['configurations']['druid-common']['properties']['druid.extensions.loadList']
      putDruidCommonProperty = self.putProperty(configurations, "druid-common", services)

      extensions_load_list = self.removeFromList(extensions_load_list, mysql_module_name)
      extensions_load_list = self.removeFromList(extensions_load_list, postgres_module_name)

      if database_type == 'mysql':
          metadata_storage_port = "3306"
          extensions_load_list = self.addToList(extensions_load_list, mysql_module_name)

      if database_type == 'postgresql':
          extensions_load_list = self.addToList(extensions_load_list, postgres_module_name)
          metadata_storage_port = "5432"

      putDruidCommonProperty('druid.metadata.storage.connector.port', metadata_storage_port)
      putDruidCommonProperty('druid.metadata.storage.connector.connectURI',
                             self.getMetadataConnectionString(database_type).format(metastore_hostname, database_name,
                                                                                    metadata_storage_port))
      # HDFS is installed
      if "HDFS" in servicesList and "hdfs-site" in services["configurations"]:
          # recommend HDFS as default deep storage
          extensions_load_list = self.addToList(extensions_load_list, "druid-hdfs-storage")
          putCommonProperty("druid.storage.type", "hdfs")
          putCommonProperty("druid.storage.storageDirectory", "/apps/druid/warehouse")
          # configure indexer logs configs
          putCommonProperty("druid.indexer.logs.type", "hdfs")
          putCommonProperty("druid.indexer.logs.directory", "/user/druid/logs")

      if "KAFKA" in servicesList:
          extensions_load_list = self.addToList(extensions_load_list, "druid-kafka-indexing-service")

      if 'AMBARI_METRICS' in servicesList:
        extensions_load_list = self.addToList(extensions_load_list, "ambari-metrics-emitter")

      putCommonProperty('druid.extensions.loadList', extensions_load_list)

      # JVM Configs go to env properties
      putEnvProperty = self.putProperty(configurations, "druid-env", services)

      # processing thread pool and memory configs
      for component in ['DRUID_HISTORICAL', 'DRUID_BROKER']:
          component_hosts = self.getHostsWithComponent("DRUID", component, services, hosts)
          nodeType = self.DRUID_COMPONENT_NODE_TYPE_MAP[component]
          putComponentProperty = self.putProperty(configurations, format("druid-{nodeType}"), services)
          if (component_hosts is not None and len(component_hosts) > 0):
              totalAvailableCpu = self.getMinCpu(component_hosts)
              processingThreads = 1
              if totalAvailableCpu > 1:
                  processingThreads = totalAvailableCpu - 1
              numMergeBuffers = max(2, processingThreads/4)
              putComponentProperty('druid.processing.numThreads', processingThreads)
              putComponentProperty('druid.server.http.numThreads', max(10, (totalAvailableCpu * 17) / 16 + 2) + 30)
              putComponentProperty('druid.processing.numMergeBuffers', numMergeBuffers)
              totalAvailableMemInMb = self.getMinMemory(component_hosts) / 1024
              maxAvailableBufferSizeInMb = totalAvailableMemInMb/(processingThreads + numMergeBuffers)
              putComponentProperty('druid.processing.buffer.sizeBytes', self.getDruidProcessingBufferSizeInMb(maxAvailableBufferSizeInMb) * 1024 * 1024)


  # returns the recommended druid processing buffer size in Mb.
  # the recommended buffer size is kept lower then the max available memory to have enough free memory to load druid data.
  # for low memory nodes, the actual allocated buffer size is small to keep some free memory for memory mapping of segments
  # If user installs all druid processes on a single node, memory available for loading segments will be further decreased.
  def getDruidProcessingBufferSizeInMb(self, maxAvailableBufferSizeInMb):
      if maxAvailableBufferSizeInMb <= 256:
          return min(64, maxAvailableBufferSizeInMb)
      elif maxAvailableBufferSizeInMb <= 1024:
          return 128
      elif maxAvailableBufferSizeInMb <= 2048:
          return 256
      elif maxAvailableBufferSizeInMb <= 6144:
          return 512
      # High Memory nodes below
      else :
          return 1024

  def getMetadataConnectionString(self, database_type):
      driverDict = {
          'mysql': 'jdbc:mysql://{0}:{2}/{1}?createDatabaseIfNotExist=true',
          'derby': 'jdbc:derby://{0}:{2}/{1};create=true',
          'postgresql': 'jdbc:postgresql://{0}:{2}/{1}'
      }
      return driverDict.get(database_type.lower())

  def addToList(self, json_list, word):
      desr_list = json.loads(json_list)
      if word not in desr_list:
          desr_list.append(word)
      return json.dumps(desr_list)

  def removeFromList(self, json_list, word):
      desr_list = json.loads(json_list)
      if word in desr_list:
          desr_list.remove(word)
      return json.dumps(desr_list)

  def recommendDruidMaxMemoryLimitConfigurations(self, configurations, clusterData, services, hosts):
      putEnvPropertyAttribute = self.putPropertyAttribute(configurations, "druid-env")
      for component in ["DRUID_HISTORICAL", "DRUID_MIDDLEMANAGER", "DRUID_BROKER", "DRUID_OVERLORD",
                        "DRUID_COORDINATOR"]:
          component_hosts = self.getHostsWithComponent("DRUID", component, services, hosts)
          if component_hosts is not None and len(component_hosts) > 0:
              totalAvailableMem = self.getMinMemory(component_hosts) / 1024  # In MB
              nodeType = self.DRUID_COMPONENT_NODE_TYPE_MAP[component]
              putEnvPropertyAttribute(format('druid.{nodeType}.jvm.heap.memory'), 'maximum',
                                      max(totalAvailableMem, 1024))

  DRUID_COMPONENT_NODE_TYPE_MAP = {
      'DRUID_BROKER': 'broker',
      'DRUID_COORDINATOR': 'coordinator',
      'DRUID_HISTORICAL': 'historical',
      'DRUID_MIDDLEMANAGER': 'middlemanager',
      'DRUID_OVERLORD': 'overlord',
      'DRUID_ROUTER': 'router'
  }

  def getMinMemory(self, component_hosts):
      min_ram_kb = 1073741824  # 1 TB
      for host in component_hosts:
          ram_kb = host['Hosts']['total_mem']
          min_ram_kb = min(min_ram_kb, ram_kb)
      return min_ram_kb

  def getMinCpu(self, component_hosts):
      min_cpu = 256
      for host in component_hosts:
          cpu_count = host['Hosts']['cpu_count']
          min_cpu = min(min_cpu, cpu_count)
      return min_cpu


class DruidValidator(service_advisor.ServiceAdvisor):
  """
  Druid Validator checks the correctness of properties whenever the service is first added or the user attempts to
  change configs via the UI.
  """

  def __init__(self, *args, **kwargs):
    self.as_super = super(DruidValidator, self)
    self.as_super.__init__(*args, **kwargs)

    self.validators = [("druid-env", self.validateDruidEnvConfigurationsFromHDP30),
                       ("druid-historical", self.validateDruidHistoricalConfigurationsFromHDP30),
                       ("druid-broker", self.validateDruidBrokerConfigurationsFromHDP30)]

  def validateDruidEnvConfigurationsFromHDP30(self, properties, recommendedDefaults, configurations, services, hosts):
      validationItems = []
      #  Minimum Direct memory Validation
      envProperties = services['configurations']['druid-env']['properties']
      for nodeType in ['broker', 'historical']:
          properties = services['configurations'][format('druid-{nodeType}')]['properties']
          intermediateBufferSize = int(properties['druid.processing.buffer.sizeBytes']) / (1024 * 1024)  # In MBs
          processingThreads = int(properties['druid.processing.numThreads'])
          directMemory = int(envProperties[format('druid.{nodeType}.jvm.direct.memory')])
          if directMemory < (processingThreads + 1) * intermediateBufferSize:
              validationItems.extend(
                  {"config-name": format("druid.{nodeType}.jvm.direct.memory"), "item": self.getErrorItem(
                      format(
                          "Not enough direct memory available for {nodeType} Node."
                          "Please adjust druid.{nodeType}.jvm.direct.memory, druid.processing.buffer.sizeBytes, druid.processing.numThreads"
                      )
                  )
                   })
      return self.toConfigurationValidationProblems(validationItems, "druid-env")

  def validateDruidHistoricalConfigurationsFromHDP30(self, properties, recommendedDefaults, configurations, services, hosts):
      validationItems = [
          {"config-name": "druid.processing.numThreads",
           "item": self.validatorEqualsToRecommendedItem(properties, recommendedDefaults,
                                                         "druid.processing.numThreads")}
      ]
      return self.toConfigurationValidationProblems(validationItems, "druid-historical")

  def validateDruidBrokerConfigurationsFromHDP30(self, properties, recommendedDefaults, configurations, services, hosts):
        validationItems = [
            {"config-name": "druid.processing.numThreads",
             "item": self.validatorEqualsToRecommendedItem(properties, recommendedDefaults,
                                                           "druid.processing.numThreads")}
        ]
        return self.toConfigurationValidationProblems(validationItems, "druid-broker")
