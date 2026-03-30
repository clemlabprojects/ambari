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

class Spark3ServiceAdvisor(service_advisor.ServiceAdvisor):

  def __init__(self, *args, **kwargs):
    self.as_super = super(Spark3ServiceAdvisor, self)
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
    self.heap_size_properties = {"SPARK3_JOBHISTORYSERVER":
                                   [{"config-name": "spark3-env",
                                     "property": "spark_daemon_memory",
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

    return self.getServiceComponentCardinalityValidations(services, hosts, "SPARK3")

  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
    """
    Entry point.
    Must be overriden in child class.
    """
    #Logger.info("Class: %s, Method: %s. Recommending Service Configurations." %
    #            (self.__class__.__name__, inspect.stack()[0][3]))

    recommender = Spark3Recommender()
    recommender.recommendSpark3ConfigurationsFromHDP25(configurations, clusterData, services, hosts)
    recommender.recommendSPARK3ConfigurationsFromHDP26(configurations, clusterData, services, hosts)
    recommender.recommendSPARK3ConfigurationsFromODP12(configurations, clusterData, services, hosts)

  def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
    """
    Entry point.
    Validate configurations for the service. Return a list of errors.
    The code for this function should be the same for each Service Advisor.
    """
    #Logger.info("Class: %s, Method: %s. Validating Configurations." %
    #            (self.__class__.__name__, inspect.stack()[0][3]))

    validator = Spark3Validator()
    # Calls the methods of the validator using arguments,
    # method(siteProperties, siteRecommendations, configurations, services, hosts)
    return validator.validateListOfConfigUsingMethod(configurations, recommendedDefaults, services, hosts, validator.validators)

  def isComponentUsingCardinalityForLayout(self, componentName):
    return componentName in ('SPARK3_THRIFTSERVER', 'SPARK3_LIVY2_SERVER')

  @staticmethod
  def isKerberosEnabled(services, configurations):
    """
    Determines if security is enabled by testing the value of spark3-defaults/spark.history.kerberos.enabled enabled.
    If the property exists and is equal to "true", then is it enabled; otherwise is it assumed to be
    disabled.

    :type services: dict
    :param services: the dictionary containing the existing configuration values
    :type configurations: dict
    :param configurations: the dictionary containing the updated configuration values
    :rtype: bool
    :return: True or False
    """
    if configurations and "spark3-defaults" in configurations and \
            "spark.history.kerberos.enabled" in configurations["spark3-defaults"]["properties"]:
      return configurations["spark3-defaults"]["properties"]["spark.history.kerberos.enabled"].lower() == "true"
    elif services and "spark3-defaults" in services["configurations"] and \
            "spark.history.kerberos.enabled" in services["configurations"]["spark3-defaults"]["properties"]:
      return services["configurations"]["spark3-defaults"]["properties"]["spark.history.kerberos.enabled"].lower() == "true"
    else:
      return False


class Spark3Recommender(service_advisor.ServiceAdvisor):
  """
  Spark3 Recommender suggests properties when adding the service for the first time or modifying configs via the UI.
  """

  def __init__(self, *args, **kwargs):
    self.as_super = super(Spark3Recommender, self)
    self.as_super.__init__(*args, **kwargs)

  def recommendSpark3ConfigurationsFromHDP25(self, configurations, clusterData, services, hosts):
    """
    :type configurations dict
    :type clusterData dict
    :type services dict
    :type hosts dict
    """
    putSparkProperty = self.putProperty(configurations, "spark3-defaults", services)
    putSparkThriftSparkConf = self.putProperty(configurations, "spark3-thrift-sparkconf", services)
    putSparkEnvProperty = self.putProperty(configurations, "spark3-env", services)

    preferred_fs_type = self.getCoreFilesystemType(configurations, services)
    spark3_fs_type = self.getConfigProperty(configurations, services, "spark3-env", "spark3_filesystem_type", preferred_fs_type)
    putSparkEnvProperty("spark3_filesystem_type", spark3_fs_type)

    spark_queue = self.recommendYarnQueue(services, "spark3-defaults", "spark.yarn.queue")
    if spark_queue is not None:
      putSparkProperty("spark.yarn.queue", spark_queue)

    spark_thrift_queue = self.recommendYarnQueue(services, "spark3-thrift-sparkconf", "spark.yarn.queue")
    if spark_thrift_queue is not None:
      putSparkThriftSparkConf("spark.yarn.queue", spark_thrift_queue)

    if self.isConfigPropertyChanged(services, "spark3-env", "spark3_filesystem_type"):
      putSparkProperty(
        "spark.history.fs.logDirectory",
        self.buildPathForServiceFilesystem(configurations, services, "spark3-env", "spark3_filesystem_type", "hdfs:///spark3-history/")
      )
      putSparkProperty(
        "spark.eventLog.dir",
        self.buildPathForServiceFilesystem(configurations, services, "spark3-env", "spark3_filesystem_type", "hdfs:///spark3-history/")
      )
      putSparkProperty(
        "spark.sql.warehouse.dir",
        self.buildPathForServiceFilesystem(configurations, services, "spark3-env", "spark3_filesystem_type", "/apps/spark/warehouse")
      )

      putSparkThriftSparkConf("spark.history.fs.logDirectory", "{{spark_history_dir}}")
      putSparkThriftSparkConf("spark.eventLog.dir", "{{spark_history_dir}}")
      putSparkThriftSparkConf(
        "spark.sql.warehouse.dir",
        self.buildPathForServiceFilesystem(configurations, services, "spark3-env", "spark3_filesystem_type", "/apps/spark/warehouse")
      )


  def recommendSPARK3ConfigurationsFromHDP26(self, configurations, clusterData, services, hosts):
    """
    :type configurations dict
    :type clusterData dict
    :type services dict
    :type hosts dict
    """

    if Spark3ServiceAdvisor.isKerberosEnabled(services, configurations):

      spark3_defaults = self.getServicesSiteProperties(services, "spark3-defaults")

      if spark3_defaults:
        putSpark3DafaultsProperty = self.putProperty(configurations, "spark3-defaults", services)
        putSpark3DafaultsProperty('spark.acls.enable', 'true')
        putSpark3DafaultsProperty('spark.admin.acls', '')
        putSpark3DafaultsProperty('spark.history.ui.acls.enable', 'true')
        putSpark3DafaultsProperty('spark.history.ui.admin.acls', '')


    self.__addZeppelinToLivy2SuperUsers(configurations, services)

  def recommendSPARK3ConfigurationsFromODP12(self, configurations, clusterData, services, hosts):
    """
    :type configurations dict
    :type clusterData dict
    :type services dict
    :type hosts dict
    """
    servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
    spark3JobHistoryServerHosts = self.getHostWithComponent("SPARK3", "SPARK3_JOBHISTORYSERVER", services, hosts)
    putSpark3DefaultsProperty = self.putProperty(configurations, "spark3-defaults", services)
    spark_jhs_port = '18082'
    spark_jhs_port_ssl = '18482'
    protocol = 'http://'
    if 'spark3-defaults' in services["configurations"] and (spark3JobHistoryServerHosts is not None):
      if 'spark.ssl.enabled' in services["configurations"]['spark3-defaults']['properties']:
        spark_ssl_enabled = services["configurations"]['spark3-defaults']['properties']['spark.ssl.enabled']
        if spark_ssl_enabled.lower() == 'true' and len(spark3JobHistoryServerHosts) > 0:
          protocol = 'https://'
          if 'spark.ssl.historyServer.port' in services["configurations"]['spark3-defaults']['properties']:
            spark_jhs_port_ssl = services["configurations"]['spark3-defaults']['properties']['spark.ssl.historyServer.port']
          else:
            putSpark3DefaultsProperty('spark.ssl.historyServer.port', spark_jhs_port_ssl)
          
          # set spark.yarn.historyServer.address to use https and ssl port
          putSpark3DefaultsProperty('spark.yarn.historyServer.address', spark3JobHistoryServerHosts['Hosts']['host_name'] + ':' + spark_jhs_port_ssl)
        else:
          if 'spark.history.ui.port' in services["configurations"]['spark3-defaults']['properties']:
            spark_jhs_port = services["configurations"]['spark3-defaults']['properties']['spark.history.ui.port']
          else:
            putSpark3DefaultsProperty('spark.history.ui.port', spark_jhs_port)

          putSpark3DefaultsProperty('spark.yarn.historyServer.address', spark3JobHistoryServerHosts['Hosts']['host_name'] + ':' + spark_jhs_port)

    # Atlas Spark Hook
    is_atlas_present_in_cluster = "ATLAS" in servicesList
    enable_external_atlas_for_spark = False
    if "spark3-atlas-application.properties" in services["configurations"] and \
       "enable.external.atlas.for.spark" in services["configurations"]["spark3-atlas-application.properties"]["properties"]:
      enable_external_atlas_for_spark = services["configurations"]["spark3-atlas-application.properties"]["properties"]["enable.external.atlas.for.spark"].lower() == "true"

    putSpark3EnvProperty = self.putProperty(configurations, "spark3-env", services)
    if is_atlas_present_in_cluster or enable_external_atlas_for_spark:
      putSpark3EnvProperty("spark.atlas.hook", "true")
    else:
      putSpark3EnvProperty("spark.atlas.hook", "false")

    enable_atlas_hook = False
    if "spark3-env" in configurations and "spark.atlas.hook" in configurations["spark3-env"]["properties"]:
      enable_atlas_hook = configurations["spark3-env"]["properties"]["spark.atlas.hook"].lower() == "true"
    elif "spark3-env" in services["configurations"] and "spark.atlas.hook" in services["configurations"]["spark3-env"]["properties"]:
      enable_atlas_hook = services["configurations"]["spark3-env"]["properties"]["spark.atlas.hook"].lower() == "true"

    def _get_site_value(site, key):
      if site in configurations and key in configurations[site]["properties"]:
        return configurations[site]["properties"][key]
      if site in services["configurations"] and key in services["configurations"][site]["properties"]:
        return services["configurations"][site]["properties"][key]
      return None

    def _merge_csv(value, entry):
      if value:
        items = [x.strip() for x in value.split(",") if x.strip()]
      else:
        items = []
      if entry not in items:
        items.append(entry)
      return ",".join(items)

    def _append_option(site, put_property, key, option_value):
      current = _get_site_value(site, key)
      if current:
        tokens = current.split()
        if option_value not in tokens:
          put_property(key, current + " " + option_value)
      else:
        put_property(key, option_value)

    if enable_atlas_hook:
      atlas_listener = "org.apache.atlas.spark.hook.SparkAtlasQueryExecutionListener"
      atlas_conf_opt = "-Datlas.conf=/etc/spark3/conf"

      putSpark3DefaultsProperty = self.putProperty(configurations, "spark3-defaults", services)
      putSpark3ThriftSparkConf = self.putProperty(configurations, "spark3-thrift-sparkconf", services)

      current_listeners = _get_site_value("spark3-defaults", "spark.sql.queryExecutionListeners")
      putSpark3DefaultsProperty("spark.sql.queryExecutionListeners", _merge_csv(current_listeners, atlas_listener))

      current_thrift_listeners = _get_site_value("spark3-thrift-sparkconf", "spark.sql.queryExecutionListeners")
      putSpark3ThriftSparkConf("spark.sql.queryExecutionListeners", _merge_csv(current_thrift_listeners, atlas_listener))

      _append_option("spark3-defaults", putSpark3DefaultsProperty, "spark.driver.extraJavaOptions", atlas_conf_opt)
      _append_option("spark3-defaults", putSpark3DefaultsProperty, "spark.executor.extraJavaOptions", atlas_conf_opt)

    # Add ticket-based JAAS properties for Spark Atlas hook when security is enabled
    security_enabled = self.isSecurityEnabled(services)
    if "spark3-atlas-application.properties" in services["configurations"]:
      putSparkAtlasHookProperty = self.putProperty(configurations, "spark3-atlas-application.properties", services)
      putSparkAtlasHookPropertyAttribute = self.putPropertyAttribute(configurations, "spark3-atlas-application.properties")

      if security_enabled and enable_atlas_hook:
        putSparkAtlasHookProperty("atlas.jaas.ticketBased-KafkaClient.loginModuleControlFlag", "required")
        putSparkAtlasHookProperty("atlas.jaas.ticketBased-KafkaClient.loginModuleName", "com.sun.security.auth.module.Krb5LoginModule")
        putSparkAtlasHookProperty("atlas.jaas.ticketBased-KafkaClient.option.useTicketCache", "true")
      else:
        putSparkAtlasHookPropertyAttribute("atlas.jaas.ticketBased-KafkaClient.loginModuleControlFlag", "delete", "true")
        putSparkAtlasHookPropertyAttribute("atlas.jaas.ticketBased-KafkaClient.loginModuleName", "delete", "true")
        putSparkAtlasHookPropertyAttribute("atlas.jaas.ticketBased-KafkaClient.option.useTicketCache", "delete", "true")

  def __addZeppelinToLivy2SuperUsers(self, configurations, services):
    """
    If Kerberos is enabled AND Zeppelin is installed AND Spark3 Livy Server is installed, then set
    spark3-livy2-conf/livy.superusers to contain the Zeppelin principal name from
    zeppelin-site/zeppelin.server.kerberos.principal

    :param configurations:
    :param services:
    """
    if Spark3ServiceAdvisor.isKerberosEnabled(services, configurations):
      zeppelin_site = self.getServicesSiteProperties(services, "zeppelin-site")

      if zeppelin_site and 'zeppelin.server.kerberos.principal' in zeppelin_site:
        zeppelin_principal = zeppelin_site['zeppelin.server.kerberos.principal']
        zeppelin_user = zeppelin_principal.split('@')[0] if zeppelin_principal else None

        if zeppelin_user:
          livy2_conf = self.getServicesSiteProperties(services, 'spark3-livy2-conf')

          if livy2_conf:
            superusers = livy2_conf['livy.superusers'] if livy2_conf and 'livy.superusers' in livy2_conf else None

            # add the Zeppelin user to the set of users
            if superusers:
              _superusers = superusers.split(',')
              _superusers = [x.strip() for x in _superusers]
              _superusers = list(filter(None, _superusers))  # Removes empty string elements from array
            else:
              _superusers = []

            if zeppelin_user not in _superusers:
              _superusers.append(zeppelin_user)

              putLivy2ConfProperty = self.putProperty(configurations, 'spark3-livy2-conf', services)
              putLivy2ConfProperty('livy.superusers', ','.join(_superusers))


class Spark3Validator(service_advisor.ServiceAdvisor):
  """
  Spark3 Validator checks the correctness of properties whenever the service is first added or the user attempts to
  change configs via the UI.
  """

  def __init__(self, *args, **kwargs):
    self.as_super = super(Spark3Validator, self)
    self.as_super.__init__(*args, **kwargs)

    self.validators = [("spark3-defaults", self.validateSpark3DefaultsFromHDP25),
                       ("spark3-thrift-sparkconf", self.validateSpark3ThriftSparkConfFromHDP25)]


  def validateSpark3DefaultsFromHDP25(self, properties, recommendedDefaults, configurations, services, hosts):
    servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
    spark3FsType = self.getConfigProperty(configurations, services, "spark3-env", "spark3_filesystem_type", self.getCoreFilesystemType(configurations, services))
    validationItems = [
      {
        "config-name": 'spark.yarn.queue',
        "item": self.validatorYarnQueue(properties, recommendedDefaults, 'spark.yarn.queue', services)
      }
    ]
    if str(spark3FsType).upper() == "HDFS" and "HDFS" not in servicesList:
      validationItems.append({
        "config-name": "spark3_filesystem_type",
        "item": self.getWarnItem("HDFS is not installed. Select OZONE or EXTERNAL for spark3-env/spark3_filesystem_type.")
      })
    return self.toConfigurationValidationProblems(validationItems, "spark3-defaults")


  def validateSpark3ThriftSparkConfFromHDP25(self, properties, recommendedDefaults, configurations, services, hosts):
    validationItems = [
      {
        "config-name": 'spark.yarn.queue',
        "item": self.validatorYarnQueue(properties, recommendedDefaults, 'spark.yarn.queue', services)
      }
    ]
    return self.toConfigurationValidationProblems(validationItems, "spark3-thrift-sparkconf")
