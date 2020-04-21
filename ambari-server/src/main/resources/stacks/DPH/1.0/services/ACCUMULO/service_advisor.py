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

from resource_management.core.logger import Logger

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STACKS_DIR = os.path.join(SCRIPT_DIR, '../../../../../stacks/')
PARENT_FILE = os.path.join(STACKS_DIR, 'service_advisor.py')

try:
  if "BASE_SERVICE_ADVISOR" in os.environ:
    PARENT_FILE = os.environ["BASE_SERVICE_ADVISOR"]
  with open(PARENT_FILE, 'rb') as fp:
    service_advisor = imp.load_module('service_advisor', fp, PARENT_FILE, ('.py', 'rb', imp.PY_SOURCE))
except Exception as e:
  traceback.print_exc()
  print "Failed to load parent"

class ACCUMULOServiceAdvisor(service_advisor.ServiceAdvisor):

  def __init__(self, *args, **kwargs):
    self.as_super = super(ACCUMULOServiceAdvisor, self)
    self.as_super.__init__(*args, **kwargs)

    # Always call these methods
    self.modifyMastersWithMultipleInstances()
    self.modifyCardinalitiesDict()
    self.modifyHeapSizeProperties()

  def modifyMastersWithMultipleInstances(self):
    """
    Modify the set of masters with multiple instances.
    Must be overriden in child class.
    """
    self.mastersWithMultipleInstances.add("ACCUMULO_MASTER")

  def modifyCardinalitiesDict(self):
    """
    Modify the dictionary of cardinalities.
    Must be overriden in child class.
    """
    self.cardinalitiesDict["ACCUMULO_MASTER"] = {"min": 1}

  def modifyHeapSizeProperties(self):
    """
    Modify the dictionary of heap size properties.
    Must be overriden in child class.
    """
    self.heap_size_properties = {"ACCUMULO_TSERVER":
                                   [{"config-name": "accumulo-env",
                                     "property": "accumulo_tserver_heapsize",
                                     "default": "1024m"}],
                                 "ACCUMULO_MASTER":
                                   [{"config-name": "accumulo-env",
                                     "property": "accumulo_master_heapsize",
                                     "default": "1024m"}]}

  def colocateService(self, hostsComponentsMap, serviceComponents):
    """
    Populate hostsComponentsMap with key = hostname and value = [{"name": "COMP_NAME_1"}, {"name": "COMP_NAME_2"}, ...]
    of services that must be co-hosted and on which host they should be present.
    :param hostsComponentsMap: Map from hostname to list of [{"name": "COMP_NAME_1"}, {"name": "COMP_NAME_2"}, ...]
    present on on that host.
    :param serviceComponents: Mapping of components
    If any components of the service should be colocated with other services,
    this is where you should set up that layout.  Example:
      # colocate HAWQSEGMENT with DATANODE, if no hosts have been allocated for HAWQSEGMENT
      hawqSegment = [component for component in serviceComponents if component["StackServiceComponents"]["component_name"] == "HAWQSEGMENT"][0]
      if not self.isComponentHostsPopulated(hawqSegment):
        for hostName in hostsComponentsMap.keys():
          hostComponents = hostsComponentsMap[hostName]
          if {"name": "DATANODE"} in hostComponents and {"name": "HAWQSEGMENT"} not in hostComponents:
            hostsComponentsMap[hostName].append( { "name": "HAWQSEGMENT" } )
          if {"name": "DATANODE"} not in hostComponents and {"name": "HAWQSEGMENT"} in hostComponents:
            hostComponents.remove({"name": "HAWQSEGMENT"})
    """
    pass

  def getServiceComponentLayoutValidations(self, services, hosts):
    """
    Get a list of errors.
    Must be overriden in child class.
    """

    return self.getServiceComponentCardinalityValidations(services, hosts, "ACCUMULO")

  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
    """
    Entry point.
    Must be overriden in child class.
    """
    pass

  def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
    """
    Entry point.
    Validate configurations for the service. Return a list of errors.
    The code for this function should be the same for each Service Advisor.
    """
    return []


