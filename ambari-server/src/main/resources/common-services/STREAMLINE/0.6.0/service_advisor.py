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
import os
import imp
import traceback
from ambari_server.serverConfiguration import get_ambari_properties, get_ambari_version

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SERVICE_DIR = os.path.join(SCRIPT_DIR, '../0.5.0/')
PARENT_FILE = os.path.join(SERVICE_DIR, 'service_advisor.py')

try:
    with open(PARENT_FILE, 'rb') as fp:
        service_advisor = imp.load_module('service_advisor', fp, PARENT_FILE, ('.py', 'rb', imp.PY_SOURCE))
except Exception as e:
    traceback.print_exc()
    print "Failed to load parent"

DB_TYPE_DEFAULT_PORT_MAP = {"mysql":"3306", "oracle":"1521", "postgresql":"5432"}

class STREAMLINE060ServiceAdvisor(service_advisor.STREAMLINE050ServiceAdvisor):

  def getDBConnectionHostPort(self, db_type, db_host):
    connection_string = ""
    if db_type is None or db_type == "":
      return connection_string
    else:
      colon_count = db_host.count(':')
      if colon_count == 0:
        if DB_TYPE_DEFAULT_PORT_MAP.has_key(db_type):
          connection_string = db_host + ":" + DB_TYPE_DEFAULT_PORT_MAP[db_type]
        else:
          connection_string = db_host
      elif colon_count == 1:
        connection_string = db_host
      elif colon_count == 2:
        connection_string = db_host

    return connection_string

  def getOracleDBConnectionHostPort(self, db_type, db_host, db_name):
    connection_string = self.getDBConnectionHostPort(db_type, db_host)
    colon_count = db_host.count(':')
    if colon_count == 1 and '/' in db_host:
      connection_string = "//" + connection_string
    elif colon_count == 0 or colon_count == 1:
      connection_string = "//" + connection_string + "/" + db_name if db_name else "//" + connection_string

    return connection_string

  def validateSTREAMLINEConfigurations(self, properties, recommendedDefaults, configurations, services, hosts):

    parentValidationProblems = super(STREAMLINE060ServiceAdvisor, self).validateSTREAMLINEConfigurations(properties, recommendedDefaults, configurations, services, hosts)
    validationItems = []
    streamline_database_name = services['configurations']['streamline-common']['properties']['database_name']
    streamline_storage_type = str(services['configurations']['streamline-common']['properties']['streamline.storage.type']).lower()
    streamline_storage_connector_connectURI = services['configurations']['streamline-common']['properties']['streamline.storage.connector.connectURI']
    url_error_message = ""

    import re
    if streamline_storage_connector_connectURI:
      if 'oracle' not in streamline_storage_type:
        pattern = '(.*?):(.*?)://(.*?):(.*?)/(.*)'
        dbc_connector_uri = re.match(pattern, streamline_storage_connector_connectURI)
        if dbc_connector_uri is not None:
          dbc_connector_type, db_storage_type, streamline_db_hostname, streamline_db_portnumber, streamline_db_name = re.match(
            pattern, streamline_storage_connector_connectURI).groups()
          if (not dbc_connector_type or not dbc_connector_type or not streamline_db_hostname or not streamline_db_portnumber or not streamline_db_name):
            url_error_message += "Please enter Streamline storage connector url in following format jdbc:" + streamline_storage_type + "://streamline_db_hostname:port_number/" + streamline_database_name
            validationItems.append({"config-name": 'streamline.storage.connector.connectURI', "item": self.getErrorItem(url_error_message)})
        else:
          url_error_message += "Please enter Streamline storage connector url in following format jdbc:" + streamline_storage_type + "://streamline_db_hostname:port_number/" + streamline_database_name
          validationItems.append({"config-name": 'streamline.storage.connector.connectURI', "item": self.getErrorItem(url_error_message)})
      else:
        pattern = '(.*?):(.*?):(.*?):@(.*?):(.*?)/(.*)'
        dbc_connector_uri = re.match(pattern, streamline_storage_connector_connectURI)
        if dbc_connector_uri is not None:
          dbc_connector_type, db_storage_type, dbc_connector_kind, streamline_db_hostname, streamline_db_portnumber, streamline_db_name = re.match(
            pattern, streamline_storage_connector_connectURI).groups()
          if (not dbc_connector_type or not db_storage_type or not dbc_connector_kind or not streamline_db_hostname or not streamline_db_portnumber or not streamline_db_name):
            url_error_message += "Please enter Streamline storage connector url in following format jdbc:" + streamline_storage_type + ":thin:@streamline_db_hostname:port_number/" + streamline_database_name
            validationItems.append({"config-name": 'streamline.storage.connector.connectURI', "item": self.getErrorItem(url_error_message)})
        else:
          url_error_message += "Please enter Streamline storage connector url in following format jdbc:" + streamline_storage_type + ":thin:@streamline_db_hostname:port_number/" + streamline_database_name
          validationItems.append({"config-name": 'streamline.storage.connector.connectURI', "item": self.getErrorItem(url_error_message)})

    validationProblems = self.toConfigurationValidationProblems(validationItems, "streamline-common")

    # SSO based validation
    validationItemsSSO = []
    sso_message = ""
    try:
      streamline_keytab_path = services['configurations']['streamline-env']['properties']['streamline_keytab']
      security_enabled = True
    except KeyError:
      security_enabled = False
      self.logger.info("Kerberos is disabled.")

    streamline_sso_enabled = services['configurations']['streamline-sso-config']['properties']['streamline.sso.enabled']
    if security_enabled:
      if streamline_sso_enabled == "true":
        sso_message += "Enabling knox based SSO for Streamline Analystics Manager would disable Kerberos based SPENGO authentication for streamline client."
        validationItemsSSO.append({"config-name": 'streamline.sso.enabled', "item": self.getWarnItem(sso_message)})

    validationProblems.extend(self.toConfigurationValidationProblems(validationItemsSSO, "streamline-sso-config"))

    validationProblems.extend(parentValidationProblems)
    return validationProblems

  def autopopulateSTREAMLINEJdbcUrl(self, configurations, services):

    putStreamlineCommonProperty = self.putProperty(configurations, "streamline-common", services)
    putStreamlineEnvProperty = self.putProperty(configurations, "streamline-env", services)

    if 'streamline-common' in services['configurations']:
      streamline_storage_database = services['configurations']['streamline-common']['properties']['database_name']
      streamline_storage_type = str(services['configurations']['streamline-common']['properties']['streamline.storage.type']).lower()
      streamline_db_hostname = services['configurations']['streamline-common']['properties']['streamline.storage.db.hostname']

      streamline_db_url_dict = {
        'mysql': {'streamline.storage.connector.connectURI': 'jdbc:mysql://' + self.getDBConnectionHostPort(streamline_storage_type, streamline_db_hostname) + '/' + streamline_storage_database},
        'oracle': {'streamline.storage.connector.connectURI': 'jdbc:oracle:thin:@' + self.getDBConnectionHostPort(streamline_storage_type, streamline_db_hostname) + '/' + streamline_storage_database},
        'postgresql': {'streamline.storage.connector.connectURI': 'jdbc:postgresql://' + self.getDBConnectionHostPort(streamline_storage_type, streamline_db_hostname) + '/' + streamline_storage_database},
        }

      streamlineDbProperties = streamline_db_url_dict.get(streamline_storage_type, streamline_db_url_dict['mysql'])
      for key in streamlineDbProperties:
        putStreamlineCommonProperty(key, streamlineDbProperties.get(key))

      db_root_jdbc_url_dict = {
        'mysql': {'db_root_jdbc_url': 'jdbc:mysql://' + self.getDBConnectionHostPort(streamline_storage_type, streamline_db_hostname)},
        'postgresql': {'db_root_jdbc_url': 'jdbc:postgresql://' + self.getDBConnectionHostPort(streamline_storage_type, streamline_db_hostname)},
        }

      streamlinePrivelegeDbProperties = db_root_jdbc_url_dict.get(streamline_storage_type, db_root_jdbc_url_dict['mysql'])

      if 'oracle' in streamline_storage_type:
        putStreamlineEnvProperty("create_db_user", "false")

      for key in streamlinePrivelegeDbProperties:
        putStreamlineCommonProperty(key, streamlinePrivelegeDbProperties.get(key))

  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):

    Logger.info("Class: %s, Method: %s. Get Service Configuration Recommendations." % (self.__class__.__name__, inspect.stack()[0][3]))
    servicesList = [service["StackServices"]["service_name"] for service in services["services"]]

    if 'AMBARI_METRICS' in servicesList:
      putAmsSiteProperty = self.putProperty(configurations, "ams-site")
      putAmsSiteProperty('timeline.metrics.downsampler.event.metric.patterns', 'topology\.%')

    properties = get_ambari_properties()
    ambari_version = get_ambari_version(properties)
    if not (ambari_version) or not (ambari_version.startswith('2.5')):
      putStreamlineLogSearchConfAttribute = self.putPropertyAttribute(configurations, "streamline-logsearch-conf")
      putStreamlineLogSearchConfAttribute('service_name', 'visible', 'false')
      putStreamlineLogSearchConfAttribute('component_mappings', 'visible', 'false')
      putStreamlineLogSearchConfAttribute('content', 'visible', 'false')

    self.autopopulateSTREAMLINEJdbcUrl(configurations, services)

    # Setting up KNOX SSO for Streams Messaging Manager.
    self.getServiceConfigurationRecommendationsForSSO(configurations, clusterData, services, hosts)
    pass

  def getServiceConfigurationRecommendationsForSSO(self, configurations, clusterData, services, hosts):
    """
    Any SSO-related configuration recommendations for the service should be defined in this function.
    """
    ambari_configuration = self.get_ambari_configuration(services)
    ambari_sso_details = ambari_configuration.get_ambari_sso_details() if ambari_configuration else None

    if ambari_sso_details and ambari_sso_details.is_managing_services():
      putStreamlineSSOProperty = self.putProperty(configurations, "streamline-sso-config")

      # If SSO should be enabled for this service
      if ambari_sso_details.should_enable_sso('STREAMLINE'):
        putStreamlineSSOProperty("streamline.sso.enabled", "true")
        putStreamlineSSOProperty("streamline.authentication.provider.url", ambari_sso_details.get_sso_provider_url())
        putStreamlineSSOProperty("streamline.public.key.pem", ambari_sso_details.get_sso_provider_certificate(False, True))
      else:
        putStreamlineSSOProperty("streamline.sso.enabled", "false")