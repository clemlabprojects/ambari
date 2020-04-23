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

import imp
import os
import socket
import traceback

from resource_management.core.logger import Logger

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STACKS_DIR = os.path.join(SCRIPT_DIR, "../../../../")
PARENT_FILE = os.path.join(STACKS_DIR, "service_advisor.py")

try:
  with open(PARENT_FILE, "rb") as fp:
    service_advisor = imp.load_module("service_advisor", fp, PARENT_FILE, (".py", "rb", imp.PY_SOURCE))
except Exception as e:
  traceback.print_exc()
  print "Failed to load parent"

class SupersetServiceAdvisor(service_advisor.ServiceAdvisor):

  def __init__(self, *args, **kwargs):
    self.as_super = super(SupersetServiceAdvisor, self)
    self.as_super.__init__(*args, **kwargs)

    self.initialize_logger("SupersetServiceAdvisor")

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

    return self.getServiceComponentCardinalityValidations(services, hosts, "SUPERSET")

  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
    self.logger.info("Class: %s, Method: %s. Recommending Service Configurations." %
                (self.__class__.__name__, inspect.stack()[0][3]))

    recommender = SupersetRecommender()
    recommender.recommendSupersetConfigurationsFromHDP30(configurations, clusterData, services, hosts)


  def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
    """
    Entry point.
    Validate configurations for the service. Return a list of errors.
    The code for this function should be the same for each Service Advisor.
    """
    self.logger.info("Class: %s, Method: %s. Validating Configurations." %
                (self.__class__.__name__, inspect.stack()[0][3]))
    return []

class SupersetRecommender(service_advisor.ServiceAdvisor):
  """
  Superset Recommender suggests properties when adding the service for the first time or modifying configs via the UI.
  """

  def __init__(self, *args, **kwargs):
    self.as_super = super(SupersetRecommender, self)
    self.as_super.__init__(*args, **kwargs)


  def recommendSupersetConfigurationsFromHDP30(self, configurations, clusterData, services, hosts):
    # superset is in list of services to be installed
    if 'superset' in services['configurations']:
      # Recommendations for Superset
      superset_database_type = services['configurations']["superset"]["properties"]["SUPERSET_DATABASE_TYPE"]
      putSupersetProperty = self.putProperty(configurations, "superset", services)

      if superset_database_type == "mysql":
        putSupersetProperty("SUPERSET_DATABASE_PORT", "3306")
      elif superset_database_type == "postgresql":
        putSupersetProperty("SUPERSET_DATABASE_PORT", "5432")
      elif superset_database_type == "sqlite":
        putSupersetProperty("SUPERSET_DATABASE_PORT", "")
