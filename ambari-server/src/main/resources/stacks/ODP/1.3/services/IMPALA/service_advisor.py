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
import traceback
import os

from resource_management.core.logger import Logger


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STACKS_DIR = os.path.join(SCRIPT_DIR, "../../../../")
PARENT_FILE = os.path.join(STACKS_DIR, "service_advisor.py")
#SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
#STACKS_DIR = os.path.join(SCRIPT_DIR, '../../../../../stacks/')
#PARENT_FILE = os.path.join(STACKS_DIR, 'service_advisor.py')
try:
    if "BASE_SERVICE_ADVISOR" in os.environ:
        PARENT_FILE = os.environ["BASE_SERVICE_ADVISOR"]
    with open(PARENT_FILE, "rb") as fp:
        service_advisor = imp.load_module(
            "service_advisor", fp, PARENT_FILE, (".py", "rb", imp.PY_SOURCE)
        )
except Exception as e:
    traceback.print_exc()
    print("Failed to load parent file:{0}".format(e))

Logger.initialize_logger()


class Impala030ServiceAdvisor(service_advisor.ServiceAdvisor):
    def __init__(self, *args, **kwargs):
        super(Impala030ServiceAdvisor,self).__init__(*args, **kwargs)
        self.cardinalitiesDict = {}
        self.mastersWithMultipleInstances = set()
        self.mastersWithMultipleInstances.add("IMPALA_CATALOG_SERVICE")
        self.mastersWithMultipleInstances.add("IMPALA_STATE_STORE")
        self.notPreferableOnServerComponents = set()
        # Always call these methods
        self.modifyMastersWithMultipleInstances()
        self.modifyCardinalitiesDict()
        self.modifyHeapSizeProperties()
        self.modifyNotValuableComponents()
        self.modifyComponentsNotPreferableOnServer()
        self.modifyComponentLayoutSchemes()
        self.allRequestedProperties = {}


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
        self.cardinalitiesDict.update(
            {
                "IMPALA_CATALOG_SERVICE": {"min":1, "max":2},
                "IMPALA_STATE_STORE": {"min":1, "max":2},
                "IMPALA_DAEMON":{"min":1},
            }
        )
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
        self.componentLayoutSchemes = {}
        pass


    def getServiceComponentLayoutValidations(self, services, hosts):
        """
        Get a list of errors.
        Must be overriden in child class.
        """

        return self.getServiceComponentCardinalityValidations(services, hosts, "IMPALA")


    def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
        putHDFSCoreProperty = self.putProperty(configurations, "core-site", services)
        putHIVECoreProperty = self.putProperty(configurations, "hive-site", services)
        putImpalaEnvProperty = self.putProperty(configurations, "impala-env", services)
        services_list = self.get_services_list(services)


        impala_user = "impala"
        try:
            impala_user = services["configurations"]["impala-env"]["properties"].get("impala_user", "impala")
        except Exception:
            impala_user = "impala"

        putHDFSCoreProperty("hadoop.proxyuser.{0}.hosts".format(impala_user), "*")
        putHDFSCoreProperty("hadoop.proxyuser.{0}.groups".format(impala_user), "*")
        putHDFSCoreProperty("hadoop.proxyuser.{0}.users".format(impala_user), "*")

        # Ensure Impala JVM gets credentials from keytab-based login context for HMS SASL.
        required_option = "-Djavax.security.auth.useSubjectCredsOnly=false"
        default_log4j_option = "-Dlog4j.configuration=/etc/impala/conf/log4j.properties"

        current_opts = ""
        try:
            current_opts = services["configurations"]["impala-env"]["properties"].get(
                "impala_service_java_options", ""
            )
        except Exception:
            current_opts = ""

        if not current_opts:
            current_opts = default_log4j_option

        options = current_opts.split()
        if default_log4j_option not in options:
            options.insert(0, default_log4j_option)
        if required_option not in options:
            options.append(required_option)

        putImpalaEnvProperty("impala_service_java_options", " ".join(options))

        # Required by Impala HMS event processing on secured clusters.
        putHIVECoreProperty("hive.metastore.dml.events", "true")

        # Toggle integrations based on installed services.
        putImpalaEnvProperty("impala_disable_kudu", "false" if "KUDU" in services_list else "true")
        putImpalaEnvProperty("enable_ranger", "true" if "RANGER" in services_list else "false")
