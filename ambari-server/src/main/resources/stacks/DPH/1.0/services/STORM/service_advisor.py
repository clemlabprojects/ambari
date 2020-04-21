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

from resource_management.libraries.functions.get_bare_principal import get_bare_principal
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

class StormServiceAdvisor(service_advisor.ServiceAdvisor):

    def __init__(self, *args, **kwargs):
        self.as_super = super(StormServiceAdvisor, self)
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

        return self.getServiceComponentCardinalityValidations(services, hosts, "STORM")

    def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
        """
        Entry point.
        Must be overriden in child class.
        """
        #Logger.info("Class: %s, Method: %s. Recommending Service Configurations." %
        #            (self.__class__.__name__, inspect.stack()[0][3]))

        recommender = StormRecommender()
        recommender.recommendStormConfigurationsFromHDP206(configurations, clusterData, services, hosts)
        recommender.recommendStormConfigurationsFromHDP21(configurations, clusterData, services, hosts)
        recommender.recommendStormConfigurationsFromHDP22(configurations, clusterData, services, hosts)
        recommender.recommendStormConfigurationsFromHDP23(configurations, clusterData, services, hosts)
        recommender.recommendStormConfigurationsFromHDP26(configurations, clusterData, services, hosts)
        recommender.recommendStormConfigurationsFromHDP30(configurations, clusterData, services, hosts)

    def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
        """
        Entry point.
        Validate configurations for the service. Return a list of errors.
        The code for this function should be the same for each Service Advisor.
        """
        #Logger.info("Class: %s, Method: %s. Validating Configurations." %
        #            (self.__class__.__name__, inspect.stack()[0][3]))

        validator = StormValidator()
        # Calls the methods of the validator using arguments,
        # method(siteProperties, siteRecommendations, configurations, services, hosts)
        return validator.validateListOfConfigUsingMethod(configurations, recommendedDefaults, services, hosts, validator.validators)

    @staticmethod
    def isKerberosEnabled(services, configurations):
        """
        Determine if Kerberos is enabled for Storm.

        If storm-site/storm.thrift.transport exists and is set to kerberos sasl transport plugin, return True;
        otherwise return false.

        The value of this property is first tested in the updated configurations (configurations) then
        tested in the current configuration set (services)

        :type services: dict
        :param services: the dictionary containing the existing configuration values
        :type configurations: dict
        :param configurations: the dictionary containing the updated configuration values
        :rtype: bool
        :return: True or False
        """
        if configurations and "storm-site" in configurations and \
                "storm.thrift.transport" in configurations["storm-site"]["properties"]:
            return configurations["storm-site"]["properties"]["storm.thrift.transport"] == "org.apache.storm.security.auth.kerberos.KerberosSaslTransportPlugin"
        elif services and "storm-site" in services["configurations"] and \
                "storm.thrift.transport" in services["configurations"]["storm-site"]["properties"]:
            return services["configurations"]["storm-site"]["properties"]["storm.thrift.transport"] == "org.apache.storm.security.auth.kerberos.KerberosSaslTransportPlugin"
        else:
            return False



class StormRecommender(service_advisor.ServiceAdvisor):
    """
    Storm Recommender suggests properties when adding the service for the first time or modifying configs via the UI.
    """

    def __init__(self, *args, **kwargs):
        self.as_super = super(StormRecommender, self)
        self.as_super.__init__(*args, **kwargs)

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

    def recommendStormConfigurationsFromHDP206(self, configurations, clusterData, services, hosts):
        putStormSiteProperty = self.putProperty(configurations, "storm-site", services)
        servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
        # Storm AMS integration
        if 'AMBARI_METRICS' in servicesList:
            putStormSiteProperty('metrics.reporter.register', 'org.apache.hadoop.metrics2.sink.storm.StormTimelineMetricsReporter')

    def recommendStormConfigurationsFromHDP21(self, configurations, clusterData, services, hosts):
        storm_mounts = [
            ("storm.local.dir", ["NODEMANAGER", "NIMBUS"], "/hadoop/storm", "single")
        ]

        self.updateMountProperties("storm-site", storm_mounts, configurations, services, hosts)


    def recommendStormConfigurationsFromHDP22(self, configurations, clusterData, services, hosts):
        putStormSiteProperty = self.putProperty(configurations, "storm-site", services)
        putStormSiteAttributes = self.putPropertyAttribute(configurations, "storm-site")
        storm_site = self.getServicesSiteProperties(services, "storm-site")
        security_enabled = StormServiceAdvisor.isKerberosEnabled(services, configurations)
        if "ranger-env" in services["configurations"] and "ranger-storm-plugin-properties" in services["configurations"] and \
                        "ranger-storm-plugin-enabled" in services["configurations"]["ranger-env"]["properties"]:
            putStormRangerPluginProperty = self.putProperty(configurations, "ranger-storm-plugin-properties", services)
            rangerEnvStormPluginProperty = services["configurations"]["ranger-env"]["properties"]["ranger-storm-plugin-enabled"]
            putStormRangerPluginProperty("ranger-storm-plugin-enabled", rangerEnvStormPluginProperty)

        rangerPluginEnabled = ''
        if 'ranger-storm-plugin-properties' in configurations and 'ranger-storm-plugin-enabled' in  configurations['ranger-storm-plugin-properties']['properties']:
            rangerPluginEnabled = configurations['ranger-storm-plugin-properties']['properties']['ranger-storm-plugin-enabled']
        elif 'ranger-storm-plugin-properties' in services['configurations'] and 'ranger-storm-plugin-enabled' in services['configurations']['ranger-storm-plugin-properties']['properties']:
            rangerPluginEnabled = services['configurations']['ranger-storm-plugin-properties']['properties']['ranger-storm-plugin-enabled']

        nonRangerClass = 'org.apache.storm.security.auth.authorizer.SimpleACLAuthorizer'
        servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
        rangerServiceVersion=''
        if 'RANGER' in servicesList:
            rangerServiceVersion = [service['StackServices']['service_version'] for service in services["services"] if service['StackServices']['service_name'] == 'RANGER'][0]

        if rangerServiceVersion and rangerServiceVersion == '0.4.0':
            rangerClass = 'com.xasecure.authorization.storm.authorizer.XaSecureStormAuthorizer'
        else:
            rangerClass = 'org.apache.ranger.authorization.storm.authorizer.RangerStormAuthorizer'
        # Cluster is kerberized
        if security_enabled:
            if rangerPluginEnabled and (rangerPluginEnabled.lower() == 'Yes'.lower()):
                putStormSiteProperty('nimbus.authorizer',rangerClass)
            else:
                putStormSiteProperty('nimbus.authorizer', nonRangerClass)
        else:
            putStormSiteAttributes('nimbus.authorizer', 'delete', 'true')


    def recommendStormConfigurationsFromHDP23(self, configurations, clusterData, services, hosts):
        putStormStartupProperty = self.putProperty(configurations, "storm-site", services)
        putStormEnvProperty = self.putProperty(configurations, "storm-env", services)
        servicesList = [service["StackServices"]["service_name"] for service in services["services"]]

        if "storm-site" in services["configurations"]:
            # atlas
            notifier_plugin_property = "storm.topology.submission.notifier.plugin.class"
            if notifier_plugin_property in services["configurations"]["storm-site"]["properties"] and \
                            services["configurations"]["storm-site"]["properties"][notifier_plugin_property] is not None:

                notifier_plugin_value = services["configurations"]["storm-site"]["properties"][notifier_plugin_property]
            else:
                notifier_plugin_value = " "

            atlas_is_present = "ATLAS" in servicesList
            atlas_hook_class = "org.apache.atlas.storm.hook.StormAtlasHook"
            atlas_hook_is_set = atlas_hook_class in notifier_plugin_value
            enable_atlas_hook = False
            enable_external_atlas_for_storm = False

            if 'storm-atlas-application.properties' in services['configurations'] and 'enable.external.atlas.for.storm' in services['configurations']['storm-atlas-application.properties']['properties']:
                enable_external_atlas_for_storm = services['configurations']['storm-atlas-application.properties']['properties']['enable.external.atlas.for.storm'].lower() == "true"

            if atlas_is_present:
                putStormEnvProperty("storm.atlas.hook", "true")
            elif enable_external_atlas_for_storm:
                putStormEnvProperty("storm.atlas.hook", "true")
            else:
                putStormEnvProperty("storm.atlas.hook", "false")

            if 'storm-env' in configurations and 'storm.atlas.hook' in configurations['storm-env']['properties']:
                enable_atlas_hook = configurations['storm-env']['properties']['storm.atlas.hook'] == "true"
            elif 'storm-env' in services['configurations'] and 'storm.atlas.hook' in services['configurations']['storm-env']['properties']:
                enable_atlas_hook = services['configurations']['storm-env']['properties']['storm.atlas.hook'] == "true"

            if enable_atlas_hook and not atlas_hook_is_set:
                notifier_plugin_value = atlas_hook_class if notifier_plugin_value == " " else ",".join([notifier_plugin_value, atlas_hook_class])

            if not enable_atlas_hook and atlas_hook_is_set:
                application_classes = [item for item in notifier_plugin_value.split(",") if item != atlas_hook_class and item != " "]
                notifier_plugin_value = ",".join(application_classes) if application_classes else " "

            if notifier_plugin_value.strip() != "":
                putStormStartupProperty(notifier_plugin_property, notifier_plugin_value)
            else:
                putStormStartupPropertyAttribute = self.putPropertyAttribute(configurations, "storm-site")
                putStormStartupPropertyAttribute(notifier_plugin_property, 'delete', 'true')

    def recommendStormConfigurationsFromHDP26(self, configurations, clusterData, services, hosts):
        """
         In HDF-2.6.1 we introduced a new way of doing Auto Credentials with services such as
         HDFS, HIVE, HBASE. This method will update the required configs for autocreds if the users installs
         STREAMLINE service.
         """
        storm_site = self.getServicesSiteProperties(services, "storm-site")
        storm_env = self.getServicesSiteProperties(services, "storm-env")
        putStormSiteProperty = self.putProperty(configurations, "storm-site", services)
        putStormSiteAttributes = self.putPropertyAttribute(configurations, "storm-site")
        security_enabled = StormServiceAdvisor.isKerberosEnabled(services, configurations)
        servicesList = [service["StackServices"]["service_name"] for service in services["services"]]

        if storm_env and storm_site and security_enabled and 'STREAMLINE' in servicesList:
            storm_nimbus_impersonation_acl = storm_site["nimbus.impersonation.acl"] if "nimbus.impersonation.acl" in storm_site else None
            if storm_nimbus_impersonation_acl is not None:
                storm_nimbus_impersonation_acl = "{ {{storm_bare_jaas_principal}} : {hosts: ['*'], groups: ['*']},{{streamline_bare_jaas_principal}} : {hosts: ['*'], groups: ['*']}}"
            putStormSiteProperty('nimbus.impersonation.acl', storm_nimbus_impersonation_acl)

            storm_nimbus_autocred_plugin_classes = storm_site["nimbus.autocredential.plugins.classes"] if "nimbus.autocredential.plugins.classes" in storm_site else None
            if storm_nimbus_autocred_plugin_classes is not None:
                new_storm_nimbus_autocred_plugin_classes = ['org.apache.storm.hdfs.security.AutoHDFS',
                                                            'org.apache.storm.hbase.security.AutoHBase',
                                                            'org.apache.storm.hive.security.AutoHive']
                new_conf = self.appendToYamlString(storm_nimbus_autocred_plugin_classes,
                                                                  new_storm_nimbus_autocred_plugin_classes)

                putStormSiteProperty("nimbus.autocredential.plugins.classes", new_conf)
            else:
                putStormSiteProperty("nimbus.autocredential.plugins.classes", "['org.apache.storm.hdfs.security.AutoHDFS', 'org.apache.storm.hbase.security.AutoHBase', 'org.apache.storm.hive.security.AutoHive']")


            storm_nimbus_credential_renewer_classes = storm_site["nimbus.credential.renewers.classes"] if "nimbus.credential.renewers.classes" in storm_site else None
            if storm_nimbus_credential_renewer_classes is not None:
                new_storm_nimbus_credential_renewer_classes_array = ['org.apache.storm.hdfs.security.AutoHDFS',
                                                                     'org.apache.storm.hbase.security.AutoHBase',
                                                                     'org.apache.storm.hive.security.AutoHive']
                new_conf = self.appendToYamlString(storm_nimbus_credential_renewer_classes,
                                                                  new_storm_nimbus_credential_renewer_classes_array)
                putStormSiteProperty("nimbus.autocredential.plugins.classes", new_conf)
            else:
                putStormSiteProperty("nimbus.credential.renewers.classes", "['org.apache.storm.hdfs.security.AutoHDFS', 'org.apache.storm.hbase.security.AutoHBase', 'org.apache.storm.hive.security.AutoHive']")
            putStormSiteProperty("nimbus.credential.renewers.freq.secs", "82800")
        pass

    def recommendStormConfigurationsFromHDP30(self, configurations, clusterData, services, hosts):

        storm_site = self.getServicesSiteProperties(services, "storm-site")
        security_enabled = StormServiceAdvisor.isKerberosEnabled(services, configurations)
        servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
        putStormSiteProperty = self.putProperty(configurations, "storm-site", services)

        # Storm AMS integration
        if 'AMBARI_METRICS' in servicesList:
            putStormSiteProperty('storm.cluster.metrics.consumer.register', '[{"class": "org.apache.hadoop.metrics2.sink.storm.StormTimelineMetricsReporter"}]')
            putStormSiteProperty('topology.metrics.consumer.register',
                                 '[{"class": "org.apache.hadoop.metrics2.sink.storm.StormTimelineMetricsSink", '
                                 '"parallelism.hint": 1, '
                                 '"whitelist": ["kafkaOffset\\\..+/", "__complete-latency", "__process-latency", '
                                 '"__execute-latency", '
                                 '"__receive\\\.population$", "__sendqueue\\\.population$", "__execute-count", "__emit-count", '
                                 '"__ack-count", "__fail-count", "memory/heap\\\.usedBytes$", "memory/nonHeap\\\.usedBytes$", '
                                 '"GC/.+\\\.count$", "GC/.+\\\.timeMs$"]}]')
        else:
            putStormSiteProperty('storm.cluster.metrics.consumer.register', 'null')
            putStormSiteProperty('topology.metrics.consumer.register', 'null')

        if storm_site and not security_enabled:
            putStormSitePropertyAttribute = self.putPropertyAttribute(configurations, "storm-site")
            if 'nimbus.impersonation.acl' in storm_site:
                putStormSitePropertyAttribute('nimbus.impersonation.acl', 'delete', 'true')
            if 'nimbus.impersonation.authorizer' in storm_site:
                putStormSitePropertyAttribute('nimbus.impersonation.authorizer', 'delete', 'true')


class StormValidator(service_advisor.ServiceAdvisor):
    """
    Kafka Validator checks the correctness of properties whenever the service is first added or the user attempts to
    change configs via the UI.
    """

    def __init__(self, *args, **kwargs):
        self.as_super = super(StormValidator, self)
        self.as_super.__init__(*args, **kwargs)

        self.validators = [("storm-site", self.validateStormConfigurationsFromHDP25),
                           ("ranger-storm-plugin-properties", self.validateStormConfigurationsFromHDP22)]

    def validateStormConfigurationsFromHDP206(self, properties, recommendedDefaults, configurations, services, hosts):
        validationItems = []
        servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
        # Storm AMS integration
        if 'AMBARI_METRICS' in servicesList and "metrics.reporter.register" in properties and \
                        "org.apache.hadoop.metrics2.sink.storm.StormTimelineMetricsReporter" not in properties.get("metrics.reporter.register"):

            validationItems.append({"config-name": 'metrics.reporter.register',
                                    "item": self.getWarnItem(
                                        "Should be set to org.apache.hadoop.metrics2.sink.storm.StormTimelineMetricsReporter to report the metrics to Ambari Metrics service.")})

        return self.toConfigurationValidationProblems(validationItems, "storm-site")



    def validateStormConfigurationsFromHDP22(self, properties, recommendedDefaults, configurations, services, hosts):
        validationItems = []
        ranger_plugin_properties = self.getSiteProperties(configurations, "ranger-storm-plugin-properties")
        ranger_plugin_enabled = ranger_plugin_properties['ranger-storm-plugin-enabled'] if ranger_plugin_properties else 'No'
        servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
        security_enabled = StormServiceAdvisor.isKerberosEnabled(services, configurations)
        if 'RANGER' in servicesList and ranger_plugin_enabled.lower() == 'yes':
            # ranger-hdfs-plugin must be enabled in ranger-env
            ranger_env = self.getServicesSiteProperties(services, 'ranger-env')
            if not ranger_env or not 'ranger-storm-plugin-enabled' in ranger_env or \
                            ranger_env['ranger-storm-plugin-enabled'].lower() != 'yes':
                validationItems.append({"config-name": 'ranger-storm-plugin-enabled',
                                        "item": self.getWarnItem(
                                            "ranger-storm-plugin-properties/ranger-storm-plugin-enabled must correspond ranger-env/ranger-storm-plugin-enabled")})
        if ("RANGER" in servicesList) and (ranger_plugin_enabled.lower() == 'Yes'.lower()) and not security_enabled:
            validationItems.append({"config-name": "ranger-storm-plugin-enabled",
                                    "item": self.getWarnItem(
                                        "Ranger Storm plugin should not be enabled in non-kerberos environment.")})

        return self.toConfigurationValidationProblems(validationItems, "ranger-storm-plugin-properties")

    def validateStormConfigurationsFromHDP25(self, properties, recommendedDefaults, configurations, services, hosts):
        self.validateStormConfigurationsFromHDP206(properties, recommendedDefaults, configurations, services, hosts)
        validationItems = []

        servicesList = [service["StackServices"]["service_name"] for service in services["services"]]
        # Storm AMS integration
        if 'AMBARI_METRICS' in servicesList:
            if "storm.cluster.metrics.consumer.register" in properties and \
                            'null' in properties.get("storm.cluster.metrics.consumer.register"):

                validationItems.append({"config-name": 'storm.cluster.metrics.consumer.register',
                                        "item": self.getWarnItem(
                                            "Should be set to recommended value to report metrics to Ambari Metrics service.")})

            if "topology.metrics.consumer.register" in properties and \
                            'null' in properties.get("topology.metrics.consumer.register"):

                validationItems.append({"config-name": 'topology.metrics.consumer.register',
                                        "item": self.getWarnItem(
                                            "Should be set to recommended value to report metrics to Ambari Metrics service.")})

        return self.toConfigurationValidationProblems(validationItems, "storm-site")



