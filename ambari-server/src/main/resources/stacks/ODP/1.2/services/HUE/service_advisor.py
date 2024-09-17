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
import xml.etree.ElementTree as ET


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
  print("Failed to load parent")

class HueServiceAdvisor(service_advisor.ServiceAdvisor):

  def __init__(self, *args, **kwargs):
    self.as_super = super(HueServiceAdvisor, self)
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
    # Nothing to do
    pass

  def getServiceComponentLayoutValidations(self, services, hosts):
    """
    Get a list of errors.
    Must be overriden in child class.
    """

    return self.getServiceComponentCardinalityValidations(services, hosts, "Hue")

  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
    """
    Entry point.
    Must be overriden in child class.
    """
    #Logger.info("Class: %s, Method: %s. Recommending Service Configurations." %
    #            (self.__class__.__name__, inspect.stack()[0][3]))

    recommender = HueRecommender()
    recommender.recommendHueConfigurationsFromODP12(configurations, clusterData, services, hosts)

  def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
    """
    Entry point.
    Validate configurations for the service. Return a list of errors.
    The code for this function should be the same for each Service Advisor.
    """
    #Logger.info("Class: %s, Method: %s. Validating Configurations." %
    #            (self.__class__.__name__, inspect.stack()[0][3]))

    validator = HueValidator()
    # Calls the methods of the validator using arguments,
    # method(siteProperties, siteRecommendations, configurations, services, hosts)
    return validator.validateListOfConfigUsingMethod(configurations, recommendedDefaults, services, hosts, validator.validators)



class HueRecommender(service_advisor.ServiceAdvisor):
  """
  Hue Recommender suggests properties when adding the service for the first time or modifying configs via the UI.
  """

  def __init__(self, *args, **kwargs):
    self.as_super = super(HueRecommender, self)
    self.as_super.__init__(*args, **kwargs)

  def recommendHueConfigurationsFromODP12(self, configurations, clusterData, services, hosts):
    hue_server_hosts = self.getComponentHostNames(services, "HUE", "HUE_SERVER")
    if 'hue-env' in services['configurations']:
      putHueEnvProperty = self.putProperty(configurations, "hue-env", services)
      putHueIniConfProperty = self.putProperty(configurations, "hue-ini-conf", services)
      cluster_env = self.getServicesSiteProperties(services, "cluster-env")
      security_enabled = cluster_env is not None and "security_enabled" in cluster_env and \
                        cluster_env["security_enabled"].lower() == "true"
      putHueEnvProperty("hue_database", 'MYSQL')
      hueDbFlavor = services['configurations']["hue-env"]["properties"]["hue_database"]
      hueDbHost =   services['configurations']["hue-ini-conf"]["properties"]["hue_db_host"]
      hueDbName =   services['configurations']["hue-ini-conf"]["properties"]["hue_db_name"]
      hue_db_url_dict = {
        'MYSQL': {'hue.jpa.jdbc.driver': 'com.mysql.jdbc.Driver',
                  'hue.jpa.jdbc.url': 'jdbc:mysql://' + self.getDBConnectionHostPort(hueDbFlavor, hueDbHost) + '/' + hueDbName},
        'ORACLE': {'hue.jpa.jdbc.driver': 'oracle.jdbc.driver.OracleDriver',
                    'hue.jpa.jdbc.url': 'jdbc:oracle:thin:@' + self.getOracleDBConnectionHostPort(hueDbFlavor, hueDbHost, hueDbName)},
        'POSTGRES': {'hue.jpa.jdbc.driver': 'org.postgresql.Driver',
                      'hue.jpa.jdbc.url': 'jdbc:postgresql://' + self.getDBConnectionHostPort(hueDbFlavor, hueDbHost) + '/' + hueDbName}
      }
      hueDbProperties = hue_db_url_dict.get(hueDbFlavor, hue_db_url_dict['MYSQL'])
      for key in hueDbProperties:
        putHueIniConfProperty(key, hueDbProperties.get(key))
      hue_server_hosts = [str(s) for s in hue_server_hosts]
      putHueIniConfProperty('hue_server_hosts', hue_server_hosts[0])

    if 'hue-env' in services['configurations'] and ('hue_database' in services['configurations']['hue-env']['properties']) \
      and ('hue_db_host' in services['configurations']['hue-ini-conf']['properties']):

      hueDbFlavor = services['configurations']["hue-env"]["properties"]["hue_database"]
      hueDbHost =   services['configurations']["hue-ini-conf"]["properties"]["hue_db_host"]
      hue_db_privelege_url_dict = {
        'MYSQL': {'hue_privelege_user_jdbc_url': 'jdbc:mysql://' + self.getDBConnectionHostPort(hueDbFlavor, hueDbHost)},
        'ORACLE': {'hue_privelege_user_jdbc_url': 'jdbc:oracle:thin:@' + self.getOracleDBConnectionHostPort(hueDbFlavor, hueDbHost, None)},
        'POSTGRES': {'hue_privelege_user_jdbc_url': 'jdbc:postgresql://' + self.getDBConnectionHostPort(hueDbFlavor, hueDbHost) + '/postgres'}
      }
      huePrivelegeDbProperties = hue_db_privelege_url_dict.get(hueDbFlavor, hue_db_privelege_url_dict['MYSQL'])
      for key in huePrivelegeDbProperties:
        putHueEnvProperty(key, huePrivelegeDbProperties.get(key))

    hueEnvProperties = self.getSiteProperties(services['configurations'], 'hue-env')

    servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
    if hueEnvProperties and self.checkSiteProperties(hueEnvProperties, 'hue_user') and 'KERBEROS' in servicesList:
      # HDFS core-site
      putCoreSiteProperty = self.putProperty(configurations, "core-site", services)
      putCoreSitePropertyAttribute = self.putPropertyAttribute(configurations, "core-site")
      hueUser = hueEnvProperties['hue_user']
      hueUserOld = self.getOldValue(services, 'hue-env', 'hue_user')
      self.put_proxyuser_value(hueUser, '*', is_groups=True, services=services, configurations=configurations, put_function=putCoreSiteProperty)
      if hueUserOld is not None and hueUser != hueUserOld:
        putCoreSitePropertyAttribute("hadoop.proxyuser.{0}.groups".format(hueUserOld), 'delete', 'true')
        services["forced-configurations"].append({"type" : "core-site", "name" : "hadoop.proxyuser.{0}.groups".format(hueUserOld)})
        services["forced-configurations"].append({"type" : "core-site", "name" : "hadoop.proxyuser.{0}.groups".format(hueUser)})

      # OZONE core-site
      putCoreSiteProperty = self.putProperty(configurations, "ozone-core-site", services)
      putCoreSitePropertyAttribute = self.putPropertyAttribute(configurations, "ozone-core-site")
      hueUser = hueEnvProperties['hue_user']
      hueUserOld = self.getOldValue(services, 'hue-env', 'hue_user')
      self.put_proxyuser_value(hueUser, '*', is_groups=True, services=services, configurations=configurations, put_function=putCoreSiteProperty)
      if hueUserOld is not None and hueUser != hueUserOld:
        putCoreSitePropertyAttribute("hadoop.proxyuser.{0}.groups".format(hueUserOld), 'delete', 'true')
        services["forced-configurations"].append({"type" : "ozone-core-site", "name" : "hadoop.proxyuser.{0}.groups".format(hueUserOld)})
        services["forced-configurations"].append({"type" : "ozone-core-site", "name" : "hadoop.proxyuser.{0}.groups".format(hueUser)})

      # HDFS 
      putHTTPFSSiteProperty = self.putProperty(configurations, "httpfs-site", services)
      putHTTPFSSitePropertyAttribute = self.putPropertyAttribute(configurations, "httpfs-site")
      hueUser = hueEnvProperties['hue_user']
      hueUserOld = self.getOldValue(services, 'hue-env', 'hue_user')
      self.put_proxyuser_value(hueUser, '*', is_groups=True, services=services, configurations=configurations, put_function=putHTTPFSSiteProperty)
      if hueUserOld is not None and hueUser != hueUserOld:
        putHTTPFSSitePropertyAttribute("httpfs.proxyuser.{0}.groups".format(hueUserOld), 'delete', 'true')
        services["forced-configurations"].append({"type" : "httpfs-site", "name" : "httpfs.proxyuser.{0}.groups".format(hueUserOld)})
        services["forced-configurations"].append({"type" : "httpfs-site", "name" : "httpfs.proxyuser.{0}.groups".format(hueUser)})

      # Ozone 
      putHTTPFSSiteProperty = self.putProperty(configurations, "ozone-httpfs-site", services)
      putHTTPFSSitePropertyAttribute = self.putPropertyAttribute(configurations, "ozone-httpfs-site")
      hueUser = hueEnvProperties['hue_user']
      hueUserOld = self.getOldValue(services, 'hue-env', 'hue_user')
      self.put_proxyuser_value(hueUser, '*', is_groups=True, services=services, configurations=configurations, put_function=putHTTPFSSiteProperty)
      if hueUserOld is not None and hueUser != hueUserOld:
        putHTTPFSSitePropertyAttribute("httpfs.proxyuser.{0}.groups".format(hueUserOld), 'delete', 'true')
        services["forced-configurations"].append({"type" : "ozone-httpfs-site", "name" : "httpfs.proxyuser.{0}.groups".format(hueUserOld)})
        services["forced-configurations"].append({"type" : "ozone-httpfs-site", "name" : "httpfs.proxyuser.{0}.groups".format(hueUser)})


  def getDBConnectionHostPort(self, db_type, db_host):
      connection_string = ""
      if db_type is None or db_type == "":
        return connection_string
      else:
        colon_count = db_host.count(':')
        DB_TYPE_DEFAULT_PORT_MAP = {"MYSQL":"3306", "ORACLE":"1521", "POSTGRES":"5432", "MSSQL":"1433", "SQLA":"2638"}
        if colon_count == 0:
          if db_type in DB_TYPE_DEFAULT_PORT_MAP:
            connection_string = db_host + ":" + DB_TYPE_DEFAULT_PORT_MAP[db_type]
          else:
            connection_string = db_host
        elif colon_count == 1:
          connection_string = db_host
        elif colon_count == 2:
          connection_string = db_host

      return connection_string

  def getOracleDBConnectionHostPort(self, db_type, db_host, hueDbName):
      connection_string = self.getDBConnectionHostPort(db_type, db_host)
      colon_count = db_host.count(':')
      if colon_count == 1 and '/' in db_host:
        connection_string = "//" + connection_string
      elif colon_count == 0 or colon_count == 1:
        connection_string = "//" + connection_string + "/" + hueDbName if hueDbName else "//" + connection_string

      return connection_string
  
class HueValidator(service_advisor.ServiceAdvisor):
  """
  Hue Validator checks the correctness of properties whenever the service is first added or the user attempts to
  change configs via the UI.
  """

  def __init__(self, *args, **kwargs):
    self.as_super = super(HueValidator, self)
    self.as_super.__init__(*args, **kwargs)

    self.validators = []




