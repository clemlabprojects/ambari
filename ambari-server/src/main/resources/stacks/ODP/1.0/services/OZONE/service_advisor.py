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
import inspect

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

class OzoneServiceAdvisor(service_advisor.ServiceAdvisor):

  def __init__(self, *args, **kwargs):
    self.as_super = super(OzoneServiceAdvisor, self)
    self.as_super.__init__(*args, **kwargs)

    self.initialize_logger("OzoneServiceAdvisor")

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
    self.cardinalitiesDict.update(
        {
            'OZONE_MANAGER': {"min": 3},
            'OZONE_STORAGE_CONTAINER_MANAGER': {"min": 3},
            'OZONE_RECON': {"min": 3}
        }
    )
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

    return self.getServiceComponentCardinalityValidations(services, hosts, "OZONE")


  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
    """
    Entry point.
    Must be overridden in child class.
    """
    self.logger.info("Class: %s, Method: %s. Recommending Service Configurations." %
                (self.__class__.__name__, inspect.stack()[0][3]))

    recommender = OzoneRecommender()
    recommender.recommendOzoneConfigurationsFromODP10(configurations, clusterData, services, hosts)

  def getServiceConfigurationRecommendationsForSSO(self, configurations, clusterData, services, hosts):
    """
    Entry point.
    Must be overridden in child class.
    """
    recommender = OzoneRecommender()
    recommender.recommendConfigurationsForSSO(configurations, clusterData, services, hosts)

  def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
    """
    Entry point.
    Validate configurations for the service. Return a list of errors.
    The code for this function should be the same for each Service Advisor.
    """
    self.logger.info("Class: %s, Method: %s. Validating Configurations." %
                (self.__class__.__name__, inspect.stack()[0][3]))

    validator = OzoneValidator()
    # Calls the methods of the validator using arguments,
    # method(siteProperties, siteRecommendations, configurations, services, hosts)
    return validator.validateListOfConfigUsingMethod(configurations, recommendedDefaults, services, hosts, validator.validators)
    

class OzoneRecommender(service_advisor.ServiceAdvisor):
  """
  Ozone Recommender suggests properties when adding the service for the first time or modifying configurations via the UI.
  """

  def __init__(self, *args, **kwargs):
    self.as_super = super(OzoneRecommender, self)
    self.as_super.__init__(*args, **kwargs)


  def recommendOzoneConfigurationsFromODP10(self, configurations, clusterData, services, hosts):
    
    ## added in 1.0.6.0
    
    ozone_scm_hosts = self.getComponentHostNames(services, "OZONE", "OZONE_STORAGE_CONTAINER_MANAGER")
    ozone_om_hosts = self.getComponentHostNames(services, "OZONE", "OZONE_MANAGER")
    ozoneSiteProperties = self.getSiteProperties(services['configurations'], 'ozone-site')
    ozoneEnvProperties = self.getSiteProperties(services['configurations'], 'ozone-env')
    putOzoneEnvProperty = self.putProperty(configurations, "ozone-env", services)
    putOzoneEnvPropertyAttributes = self.putPropertyAttribute(configurations, "ozone-env")
    putOzoneSiteProperty = self.putProperty(configurations, "ozone-site", services)
    putOzoneSitePropertyAttribute = self.putPropertyAttribute(configurations, "ozone-site")
    putOzoneRangerAuditSiteProperty = self.putProperty(configurations, "ranger-ozone-audit", services)
    putOzoneRangerPluginPropertes = self.putProperty(configurations, "ranger-ozone-plugin-properties", services)
    

    servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
    # run ozone inside HDFS Datanodes is HDFS is enabled
    if 'HDFS' in servicesList:
      putOzoneRangerAuditSiteProperty('xasecure.audit.destination.hdfs', True)
    else:
      # disable hdfs related properties
      putOzoneRangerAuditSiteProperty('xasecure.audit.destination.hdfs', False)

  
    dataDirsCount = 1
    # Use users 'hdds.datanode.dir' first
    if "ozone-site" in services["configurations"] and "hdds.datanode.dir" in services["configurations"]["ozone-site"][
      "properties"]:
      dataDirsCount = len(
        str(services["configurations"]["ozone-site"]["properties"]["hdds.datanode.dir"]).split(","))
    elif "hdds.datanode.dir" in configurations["ozone-site"]["properties"]:
      dataDirsCount = len(str(configurations["ozone-site"]["properties"]["hdds.datanode.dir"]).split(","))
    if dataDirsCount <= 2:
      failedVolumesTolerated = 0
    elif dataDirsCount <= 4:
      failedVolumesTolerated = 1
    else:
      failedVolumesTolerated = 2
    putOzoneSiteProperty("dfs.datanode.failed.volumes.tolerated", failedVolumesTolerated)

    managerHosts = self.getHostsWithComponent("OZONE", "OZONE_MANAGER", services, hosts)
    scmHosts = self.getHostsWithComponent("OZONE", "OZONE_STORAGE_CONTAINER_MANAGER", services, hosts)
    reconHosts = self.getHostsWithComponent("OZONE", "OZONE_RECON", services, hosts)

    ## Ozone Manager
    # 25 * # of cores on NameNode
    managerCores = 4
    if managerHosts is not None and len(managerHosts):
      managerCores = int(managerHosts[0]['Hosts']['cpu_count'])
    putOzoneSiteProperty("ozone.om.handler.count.key", 25 * managerCores)
    if 25 * managerCores > 200:
      putOzoneSitePropertyAttribute("ozone.om.handler.count.key", "maximum", 25 * managerCores)

    servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
    if ('ranger-ozone-plugin-properties' in services['configurations']) and (
      'ranger-ozone-plugin-enabled' in services['configurations']['ranger-ozone-plugin-properties']['properties']):
      rangerPluginEnabled = services['configurations']['ranger-ozone-plugin-properties']['properties'][
        'ranger-ozone-plugin-enabled']
      if ("RANGER" in servicesList) and (rangerPluginEnabled.lower() == 'Yes'.lower()):
        putOzoneSiteProperty("ozone.acl.enabled", 'true')

    putOzoneSiteProperty("hdds.scm.safemode.threshold.pct", "0.999" if len(scmHosts) > 1 else "1.000")

    putOzoneEnvProperty('ozone_manager_heapsize', max(int(clusterData['totalAvailableRam'] / 2), 1024))

    manager_heapsize_limit = None
    if (managerHosts is not None and len(managerHosts) > 0):
      if len(managerHosts) > 1:
        om_max_heapsize = min(int(managerHosts[0]["Hosts"]["total_mem"]),
                              int(managerHosts[1]["Hosts"]["total_mem"])) / 1024
        masters_at_host = max(
            self.getHostComponentsByCategories(managerHosts[0]["Hosts"]["host_name"], ["MASTER"], services, hosts),
            self.getHostComponentsByCategories(managerHosts[1]["Hosts"]["host_name"], ["MASTER"], services, hosts),
            key=lambda x: len(x)
        )
      else:
        om_max_heapsize = int(managerHosts[0]["Hosts"]["total_mem"] / 1024)  # total_mem in kb
        masters_at_host = self.getHostComponentsByCategories(managerHosts[0]["Hosts"]["host_name"], ["MASTER"],
                                                             services, hosts)

      putOzoneEnvPropertyAttributes('ozone_manager_heapsize', 'maximum', max(om_max_heapsize, 1024))

      manager_heapsize_limit = om_max_heapsize
      manager_heapsize_limit -= clusterData["reservedRam"]
      if len(masters_at_host) > 1:
        manager_heapsize_limit = int(manager_heapsize_limit / 2)

      putOzoneEnvProperty('ozone_manager_heapsize', max(manager_heapsize_limit, 1024))

    ## Ozone Storage Container Manager
    # 25 * # of cores on NameNode
    scmCores = 4
    if scmHosts is not None and len(scmHosts):
      scmCores = int(scmHosts[0]['Hosts']['cpu_count'])
    putOzoneSiteProperty("ozone.om.handler.count.key", 25 * scmCores)
    if 25 * scmCores > 200:
      putOzoneSitePropertyAttribute("ozone.om.handler.count.key", "maximum", 25 * scmCores)

    servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
    if ('ranger-ozone-plugin-properties' in services['configurations']) and (
      'ranger-ozone-plugin-enabled' in services['configurations']['ranger-ozone-plugin-properties']['properties']):
      rangerPluginEnabled = services['configurations']['ranger-ozone-plugin-properties']['properties'][
        'ranger-ozone-plugin-enabled']
      if ("RANGER" in servicesList) and (rangerPluginEnabled.lower() == 'Yes'.lower()):
        putOzoneSiteProperty("ozone.acl.enabled", 'true')

    putOzoneSiteProperty("hdds.scm.safemode.threshold.pct", "0.999" if len(scmHosts) > 1 else "1.000")

    putOzoneEnvProperty('ozone_scm_heapsize', max(int(clusterData['totalAvailableRam'] / 2), 1024))

    scm_heapsize_limit = None
    if (scmHosts is not None and len(scmHosts) > 0):
      if len(scmHosts) > 1:
        om_max_heapsize = min(int(scmHosts[0]["Hosts"]["total_mem"]),
                              int(scmHosts[1]["Hosts"]["total_mem"])) / 1024
        masters_at_host = max(
          self.getHostComponentsByCategories(scmHosts[0]["Hosts"]["host_name"], ["MASTER"], services, hosts),
          self.getHostComponentsByCategories(scmHosts[1]["Hosts"]["host_name"], ["MASTER"], services, hosts),
          key=lambda x: len(x)  # Replace with the actual key you want to compare
        )
      else:
        om_max_heapsize = int(scmHosts[0]["Hosts"]["total_mem"] / 1024)  # total_mem in kb
        masters_at_host = self.getHostComponentsByCategories(scmHosts[0]["Hosts"]["host_name"], ["MASTER"],
                                                             services, hosts)

      putOzoneEnvPropertyAttributes('ozone_scm_heapsize', 'maximum', max(om_max_heapsize, 1024))

      scm_heapsize_limit = om_max_heapsize
      scm_heapsize_limit -= clusterData["reservedRam"]
      if len(masters_at_host) > 1:
        scm_heapsize_limit = int(scm_heapsize_limit / 2)

      putOzoneEnvProperty('ozone_scm_heapsize', max(scm_heapsize_limit, 1024))

    datanodeHosts = self.getHostsWithComponent("OZONE", "OZONE_DATANODE", services, hosts)

    if datanodeHosts is not None and len(datanodeHosts) > 0:
      min_datanode_ram_kb = 1073741824  # 1 TB
      for datanode in datanodeHosts:
        ram_kb = datanode['Hosts']['total_mem']
        min_datanode_ram_kb = min(min_datanode_ram_kb, ram_kb)

      datanodeFilesM = len(datanodeHosts) * dataDirsCount / 10  # in millions, # of files = # of disks * 100'000
      scm_memory_configs = [
        {'scm_heap': 1024, 'scm_opt': 128},
        {'scm_heap': 3072, 'scm_opt': 512},
        {'scm_heap': 5376, 'scm_opt': 768},
        {'scm_heap': 9984, 'scm_opt': 1280},
        {'scm_heap': 14848, 'scm_opt': 2048},
        {'scm_heap': 19456, 'scm_opt': 2560},
        {'scm_heap': 24320, 'scm_opt': 3072},
        {'scm_heap': 33536, 'scm_opt': 4352},
        {'scm_heap': 47872, 'scm_opt': 6144},
        {'scm_heap': 59648, 'scm_opt': 7680},
        {'scm_heap': 71424, 'scm_opt': 8960},
        {'scm_heap': 94976, 'scm_opt': 8960}
      ]
      index = {
        datanodeFilesM < 1: 0,
        1 <= datanodeFilesM < 5: 1,
        5 <= datanodeFilesM < 10: 2,
        10 <= datanodeFilesM < 20: 3,
        20 <= datanodeFilesM < 30: 4,
        30 <= datanodeFilesM < 40: 5,
        40 <= datanodeFilesM < 50: 6,
        50 <= datanodeFilesM < 70: 7,
        70 <= datanodeFilesM < 100: 8,
        100 <= datanodeFilesM < 125: 9,
        125 <= datanodeFilesM < 150: 10,
        150 <= datanodeFilesM: 11
      }[1]

      scm_memory_config = scm_memory_configs[index]

      # override with new values if applicable
      if scm_heapsize_limit is not None and scm_memory_config['scm_heap'] <= scm_heapsize_limit:
        putOzoneEnvProperty('ozone_scm_heapsize', scm_memory_config['scm_heap'])

      putOzoneEnvPropertyAttributes('ozone_datanode_heapsize', 'maximum', int(min_datanode_ram_kb / 1024))

    scm_heapsize = int(configurations["ozone-env"]["properties"]["ozone_scm_heapsize"])
    putOzoneEnvProperty('ozone_scm_opt_newsize', max(int(scm_heapsize / 8), 128))
    putOzoneEnvProperty('ozone_scm_opt_maxnewsize', max(int(scm_heapsize / 8), 128))

    manager_heapsize = int(configurations["ozone-env"]["properties"]["ozone_manager_heapsize"])
    putOzoneEnvProperty('ozone_manager_opt_newsize', max(int(manager_heapsize / 8), 128))
    putOzoneEnvProperty('ozone_manager_opt_maxnewsize', max(int(manager_heapsize / 8), 128))

    if 'ozone-site' in services['configurations']:
      ## computes ids for SCM
      scm_port = services["configurations"]["ozone-site"]["properties"]["ozone.scm.datanode.port"]
      putOzoneSiteProperty("ozone.scm.primordial.node.id", scmHosts[0]["Hosts"]["host_name"])

      scm_names = ','.join(str(x['Hosts']['host_name']+":"+str(scm_port)) for x in scmHosts)
      defaultSCMServiceName = 'scmservice'
      defaultOMServiceName = 'omservice'
      putOzoneSiteProperty("ozone.scm.names", scm_names)
      
      if  "ozone.scm.service.ids" not in services["configurations"]["ozone-site"]["properties"]:
        if services["configurations"]["ozone-site"]["properties"]["ozone.scm.service.ids"] is None:
          putOzoneSiteProperty("ozone.scm.service.ids", defaultSCMServiceName)
        else:
          defaultSCMServiceName = services["configurations"]["ozone-site"]["properties"]["ozone.scm.service.ids"].split(',')[0]
      else:
        putOzoneSiteProperty("ozone.scm.service.ids", defaultSCMServiceName)
      # configure ozone.scm.nodes.EXAMPLESCMSERVICEID
      boundaries = [0] if len(scmHosts) is 1 else list(range(0, len(scmHosts)))
      putOzoneSiteProperty("ozone.scm.nodes."+str(defaultSCMServiceName), ','.join(str("scm"+str(x)) for x in boundaries))
      scm_http_port = services["configurations"]["ozone-site"]["properties"]["ozone.scm.http-port"]
      scm_https_port = services["configurations"]["ozone-site"]["properties"]["ozone.scm.https-port"]
      for x in boundaries:
        scmhost = scmHosts[x]
        scmhostname = scmhost['Hosts']['host_name']
        putOzoneSiteProperty("ozone.scm.address."+str(defaultSCMServiceName)+".scm"+str(x).format(defaultSCMServiceName), scmhost['Hosts']['host_name'])
        putOzoneSiteProperty("ozone.scm.http-address."+str(defaultSCMServiceName)+".scm"+str(x).format(defaultSCMServiceName), str(scmhostname)+":"+str(scm_http_port))
        putOzoneSiteProperty("ozone.scm.https-address."+str(defaultSCMServiceName)+".scm"+str(x).format(defaultSCMServiceName), str(scmhostname)+":"+str(scm_https_port))

      default_ozone_port = 9862 #client port
      default_ozone_dn_to_recon_port = 9891
      if "ozone.om.address" in services["configurations"]["ozone-site"]["properties"]:
        parts = services["configurations"]["ozone-site"]["properties"]["ozone.om.address"].split(':')
        default_ozone_port = parts[1] if len(parts) > 1 else 9862
      if "ozone.recon.address" in services["configurations"]["ozone-site"]["properties"]:
        parts = services["configurations"]["ozone-site"]["properties"]["ozone.recon.address"].split(':')
        default_ozone_dn_to_recon_port = parts[1] if len(parts) > 1 else 9891
      ## computes ids for OM
      if  "ozone.om.service.ids" not in services["configurations"]["ozone-site"]["properties"]:
        if services["configurations"]["ozone-site"]["properties"]["ozone.om.service.ids"] is None:
          putOzoneSiteProperty("ozone.om.service.ids", defaultOMServiceName)
        else:
          defaultOMServiceName = services["configurations"]["ozone-site"]["properties"]["ozone.om.service.ids"].split(',')[0]
      else:
        putOzoneSiteProperty("ozone.om.service.ids", defaultOMServiceName)
      # configure ozone.om.nodes.EXAMPLESOMSERVICEID
      boundaries = [0] if len(managerHosts) is 1 else list(range(0, len(managerHosts)))
      putOzoneSiteProperty("ozone.recon.address", str(reconHosts[0]['Hosts']['host_name']+":"+str(default_ozone_dn_to_recon_port)))
      putOzoneSiteProperty("ozone.om.nodes."+str(defaultOMServiceName), ','.join(str("om"+str(x)) for x in boundaries))
      om_http_port = services["configurations"]["ozone-site"]["properties"]["ozone.om.http-port"]
      om_https_port = services["configurations"]["ozone-site"]["properties"]["ozone.om.https-port"]
      for x in boundaries:
        omhost = managerHosts[x]
        omhostname = omhost['Hosts']['host_name']
        putOzoneSiteProperty("ozone.om.address."+str(defaultOMServiceName)+".om"+str(x).format(defaultOMServiceName), str(omhost['Hosts']['host_name'])+":"+str(default_ozone_port))
        putOzoneSiteProperty("ozone.om.http-address."+str(defaultOMServiceName)+".om"+str(x).format(defaultOMServiceName), str(omhostname)+":"+str(om_http_port))
        putOzoneSiteProperty("ozone.om.https-address."+str(defaultOMServiceName)+".om"+str(x).format(defaultOMServiceName), str(omhostname)+":"+str(om_https_port))

      dnHosts = len(self.getHostsWithComponent("OZONE", "OZONE_DATANODE", services, hosts))
      # update ozone replication to size of dnHosts
      replicationReco = min(3, dnHosts)
      putOzoneSiteProperty("ozone.replication", replicationReco)

      if 'ozone-env' in services['configurations'] and 'ozone_user' in services['configurations']['ozone-env']['properties']:
        ozone_user = services['configurations']['ozone-env']['properties']['ozone_user']
      else:
        ozone_user = 'ozone'
      ## Ranger Logic
      ranger_ozone_plugin_enabled = ''
      if 'ranger-ozone-plugin-properties' in configurations and 'ranger-ozone-plugin-enabled' in configurations['ranger-ozone-plugin-properties']['properties']:
        ranger_ozone_plugin_enabled = configurations['ranger-ozone-plugin-properties']['properties']['ranger-ozone-plugin-enabled']
      elif 'ranger-ozone-plugin-properties' in services['configurations'] and 'ranger-ozone-plugin-enabled' in services['configurations']['ranger-ozone-plugin-properties']['properties']:
        ranger_ozone_plugin_enabled = services['configurations']['ranger-ozone-plugin-properties']['properties']['ranger-ozone-plugin-enabled']
      if ranger_ozone_plugin_enabled.lower() == 'Yes'.lower():
        self.logger.info("Enabling Ozone ACL.")
        putOzoneSiteProperty('ozone.acl.authorizer.class','org.apache.ranger.authorization.ozone.authorizer.RangerOzoneAuthorizer')
        putOzoneSiteProperty("ozone.acl.enabled", 'true')
        self.logger.info("Setting Ozone Repo user for Ranger.")
        putOzoneRangerPluginPropertes("REPOSITORY_CONFIG_USERNAME",ozone_user)
        # put right ranger smoke user
        if 'cluster-env' in configurations and 'smokeuser' in configurations['cluster-env']['properties']:
          putOzoneRangerPluginPropertes("policy_user", services['configurations']['cluster-env']['properties']['smokeuser'])
      else:
        putOzoneSiteProperty('ozone.acl.authorizer.class','org.apache.hadoop.ozone.security.acl.OzoneAccessAuthorizer')

      # Enable High Availability params
      if len(ozone_om_hosts) > 1 :
        self.logger.info("Enabling Ozone Manager High availability properties")
        putOzoneSiteProperty("ozone.om.internal.service.id", defaultOMServiceName)
        putOzoneSiteProperty("ozone.om.ratis.enable", "true")
        # setting default Ozone Manager Active Node
        putOzoneEnvProperty("hdds_ha_initial_om_active", managerHosts[0]["Hosts"]["host_name"])
      if len(ozone_scm_hosts) > 1 :
        self.logger.info("Enabling Ozone Manager High availability properties")
        putOzoneSiteProperty("ozone.scm.default.service.id", defaultSCMServiceName)
        putOzoneSiteProperty("ozone.scm.ratis.enable", "true")

  def is_kerberos_enabled(self, configurations, services):
    """
    Tests if Ozone has Kerberos enabled by first checking the recommended changes and then the
    existing settings.
    :type configurations dict
    :type services dict
    :rtype bool
    """
    return self._is_kerberos_enabled(configurations) or \
           (services and 'configurations' in services and self._is_kerberos_enabled(services['configurations']))

  def _is_kerberos_enabled(self, config):
    """
    Detects if Ozone has Kerberos enabled given a dictionary of configurations.
    :type config dict
    :rtype bool
    """
    return config and \
           (
             "ozone-site" in config and
             'ozone.security.enabled' in config['ozone-site']["properties"] and
             (config['ozone-site']["properties"]['ozone.security.enabled'] or
              config['ozone-site']["properties"]['hadoop.security.authentication'] == 'kerberos')
           )


class OzoneValidator(service_advisor.ServiceAdvisor):
  """
  Ozone Validator checks the correctness of properties whenever the service is first added or the user attempts to
  change configs via the UI.
  """

  def __init__(self, *args, **kwargs):
    self.as_super = super(OzoneValidator, self)
    self.as_super.__init__(*args, **kwargs)

    self.validators = [("ozone-site", self.validateOzoneConfigurationsFromODP10),
                       ("ozone-env", self.validateOzoneEnvConfigurationsFromODP10),
                       ("ozone-site", self.validateRangerAuthorizerFromODP10)]
                       
    # **********************************************************
    # Example of how to add a function that validates a certain config type.
    # If the same config type has multiple functions, can keep adding tuples to self.validators
    #self.validators.append(("hadoop-env", self.sampleValidator))

  # def sampleValidator(self, properties, recommendedDefaults, configurations, services, hosts):
  #   """
  #   Example of a validator function other other Service Advisors to emulate.
  #   :return: A list of configuration validation problems.
  #   """
  #   validationItems = []

  #   '''
  #   Item is a simple dictionary.
  #   Two functions can be used to construct it depending on the log level: WARN|ERROR
  #   E.g.,
  #   self.getErrorItem(message) or self.getWarnItem(message)

  #   item = {"level": "ERROR|WARN", "message": "value"}
  #   '''
  #   validationItems.append({"config-name": "my_config_property_name",
  #                           "item": self.getErrorItem("My custom message in method %s" % inspect.stack()[0][3])})
  #   return self.toConfigurationValidationProblems(validationItems, "hadoop-env")

  def validateOzoneConfigurationsFromODP10(self, properties, recommendedDefaults, configurations, services, hosts):
    """
    Added in ODP 1.0.6.0 ; validate ozone-site
    :return: A list of configuration validation problems.
    """
    clusterEnv = self.getSiteProperties(configurations, "cluster-env")
    # validationItems = [{"config-name": 'dfs.datanode.du.reserved', "item": self.validatorLessThenDefaultValue(properties, recommendedDefaults, 'dfs.datanode.du.reserved')},
    #                    {"config-name": 'dfs.datanode.data.dir', "item": self.validatorOneDataDirPerPartition(properties, 'dfs.datanode.data.dir', services, hosts, clusterEnv)}]
    validationItems = []
    defaultReplication = services["configurations"]["ozone-site"]["properties"]["ozone.replication"]
    dnHosts = len(self.getHostsWithComponent("OZONE", "OZONE_DATANODE", services, hosts))
    if int(defaultReplication) < 2 :
      validationItems.extend([{"config-name": "ozone.replication", "item": self.getWarnItem("Value is less than the default recommended value of 3 ")}])
    if int(dnHosts) < int(defaultReplication):
      validationItems.extend([{"config-name": "ozone.replication", "item": self.getErrorItem("Value is higher "+str(defaultReplication)+" than the number of Ozone Datanode Hosts " + str(dnHosts) )}])
    return self.toConfigurationValidationProblems(validationItems, "ozone-site")

  def validateOzoneEnvConfigurationsFromODP10(self, properties, recommendedDefaults, configurations, services, hosts):
    """
    Added in ODP 1.0.6.0 ; validate ozone-env
    :return: A list of configuration validation problems.
    """
    validationItems = []
    return self.toConfigurationValidationProblems(validationItems, "ozone-env")

  def validateRangerAuthorizerFromODP10(self, properties, recommendedDefaults, configurations, services, hosts):
    """
    If Ranger service is present and the ranger plugin is enabled, check that the provider class is correctly set.
    :return: A list of configuration validation problems.
    """
    self.logger.info("Class: %s, Method: %s. Checking if Ranger service is present and if the provider class is using the Ranger Authorizer." %
                (self.__class__.__name__, inspect.stack()[0][3]))
    ozone_site = properties
    validationItems = [] #Adding Ranger Plugin logic here
    ranger_plugin_properties = self.getSiteProperties(configurations, "ranger-ozone-plugin-properties")
    ranger_plugin_enabled = ranger_plugin_properties['ranger-ozone-plugin-enabled'] if ranger_plugin_properties else 'No'
    servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
    if ("RANGER" in servicesList) and (ranger_plugin_enabled.lower() == 'yes'):
      ranger_env = self.getServicesSiteProperties(services, 'ranger-env')
      if not ranger_env or not 'ranger-ozone-plugin-enabled' in ranger_env or \
                      ranger_env['ranger-ozone-plugin-enabled'].lower() != 'yes':
        validationItems.append({"config-name": 'ranger-ozone-plugin-enabled',
                                "item": self.getWarnItem(
                                  "ranger-ozone-plugin-properties/ranger-ozone-plugin-enabled must correspond ranger-env/ranger-ozone-plugin-enabled")})
      try:
        if ozone_site['ozone.acl.authorizer.class'].lower() != 'org.apache.ranger.authorization.ozone.authorizer.RangerOzoneAuthorizer'.lower():
          raise ValueError()
      except (KeyError, ValueError) as e:
        message = "ozone.acl.authorizer.class needs to be set to 'org.apache.ranger.authorization.ozone.authorizer.RangerOzoneAuthorizer' if Ranger Ozone Plugin is enabled."
        validationItems.append({"config-name": 'ozone.acl.authorizer.class',
                                "item": self.getWarnItem(message)})
    return self.toConfigurationValidationProblems(validationItems, 'ozone-site')