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
import inspect
from os.path import dirname
from ambari_server.serverConfiguration import get_ambari_properties, get_ambari_version

# Local imports
from resource_management.core.logger import Logger

SCRIPT_DIR = dirname(os.path.abspath(__file__))
RESOURCES_DIR = dirname(dirname(dirname(SCRIPT_DIR)))
STACKS_DIR = os.path.join(RESOURCES_DIR, 'stacks')
PARENT_FILE = os.path.join(STACKS_DIR, 'service_advisor.py')

try:
  with open(PARENT_FILE, 'rb') as fp:
    service_advisor = imp.load_module('service_advisor', fp, PARENT_FILE, ('.py', 'rb', imp.PY_SOURCE))
except Exception as e:
  traceback.print_exc()
  print "Failed to load parent"

DB_TYPE_DEFAULT_PORT_MAP = {"mysql":"3306", "oracle":"1521", "postgresql":"5432"}

class REGISTRY030ServiceAdvisor(service_advisor.ServiceAdvisor):

  def __init__(self, *args, **kwargs):
    self.as_super = super(REGISTRY030ServiceAdvisor, self)
    self.as_super.__init__(*args, **kwargs)
    Logger.initialize_logger()

  def getServiceConfigurationRecommenderDict(self):
    """
    Recommend configurations to set. Registry does not have any recommendations in this version.
    """
    Logger.info("Class: %s, Method: %s. Recommending Service Configurations." % (self.__class__.__name__, inspect.stack()[0][3]))
    return self.as_super.getServiceConfigurationRecommenderDict()

  def getServiceConfigurationValidators(self):
    """
    Get a list of errors. Registry does not have any validations in this version.
    """
    Logger.info("Class: %s, Method: %s. Validating Service Component Layout." % (self.__class__.__name__, inspect.stack()[0][3]))
    return self.as_super.getServiceConfigurationValidators()

  def recommendConfigurations(self, configurations, clusterData, services, hosts):
    """
    Recommend configurations for this service.
    """
    Logger.info("Class: %s, Method: %s. Recommending Service Configurations." % (self.__class__.__name__, inspect.stack()[0][3]))
    pass

  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
    Logger.info("Class: %s, Method: %s. get Service Configurations Recommendations. " % (self.__class__.__name__, inspect.stack()[0][3]))
    properties = get_ambari_properties()
    ambari_version = get_ambari_version(properties)
    if not(ambari_version) or not(ambari_version.startswith('2.5')):
      putRegistryLogSearchConfAttribute = self.putPropertyAttribute(configurations, "registry-logsearch-conf")
      putRegistryLogSearchConfAttribute('service_name', 'visible', 'false')
      putRegistryLogSearchConfAttribute('component_mappings', 'visible', 'false')
      putRegistryLogSearchConfAttribute('content', 'visible', 'false')

  def validateREGISTRYConfigurations(self, properties, recommendedDefaults, configurations, services, hosts):
    registry_common = properties
    validationItems = []
    url_error_message = ""
    password_error_message = ""

    #Find number of services installed, get them all and find registry service json obj in them.
    number_services = len(services['services'])
    for each_service in range(0, number_services):
      if services['services'][each_service]['components'][0]['StackServiceComponents']['service_name'] == 'REGISTRY':

        #Errors related to httpProxyServer for registry
        http_proxy_server_url = registry_common['httpProxyServer']
        if http_proxy_server_url:
          from urlparse import urlparse
          url_list = urlparse(http_proxy_server_url)

          # if missing protocol or hostname:port_number
          if (not url_list[0] or not url_list[1]):
            url_error_message += "Please enter httpProxyServer in following format : protocol_name://httpProxy_host_name:port_number"
            validationItems.append({"config-name": 'httpProxyServer', "item": self.getErrorItem(url_error_message)})
          else:
            try:
              httpProxy_hostname = url_list[1].split(":")[0]
              httpProxy_port = url_list[1].split(":")[1]
              # empty hostname or empty port_number
              if len(httpProxy_hostname) < 1 or len(httpProxy_port) < 1:
                url_error_message += "Please enter httpProxyServer in following format : protocol_name://httpProxy_host_name:port_number"
                validationItems.append({"config-name": 'httpProxyServer', "item": self.getErrorItem(url_error_message)})
            # only hostname or only port_number
            except:
              url_error_message += "Please enter httpProxyServer in following format : protocol_name://httpProxy_host_name:port_number"
              validationItems.append({"config-name": 'httpProxyServer', "item": self.getErrorItem(url_error_message)})

        # Errors related to absence of httpProxyServer and httpProxyPassword for registry
        http_proxy_server_password = registry_common['httpProxyPassword']
        http_proxy_server_username = registry_common['httpProxyUsername']
        if http_proxy_server_url and not ((http_proxy_server_password and http_proxy_server_username) or (
          not http_proxy_server_password and not http_proxy_server_username)):
          if not http_proxy_server_password:
            password_error_message = "Please provide the httpProxyPassword"
            validationItems.append({"config-name": 'httpProxyPassword', "item": self.getErrorItem(password_error_message)})
          elif not http_proxy_server_username:
            username_error_message = "Please provide the httpProxyUsername"
            validationItems.append({"config-name": 'httpProxyUsername', "item": self.getErrorItem(username_error_message)})
        elif not http_proxy_server_url and (http_proxy_server_password or http_proxy_server_username):
          url_error_message += "Please enter httpProxyServer in following format : protocol_name://httpProxy_host_name:port_number"
          validationItems.append({"config-name": 'httpProxyServer', "item": self.getErrorItem(url_error_message)})

    return self.toConfigurationValidationProblems(validationItems, "registry-common")

  def validateConfigurationsForSite(self, configurations, recommendedDefaults, services, hosts, siteName, method):
   properties = self.getSiteProperties(configurations, siteName)
   if properties:
        if siteName == 'registry-common':
                return method(properties, None, configurations, services, hosts)
        else:
                return super(REGISTRY030ServiceAdvisor, self).validateConfigurationsForSite(configurations, recommendedDefaults, services, hosts, siteName, method)
   else:
        return []

  def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
    """
    Validate configurations for the service. Return a list of errors.
    """
    siteName = "registry-common"
    method = self.validateREGISTRYConfigurations
    items = self.validateConfigurationsForSite(configurations, recommendedDefaults, services, hosts, siteName, method)
    return items
    Logger.info("Class: %s, Method: %s. Validating Service Configuration Items." % (self.__class__.__name__, inspect.stack()[0][3]))

    return items

  def getCardinalitiesDict(self, hosts):
    return {'REGISTRY_SERVER': {"min": 1}}

  def putPropertyAttribute(self, config, configType):
    if configType not in config:
      config[configType] = {}

    def appendPropertyAttribute(key, attribute, attributeValue):
      if "property_attributes" not in config[configType]:
        if "property_attributes" not in config[configType]:
          config[configType]["property_attributes"] = {}
      if key not in config[configType]["property_attributes"]:
        config[configType]["property_attributes"][key] = {}
      config[configType]["property_attributes"][key][attribute] = attributeValue if isinstance(attributeValue,
                                                                                               list) else str(
        attributeValue)

    return appendPropertyAttribute