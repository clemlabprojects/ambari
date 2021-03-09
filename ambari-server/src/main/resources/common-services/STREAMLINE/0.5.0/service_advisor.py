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
import os, re
import traceback
import inspect
from os.path import dirname

# Local imports
from resource_management.core.logger import Logger
from ambari_server.serverConfiguration import get_ambari_properties, get_ambari_version
from resource_management.libraries.functions.get_bare_principal import get_bare_principal

SCRIPT_DIR = dirname(dirname(dirname(dirname(os.path.abspath(__file__)))))
STACKS_DIR = os.path.join(SCRIPT_DIR,'stacks')
PARENT_FILE = os.path.join(STACKS_DIR, 'service_advisor.py')

try:
    with open(PARENT_FILE, 'rb') as fp:
        service_advisor = imp.load_module('service_advisor', fp, PARENT_FILE, ('.py', 'rb', imp.PY_SOURCE))
except Exception as e:
    traceback.print_exc()
    print "Failed to load parent"

DB_TYPE_DEFAULT_PORT_MAP = {"mysql":"3306", "oracle":"1521", "postgresql":"5432"}

class STREAMLINE050ServiceAdvisor(service_advisor.ServiceAdvisor):

  def __init__(self, *args, **kwargs):
    self.as_super = super(STREAMLINE050ServiceAdvisor, self)
    self.as_super.__init__(*args, **kwargs)
    Logger.initialize_logger()

  def getServiceConfigurationRecommenderDict(self):
    """
    Recommend configurations to set. Streamline does not have any recommendations in this version.
    """
    Logger.info("Class: %s, Method: %s. Recommending Service Configurations." % (self.__class__.__name__, inspect.stack()[0][3]))
    return self.as_super.getServiceConfigurationRecommenderDict()

  def getServiceConfigurationValidators(self):
    """
    Get a list of errors. Streamline does not have any validations in this version.
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

    Logger.info("Class: %s, Method: %s. Get Service Configuration Recommendations." % (self.__class__.__name__, inspect.stack()[0][3]))
    servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
    security_enabled = self.isSecurityEnabled(services)

    if 'AMBARI_METRICS' in servicesList:
      putAmsSiteProperty = self.putProperty(configurations, "ams-site")
      putAmsSiteProperty('timeline.metrics.downsampler.event.metric.patterns', 'topology\.%')

    if 'STORM' in servicesList and security_enabled:
      storm_site = self.getServicesSiteProperties(services, "storm-site")
      streamline_env = self.getServicesSiteProperties(services, "streamline-env")
      if storm_site is not None and streamline_env is not None:
        putStormSiteProperty = self.putProperty(configurations, "storm-site", services)
        putStormSiteAttributes = self.putPropertyAttribute(configurations, "storm-site")
        storm_env = self.getServicesSiteProperties(services, "storm-env")
        storm_nimbus_impersonation_acl = storm_site["nimbus.impersonation.acl"] if "nimbus.impersonation.acl" in storm_site else None
        streamline_env = self.getServicesSiteProperties(services, "streamline-env")
        _streamline_principal_name = streamline_env['streamline_principal_name'] if 'streamline_principal_name' in streamline_env else None

        if _streamline_principal_name is not None and storm_nimbus_impersonation_acl is not None:
          streamline_bare_principal = get_bare_principal(_streamline_principal_name)
          storm_nimbus_impersonation_acl = "{ " + streamline_bare_principal + " : {hosts: ['*'], groups: ['*']}, {{storm_bare_jaas_principal}} : {hosts: ['*'], groups: ['*']}}"
          putStormSiteProperty('nimbus.impersonation.acl', storm_nimbus_impersonation_acl)

        storm_nimbus_autocred_plugin_classes = storm_site["nimbus.autocredential.plugins.classes"] if "nimbus.autocredential.plugins.classes" in storm_site else None

        # Here storm_nimbus_autocred_plugin_classes is resulting in none. There is no nimbus.autocredential.plugins.classes in storm-site.xml

        if storm_nimbus_autocred_plugin_classes is not None:
          new_storm_nimbus_autocred_plugin_classes = ['org.apache.storm.hdfs.security.AutoHDFS',
                                                      'org.apache.storm.hbase.security.AutoHBase',
                                                      'org.apache.storm.hive.security.AutoHive']

          new_conf = self.appendToYamlString(storm_nimbus_autocred_plugin_classes,
                                             new_storm_nimbus_autocred_plugin_classes)
          putStormSiteProperty("nimbus.autocredential.plugins.classes", new_conf)

        else:
          putStormSiteProperty("nimbus.autocredential.plugins.classes",
                               "['org.apache.storm.hdfs.security.AutoHDFS', 'org.apache.storm.hbase.security.AutoHBase', 'org.apache.storm.hive.security.AutoHive']")

        storm_nimbus_credential_renewer_classes = storm_site["nimbus.credential.renewers.classes"] if "nimbus.credential.renewers.classes" in storm_site else None

        if storm_nimbus_credential_renewer_classes is not None:
          new_storm_nimbus_credential_renewer_classes_array = ['org.apache.storm.hdfs.security.AutoHDFS',
                                                               'org.apache.storm.hbase.security.AutoHBase',
                                                               'org.apache.storm.hive.security.AutoHive']
          new_conf = self.appendToYamlString(storm_nimbus_credential_renewer_classes,
                                             new_storm_nimbus_credential_renewer_classes_array)
          putStormSiteProperty("nimbus.autocredential.plugins.classes", new_conf)
        else:
          putStormSiteProperty("nimbus.credential.renewers.classes",
                               "['org.apache.storm.hdfs.security.AutoHDFS', 'org.apache.storm.hbase.security.AutoHBase', 'org.apache.storm.hive.security.AutoHive']")
        putStormSiteProperty("nimbus.credential.renewers.freq.secs", "82800")

    properties = get_ambari_properties()
    ambari_version = get_ambari_version(properties)
    if not (ambari_version) or not (ambari_version.startswith('2.5')):
      putStreamlineLogSearchConfAttribute = self.putPropertyAttribute(configurations, "streamline-logsearch-conf")
      putStreamlineLogSearchConfAttribute('service_name', 'visible', 'false')
      putStreamlineLogSearchConfAttribute('component_mappings', 'visible', 'false')
      putStreamlineLogSearchConfAttribute('content', 'visible', 'false')
    pass

  def validateSTREAMLINEConfigurations(self, properties, recommendedDefaults, configurations, services, hosts):
    streamline_common = properties
    validationItems = []
    warning_message = ""
    url_error_message = ""
    password_error_message = ""

    #Find number of services installed, get them all and find streamline service json obj in them.
    number_services = len(services['services'])
    for each_service in range(0, number_services):
      if services['services'][each_service]['components'][0]['StackServiceComponents']['service_name'] == 'STREAMLINE':

        # Warnings related to streamine is in HA mode
        num_streamline_nodes = len(
          services['services'][each_service]['components'][0]['StackServiceComponents']['hostnames'])
        if int(num_streamline_nodes) > 1:
          if streamline_common['jar.storage.type'] == "local":
            warning_message += "You choose 'local' option. Please choose HDFS or Database option. " \
                                "If your jar.storage.type=Database and if you choose MYSQL as Database, " \
                                "please make sure to set value of MYSQL's property max_allowed_packet larger " \
                                "than size of your udf or custom jar as it will be stored as blob in MYSQL."
            validationItems.append({"config-name": 'jar.storage.type', "item": self.getWarnItem(warning_message)})
          if streamline_common['jar.storage.type'] == "database":
            warning_message += "If choose 'Database' option. If you choose MYSQL as Database, " \
                              "please make sure to set value of MYSQL's property max_allowed_packet larger " \
                              "than size of your udf or custom jar as it will be stored as blob in MYSQL."
            validationItems.append({"config-name": 'jar.storage.type', "item": self.getWarnItem(warning_message)})

        # Errors related to httpProxyServer for streamline
        http_proxy_server_url = streamline_common['httpProxyServer']
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

        # Errors related to absence of httpProxyServer and httpProxyPassword for streamline.
        http_proxy_server_password = streamline_common['httpProxyPassword']
        http_proxy_server_username = streamline_common['httpProxyUsername']
        if http_proxy_server_url and not ((http_proxy_server_password and http_proxy_server_username) or (
                  not http_proxy_server_password and not http_proxy_server_username)):
          if not http_proxy_server_password:
            password_error_message = "Please provide the httpProxyPassword"
            validationItems.append(
              {"config-name": 'httpProxyPassword', "item": self.getErrorItem(password_error_message)})
          elif not http_proxy_server_username:
            username_error_message = "Please provide the httpProxyUsername"
            validationItems.append(
              {"config-name": 'httpProxyUsername', "item": self.getErrorItem(username_error_message)})
        elif not http_proxy_server_url and (http_proxy_server_password or http_proxy_server_username):
          url_error_message += "Please enter httpProxyServer in following format : protocol_name://httpProxy_host_name:port_number"
          validationItems.append({"config-name": 'httpProxyServer', "item": self.getErrorItem(url_error_message)})

    return self.toConfigurationValidationProblems(validationItems, "streamline-common")

  def validateConfigurationsForSite(self, configurations, recommendedDefaults, services, hosts, siteName, method):
   properties = self.getSiteProperties(configurations, siteName)
   if properties:
        if siteName == 'streamline-common':
                return method(properties, None, configurations, services, hosts)
        else:
                return super(STREAMLINE050ServiceAdvisor, self).validateConfigurationsForSite(configurations, recommendedDefaults, services, hosts, siteName, method)
   else:
        return []

  def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
    """
    Validate configurations for the service. Return a list of errors.
    """
    Logger.info("Class: %s, Method: %s. Validating Service Configuration Items." % (
      self.__class__.__name__, inspect.stack()[0][3]))

    siteName = "streamline-common"
    method = self.validateSTREAMLINEConfigurations
    items = self.validateConfigurationsForSite(configurations, recommendedDefaults, services, hosts, siteName, method)
    return items

  def getCardinalitiesDict(self, hosts):
      Logger.info("Called STREAMLINE getCardinalitiesDict")
      return {'STREAMLINE_SERVER': {"min": 1}}

  def putProperty(self, config, configType, services=None):
    userConfigs = {}
    changedConfigs = []
    # if services parameter, prefer values, set by user
    if services:

      if 'configurations' in services.keys():
        userConfigs = services['configurations']
      if 'changed-configurations' in services.keys():
        changedConfigs = services["changed-configurations"]

    if configType not in config:
      config[configType] = {}
    if "properties" not in config[configType]:
      config[configType]["properties"] = {}
    def appendProperty(key, value):
      # If property exists in changedConfigs, do not override, use user defined property
      if self.__isPropertyInChangedConfigs(configType, key, changedConfigs):
        config[configType]["properties"][key] = userConfigs[configType]['properties'][key]
      else:
        config[configType]["properties"][key] = str(value)
    return appendProperty

  def __isPropertyInChangedConfigs(self, configType, propertyName, changedConfigs):
    for changedConfig in changedConfigs:
      if changedConfig['type']==configType and changedConfig['name']==propertyName:
        return True
    return False

  def putPropertyAttribute(self, config, configType):
    if configType not in config:
      config[configType] = {}
    def appendPropertyAttribute(key, attribute, attributeValue):
      if "property_attributes" not in config[configType]:
        if "property_attributes" not in config[configType]:
          config[configType]["property_attributes"] = {}
      if key not in config[configType]["property_attributes"]:
        config[configType]["property_attributes"][key] = {}
      config[configType]["property_attributes"][key][attribute] = attributeValue if isinstance(attributeValue, list) else str(attributeValue)
    return appendPropertyAttribute

  def getSiteProperties(self, configurations, siteName):
    siteConfig = configurations.get(siteName)
    if siteConfig is None:
      return None
    return siteConfig.get("properties")

  def getServicesSiteProperties(self, services, siteName):
    configurations = services.get("configurations")
    if not configurations:
      return None
    siteConfig = configurations.get(siteName)
    if siteConfig is None:
      return None
    return siteConfig.get("properties")

  def getServiceComponentLayoutValidations(self, services, hosts):
    items = super(STREAMLINE050ServiceAdvisor, self).getServiceComponentLayoutValidations(services, hosts)
    return items

  def appendToYamlString(self, yaml_string, list_classes):
    updated_yaml_string = ""
    try:
      strip_yaml_str = re.sub('[\[\]\']', ' ', yaml_string)
      klass_array = [x.strip() for x in strip_yaml_str.split(',')]
      if yaml_string:
        for klass in list_classes:
          klass = klass.strip()
          klass_array.append(klass)
          klass_set = set(klass_array)
          klass_list = [("'" + e + "'") for e in klass_set]
          updated_yaml_string = "[" + ",".join(klass_list) + "]"
    except Exception:
      klass_list = [("'" + e + "'") for e in list_classes]
      updated_yaml_string = "[" + ",".join(klass_list) + "]"
    return updated_yaml_string