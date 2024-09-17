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
from importlib.machinery import SourceFileLoader
import os
import traceback
import re
import socket
import fnmatch

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STACKS_DIR = os.path.join(SCRIPT_DIR, "../../../../")
PARENT_FILE = os.path.join(STACKS_DIR, "service_advisor.py")

try:
  if "BASE_SERVICE_ADVISOR" in os.environ:
    PARENT_FILE = os.environ["BASE_SERVICE_ADVISOR"]
  with open(PARENT_FILE, "rb") as fp:
    service_advisor = SourceFileLoader('service_advisor', PARENT_FILE).load_module()
except Exception as e:
  traceback.print_exc()
  print("Failed to load parent")

class OozieServiceAdvisor(service_advisor.ServiceAdvisor):

  def __init__(self, *args, **kwargs):
    self.as_super = super(OozieServiceAdvisor, self)
    self.as_super.__init__(*args, **kwargs)

    self.initialize_logger("OozieServiceAdvisor")

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
    Must be overridden in child class.
    """
    # Nothing to do
    pass


  def modifyCardinalitiesDict(self):
    """
    Modify the dictionary of cardinalities.
    Must be overridden in child class.
    """
    # Nothing to do
    pass


  def modifyHeapSizeProperties(self):
    """
    Modify the dictionary of heap size properties.
    Must be overridden in child class.
    """
    pass


  def modifyNotValuableComponents(self):
    """
    Modify the set of components whose host assignment is based on other services.
    Must be overridden in child class.
    """
    # Nothing to do
    pass


  def modifyComponentsNotPreferableOnServer(self):
    """
    Modify the set of components that are not preferable on the server.
    Must be overridden in child class.
    """
    # Nothing to do
    pass


  def modifyComponentLayoutSchemes(self):
    """
    Modify layout scheme dictionaries for components.
    The scheme dictionary basically maps the number of hosts to
    host index where component should exist.
    Must be overridden in child class.
    """
    # Nothing to do
    pass


  def getServiceComponentLayoutValidations(self, services, hosts):
    """
    Get a list of errors.
    Must be overridden in child class.
    """

    return self.getServiceComponentCardinalityValidations(services, hosts, "OOZIE")


  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
    """
    Entry point.
    Must be overridden in child class.
    """
    self.logger.info("Class: %s, Method: %s. Recommending Service Configurations." %
                (self.__class__.__name__, inspect.stack()[0][3]))

    recommender = OozieRecommender()
    recommender.recommendOozieConfigurationsFromODP10(configurations, clusterData, services, hosts)
    recommender.recommendConfigurationsForSSO(configurations, clusterData, services, hosts)

  def getServiceConfigurationRecommendationsForSSO(self, configurations, clusterData, services, hosts):
    """
    Entry point.
    Must be overridden in child class.
    """
    recommender = OozieRecommender()
    recommender.recommendConfigurationsForSSO(configurations, clusterData, services, hosts)

  def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
    """
    Entry point.
    Validate configurations for the service. Return a list of errors.
    The code for this function should be the same for each Service Advisor.
    """
    return []

class OozieRecommender(service_advisor.ServiceAdvisor):
  """
  Oozie Recommender suggests properties when adding the service for the first time or modifying configurations via the UI.
  """

  def __init__(self, *args, **kwargs):
    self.as_super = super(OozieRecommender, self)
    self.as_super.__init__(*args, **kwargs)


  def recommendOozieConfigurationsFromODP10(self, configurations, clusterData, services, hosts):
    ## added in 2.0.6
    oozie_mount_properties = [
      ("oozie_data_dir", "OOZIE_SERVER", "/hadoop/oozie/data", "single"),
    ]
    self.updateMountProperties("oozie-env", oozie_mount_properties, configurations, services, hosts)

    ## added in 2.1
    oozieSiteProperties = self.getSiteProperties(services['configurations'], 'oozie-site')
    oozieEnvProperties = self.getSiteProperties(services['configurations'], 'oozie-env')
    putOozieEnvProperty = self.putProperty(configurations, "oozie-env", services)

    ## moving into the top since it's available in later versions too ##
    putOozieSiteProperty = self.putProperty(configurations, "oozie-site", services)
    putOozieSitePropertyAttributes = self.putPropertyAttribute(configurations, "oozie-site")
    ## end moving common variables ##

    if "FALCON_SERVER" in clusterData["components"]:
      falconUser = None
      if "falcon-env" in services["configurations"] and "falcon_user" in services["configurations"]["falcon-env"]["properties"]:
        falconUser = services["configurations"]["falcon-env"]["properties"]["falcon_user"]
        if falconUser is not None:
          putOozieSiteProperty("oozie.service.ProxyUserService.proxyuser.{0}.groups".format(falconUser) , "*")
          putOozieSiteProperty("oozie.service.ProxyUserService.proxyuser.{0}.hosts".format(falconUser) , "*")
        falconUserOldValue = self.getOldValue(services, "falcon-env", "falcon_user")
        if falconUserOldValue is not None:
          if 'forced-configurations' not in services:
            services["forced-configurations"] = []
          putOozieSitePropertyAttributes("oozie.service.ProxyUserService.proxyuser.{0}.groups".format(falconUserOldValue), 'delete', 'true')
          putOozieSitePropertyAttributes("oozie.service.ProxyUserService.proxyuser.{0}.hosts".format(falconUserOldValue), 'delete', 'true')
          services["forced-configurations"].append({"type" : "oozie-site", "name" : "oozie.service.ProxyUserService.proxyuser.{0}.hosts".format(falconUserOldValue)})
          services["forced-configurations"].append({"type" : "oozie-site", "name" : "oozie.service.ProxyUserService.proxyuser.{0}.groups".format(falconUserOldValue)})
          if falconUser is not None:
            services["forced-configurations"].append({"type" : "oozie-site", "name" : "oozie.service.ProxyUserService.proxyuser.{0}.hosts".format(falconUser)})
            services["forced-configurations"].append({"type" : "oozie-site", "name" : "oozie.service.ProxyUserService.proxyuser.{0}.groups".format(falconUser)})

      putMapredProperty = self.putProperty(configurations, "oozie-site")
      putMapredProperty("oozie.services.ext",
                        "org.apache.oozie.service.JMSAccessorService," +
                        "org.apache.oozie.service.PartitionDependencyManagerService," +
                        "org.apache.oozie.service.HCatAccessorService")
    if oozieEnvProperties and oozieSiteProperties and self.checkSiteProperties(oozieSiteProperties, 'oozie.service.JPAService.jdbc.driver') and self.checkSiteProperties(oozieEnvProperties, 'oozie_database'):
      putOozieSiteProperty('oozie.service.JPAService.jdbc.driver', self.getDBDriver(oozieEnvProperties['oozie_database']))
    if oozieSiteProperties and oozieEnvProperties and self.checkSiteProperties(oozieSiteProperties, 'oozie.db.schema.name', 'oozie.service.JPAService.jdbc.url') and self.checkSiteProperties(oozieEnvProperties, 'oozie_database'):
      oozieServerHost = self.getHostWithComponent('OOZIE', 'OOZIE_SERVER', services, hosts)
      oozieDBConnectionURL = oozieSiteProperties['oozie.service.JPAService.jdbc.url']
      protocol = self.getProtocol(oozieEnvProperties['oozie_database'])
      oldSchemaName = self.getOldValue(services, "oozie-site", "oozie.db.schema.name")
      # under these if constructions we are checking if oozie server hostname available,
      # if schema name was changed or if protocol according to current db type differs with protocol in db connection url(db type was changed)
      if oozieServerHost is not None:
        if oldSchemaName or (protocol and oozieDBConnectionURL and not oozieDBConnectionURL.startswith(protocol)):
          dbConnection = self.getDBConnectionString(oozieEnvProperties['oozie_database']).format(oozieServerHost['Hosts']['host_name'], oozieSiteProperties['oozie.db.schema.name'])
          putOozieSiteProperty('oozie.service.JPAService.jdbc.url', dbConnection)

  ## added in ODP 1.2.3.0

    if "HUE_SERVER" in clusterData["components"]:
      hueUser = None
      if "hue-env" in services["configurations"] and "hue_user" in services["configurations"]["hue-env"]["properties"]:
        hueUser = services["configurations"]["hue-env"]["properties"]["hue_user"]
        if hueUser is not None:
          putOozieSiteProperty("oozie.service.ProxyUserService.proxyuser.{0}.groups".format(hueUser) , "*")
          putOozieSiteProperty("oozie.service.ProxyUserService.proxyuser.{0}.hosts".format(hueUser) , "*")
        hueUserOldValue = self.getOldValue(services, "hue-env", "hue_user")
        if hueUserOldValue is not None:
          if 'forced-configurations' not in services:
            services["forced-configurations"] = []
          putOozieSitePropertyAttributes("oozie.service.ProxyUserService.proxyuser.{0}.groups".format(hueUserOldValue), 'delete', 'true')
          putOozieSitePropertyAttributes("oozie.service.ProxyUserService.proxyuser.{0}.hosts".format(hueUserOldValue), 'delete', 'true')
          services["forced-configurations"].append({"type" : "oozie-site", "name" : "oozie.service.ProxyUserService.proxyuser.{0}.hosts".format(hueUserOldValue)})
          services["forced-configurations"].append({"type" : "oozie-site", "name" : "oozie.service.ProxyUserService.proxyuser.{0}.groups".format(hueUserOldValue)})
          if hueUser is not None:
            services["forced-configurations"].append({"type" : "oozie-site", "name" : "oozie.service.ProxyUserService.proxyuser.{0}.hosts".format(hueUser)})
            services["forced-configurations"].append({"type" : "oozie-site", "name" : "oozie.service.ProxyUserService.proxyuser.{0}.groups".format(hueUser)})


    ## added in 2.3
    servicesList = [service["StackServices"]["service_name"] for service in services["services"]]

    if "FALCON" in servicesList:
      putOozieSiteProperty("oozie.service.ELService.ext.functions.coord-job-submit-instances",
                           'now=org.apache.oozie.extensions.OozieELExtensions#ph1_now_echo, \
                            today=org.apache.oozie.extensions.OozieELExtensions#ph1_today_echo, \
                            yesterday=org.apache.oozie.extensions.OozieELExtensions#ph1_yesterday_echo,\
                            currentWeek=org.apache.oozie.extensions.OozieELExtensions#ph1_currentWeek_echo, \
                            lastWeek=org.apache.oozie.extensions.OozieELExtensions#ph1_lastWeek_echo, \
                            currentMonth=org.apache.oozie.extensions.OozieELExtensions#ph1_currentMonth_echo, \
                            lastMonth=org.apache.oozie.extensions.OozieELExtensions#ph1_lastMonth_echo, \
                            currentYear=org.apache.oozie.extensions.OozieELExtensions#ph1_currentYear_echo, \
                            lastYear=org.apache.oozie.extensions.OozieELExtensions#ph1_lastYear_echo, \
                            formatTime=org.apache.oozie.coord.CoordELFunctions#ph1_coord_formatTime_echo, \
                            latest=org.apache.oozie.coord.CoordELFunctions#ph2_coord_latest_echo, \
                            future=org.apache.oozie.coord.CoordELFunctions#ph2_coord_future_echo')

      putOozieSiteProperty("oozie.service.ELService.ext.functions.coord-action-create-inst",
                           'now=org.apache.oozie.extensions.OozieELExtensions#ph2_now_inst, \
                            today=org.apache.oozie.extensions.OozieELExtensions#ph2_today_inst, \
                            yesterday=org.apache.oozie.extensions.OozieELExtensions#ph2_yesterday_inst, \
                            currentWeek=org.apache.oozie.extensions.OozieELExtensions#ph2_currentWeek_inst, \
                            lastWeek=org.apache.oozie.extensions.OozieELExtensions#ph2_lastWeek_inst, \
                            currentMonth=org.apache.oozie.extensions.OozieELExtensions#ph2_currentMonth_inst, \
                            lastMonth=org.apache.oozie.extensions.OozieELExtensions#ph2_lastMonth_inst, \
                            currentYear=org.apache.oozie.extensions.OozieELExtensions#ph2_currentYear_inst, \
                            lastYear=org.apache.oozie.extensions.OozieELExtensions#ph2_lastYear_inst, \
                            latest=org.apache.oozie.coord.CoordELFunctions#ph2_coord_latest_echo, \
                            future=org.apache.oozie.coord.CoordELFunctions#ph2_coord_future_echo, \
                            formatTime=org.apache.oozie.coord.CoordELFunctions#ph2_coord_formatTime, \
                            user=org.apache.oozie.coord.CoordELFunctions#coord_user')

      putOozieSiteProperty("oozie.service.ELService.ext.functions.coord-action-create",
                           'now=org.apache.oozie.extensions.OozieELExtensions#ph2_now, \
                            today=org.apache.oozie.extensions.OozieELExtensions#ph2_today, \
                            yesterday=org.apache.oozie.extensions.OozieELExtensions#ph2_yesterday, \
                            currentWeek=org.apache.oozie.extensions.OozieELExtensions#ph2_currentWeek, \
                            lastWeek=org.apache.oozie.extensions.OozieELExtensions#ph2_lastWeek, \
                            currentMonth=org.apache.oozie.extensions.OozieELExtensions#ph2_currentMonth, \
                            lastMonth=org.apache.oozie.extensions.OozieELExtensions#ph2_lastMonth, \
                            currentYear=org.apache.oozie.extensions.OozieELExtensions#ph2_currentYear, \
                            lastYear=org.apache.oozie.extensions.OozieELExtensions#ph2_lastYear, \
                            latest=org.apache.oozie.coord.CoordELFunctions#ph2_coord_latest_echo, \
                            future=org.apache.oozie.coord.CoordELFunctions#ph2_coord_future_echo, \
                            formatTime=org.apache.oozie.coord.CoordELFunctions#ph2_coord_formatTime, \
                            user=org.apache.oozie.coord.CoordELFunctions#coord_user')

      putOozieSiteProperty("oozie.service.ELService.ext.functions.coord-job-submit-data",
                           'now=org.apache.oozie.extensions.OozieELExtensions#ph1_now_echo, \
                            today=org.apache.oozie.extensions.OozieELExtensions#ph1_today_echo, \
                            yesterday=org.apache.oozie.extensions.OozieELExtensions#ph1_yesterday_echo, \
                            currentWeek=org.apache.oozie.extensions.OozieELExtensions#ph1_currentWeek_echo, \
                            lastWeek=org.apache.oozie.extensions.OozieELExtensions#ph1_lastWeek_echo, \
                            currentMonth=org.apache.oozie.extensions.OozieELExtensions#ph1_currentMonth_echo, \
                            lastMonth=org.apache.oozie.extensions.OozieELExtensions#ph1_lastMonth_echo, \
                            currentYear=org.apache.oozie.extensions.OozieELExtensions#ph1_currentYear_echo, \
                            lastYear=org.apache.oozie.extensions.OozieELExtensions#ph1_lastYear_echo, \
                            dataIn=org.apache.oozie.extensions.OozieELExtensions#ph1_dataIn_echo, \
                            instanceTime=org.apache.oozie.coord.CoordELFunctions#ph1_coord_nominalTime_echo_wrap, \
                            formatTime=org.apache.oozie.coord.CoordELFunctions#ph1_coord_formatTime_echo, \
                            dateOffset=org.apache.oozie.coord.CoordELFunctions#ph1_coord_dateOffset_echo, \
                            user=org.apache.oozie.coord.CoordELFunctions#coord_user')

      putOozieSiteProperty("oozie.service.ELService.ext.functions.coord-action-start",
                           'now=org.apache.oozie.extensions.OozieELExtensions#ph2_now, \
                            today=org.apache.oozie.extensions.OozieELExtensions#ph2_today, \
                            yesterday=org.apache.oozie.extensions.OozieELExtensions#ph2_yesterday, \
                            currentWeek=org.apache.oozie.extensions.OozieELExtensions#ph2_currentWeek, \
                            lastWeek=org.apache.oozie.extensions.OozieELExtensions#ph2_lastWeek, \
                            currentMonth=org.apache.oozie.extensions.OozieELExtensions#ph2_currentMonth, \
                            lastMonth=org.apache.oozie.extensions.OozieELExtensions#ph2_lastMonth, \
                            currentYear=org.apache.oozie.extensions.OozieELExtensions#ph2_currentYear, \
                            lastYear=org.apache.oozie.extensions.OozieELExtensions#ph2_lastYear, \
                            latest=org.apache.oozie.coord.CoordELFunctions#ph3_coord_latest, \
                            future=org.apache.oozie.coord.CoordELFunctions#ph3_coord_future, \
                            dataIn=org.apache.oozie.extensions.OozieELExtensions#ph3_dataIn, \
                            instanceTime=org.apache.oozie.coord.CoordELFunctions#ph3_coord_nominalTime, \
                            dateOffset=org.apache.oozie.coord.CoordELFunctions#ph3_coord_dateOffset, \
                            formatTime=org.apache.oozie.coord.CoordELFunctions#ph3_coord_formatTime, \
                            user=org.apache.oozie.coord.CoordELFunctions#coord_user')

      putOozieSiteProperty("oozie.service.ELService.ext.functions.coord-sla-submit",
                           'instanceTime=org.apache.oozie.coord.CoordELFunctions#ph1_coord_nominalTime_echo_fixed, \
                            user=org.apache.oozie.coord.CoordELFunctions#coord_user')


      putOozieSiteProperty("oozie.service.ELService.ext.functions.coord-sla-create",
                           'instanceTime=org.apache.oozie.coord.CoordELFunctions#ph2_coord_nominalTime, \
                            user=org.apache.oozie.coord.CoordELFunctions#coord_user')

      putOozieSiteProperty("oozie.service.HadoopAccessorService.supported.filesystems",
                           '*')
    else:
      putOozieSitePropertyAttributes('oozie.service.ELService.ext.functions.coord-job-submit-instances', 'delete', 'true')
      putOozieSitePropertyAttributes('oozie.service.ELService.ext.functions.coord-action-create-inst', 'delete', 'true')
      putOozieSitePropertyAttributes('oozie.service.ELService.ext.functions.coord-action-create', 'delete', 'true')
      putOozieSitePropertyAttributes('oozie.service.ELService.ext.functions.coord-job-submit-data', 'delete', 'true')
      putOozieSitePropertyAttributes('oozie.service.ELService.ext.functions.coord-action-start', 'delete', 'true')
      putOozieSitePropertyAttributes('oozie.service.ELService.ext.functions.coord-sla-submit', 'delete', 'true')
      putOozieSitePropertyAttributes('oozie.service.ELService.ext.functions.coord-sla-create', 'delete', 'true')
      putOozieSitePropertyAttributes('oozie.service.HadoopAccessorService.supported.filesystems', 'delete', 'true')

    ## added in 2.5
    if "FALCON" in servicesList:
      putOozieSiteProperty('oozie.service.ELService.ext.functions.workflow',
                           'now=org.apache.oozie.extensions.OozieELExtensions#ph1_now_echo, \
                            today=org.apache.oozie.extensions.OozieELExtensions#ph1_today_echo, \
                            yesterday=org.apache.oozie.extensions.OozieELExtensions#ph1_yesterday_echo, \
                            currentMonth=org.apache.oozie.extensions.OozieELExtensions#ph1_currentMonth_echo, \
                            lastMonth=org.apache.oozie.extensions.OozieELExtensions#ph1_lastMonth_echo, \
                            currentYear=org.apache.oozie.extensions.OozieELExtensions#ph1_currentYear_echo, \
                            lastYear=org.apache.oozie.extensions.OozieELExtensions#ph1_lastYear_echo, \
                            formatTime=org.apache.oozie.coord.CoordELFunctions#ph1_coord_formatTime_echo, \
                            latest=org.apache.oozie.coord.CoordELFunctions#ph2_coord_latest_echo, \
                            future=org.apache.oozie.coord.CoordELFunctions#ph2_coord_future_echo')
    else:
      putOozieSitePropertyAttributes('oozie.service.ELService.ext.functions.workflow', 'delete', 'true')

    if not "oozie-env" in services["configurations"] :
      self.logger.info("No oozie configurations available")
      return

    if not "FALCON_SERVER" in clusterData["components"] :
      self.logger.info("Falcon is not part of the installation")
      return

    falconUser = 'falcon'

    if "falcon-env" in services["configurations"] :
      if "falcon_user" in services["configurations"]["falcon-env"]["properties"] :
        falconUser = services["configurations"]["falcon-env"]["properties"]["falcon_user"]
        self.logger.info("Falcon user from configuration: %s " % falconUser)

    self.logger.info("Falcon user : %s" % falconUser)

    oozieUser = 'oozie'

    if "oozie_user" \
      in services["configurations"]["oozie-env"]["properties"] :
      oozieUser = services["configurations"]["oozie-env"]["properties"]["oozie_user"]
      self.logger.info("Oozie user from configuration %s" % oozieUser)

    self.logger.info("Oozie user %s" % oozieUser)

    if "oozie_admin_users" \
            in services["configurations"]["oozie-env"]["properties"] :
      currentAdminUsers =  services["configurations"]["oozie-env"]["properties"]["oozie_admin_users"]
      self.logger.info("Oozie admin users from configuration %s" % currentAdminUsers)
    else :
      currentAdminUsers = "{0}, oozie-admin".format(oozieUser)
      self.logger.info("Setting default oozie admin users to %s" % currentAdminUsers)


    if falconUser in currentAdminUsers :
      self.logger.info("Falcon user %s already member of  oozie admin users " % falconUser)
      return

    newAdminUsers = "{0},{1}".format(currentAdminUsers, falconUser)

    self.logger.info("new oozie admin users : %s" % newAdminUsers)

    services["forced-configurations"].append({"type" : "oozie-env", "name" : "oozie_admin_users"})
    putOozieEnvProperty("oozie_admin_users", newAdminUsers)

  def recommendConfigurationsForSSO(self, configurations, clusterData, services, hosts):
    ambari_configuration = self.get_ambari_configuration(services)
    ambari_sso_details = ambari_configuration.get_ambari_sso_details() if ambari_configuration else None

    if ambari_sso_details and ambari_sso_details.is_managing_services():
      putOozieSiteProperty = self.putProperty(configurations, "oozie-site", services)

      # If SSO should be enabled for this service
      if ambari_sso_details.should_enable_sso('OOZIE'):
        if(self.is_kerberos_enabled(configurations, services)):
          putOozieSiteProperty('oozie.authentication.type', "org.apache.hadoop.security.authentication.server.JWTRedirectAuthenticationHandler")
          putOozieSiteProperty('oozie.authentication.authentication.provider.url', ambari_sso_details.get_sso_provider_url())
          putOozieSiteProperty('oozie.authentication.public.key.pem', ambari_sso_details.get_sso_provider_certificate(False, True))
          putOozieSiteProperty('oozie.authentication.expected.jwt.audiences', ambari_sso_details.get_jwt_audiences())
          putOozieSiteProperty('oozie.authentication.jwt.cookie', ambari_sso_details.get_jwt_cookie_name())
        else:
          # Since Kerberos is not enabled, we can not enable SSO
          self.logger.warn("Enabling SSO integration for Oozie requires Kerberos, Since Kerberos is not enabled, SSO integration is not being recommended.")
          pass

      # If SSO should be disabled for this service
      elif ambari_sso_details.should_disable_sso('OOZIE'):
        if(self.is_kerberos_enabled(configurations, services)):
          putOozieSiteProperty('oozie.authentication.type', "kerberos")
        else:
          pass

  def is_kerberos_enabled(self, configurations, services):
    """
    Tests if Oozie has Kerberos enabled by first checking the recommended changes and then the
    existing settings.
    :type configurations dict
    :type services dict
    :rtype bool
    """
    return self._is_kerberos_enabled(configurations) or \
           (services and 'configurations' in services and self._is_kerberos_enabled(services['configurations']))

  def _is_kerberos_enabled(self, config):
    """
    Detects if Oozie has Kerberos enabled given a dictionary of configurations.
    :type config dict
    :rtype bool
    """
    return config and \
           (
             "oozie-site" in config and
             'oozie.authentication.type' in config['oozie-site']["properties"] and
             (config['oozie-site']["properties"]['oozie.authentication.type'] == 'kerberos' or
              config['oozie-site']["properties"]['oozie.authentication.type'] == 'org.apache.hadoop.security.authentication.server.JWTRedirectAuthenticationHandler')
           )
