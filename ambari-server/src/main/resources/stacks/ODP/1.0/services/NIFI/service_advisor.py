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
import importlib.util
import traceback

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SERVICE_DIR = os.path.join(SCRIPT_DIR, '../../../../../common-services/NIFI/1.1.0')
PARENT_FILE = os.path.join(SERVICE_DIR, 'service_advisor.py')

try:
    with open(PARENT_FILE, 'rb') as fp:
        spec = importlib.util.spec_from_file_location('service_advisor', PARENT_FILE)
        service_advisor = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(service_advisor)
except Exception as e:
    traceback.print_exc()
    print("Failed to load parent")

class NIFI170ServiceAdvisor(service_advisor.NIFI110ServiceAdvisor):

    def validateNiFiAmbariConfigurations(self, properties, recommendedDefaults, configurations, services, hosts):

        parentValidationProblems = super(NIFI170ServiceAdvisor, self).validateNiFiAmbariConfigurations(properties, recommendedDefaults, configurations, services, hosts)

        validationItems = []

        if 'nifi.allow.explicit.keytab' in properties and properties['nifi.allow.explicit.keytab'] == 'true':
            validationItems.append({"config-name": 'nifi.allow.explicit.keytab', 'item': self.getWarnItem('NiFi is currently set to "true" to allow explicit configuration of keytabs on processors. Please consider changing this value to false to force use of the Keytab controller for enhanced security.')})

        validationProblems = self.toConfigurationValidationProblems(validationItems, "nifi-ambari-config")
        validationProblems.extend(parentValidationProblems)

        return validationProblems

    def validateNiFiSslProperties(self, properties, recommendedDefaults, configurations, services, hosts):

        parentValidationProblems = super(NIFI170ServiceAdvisor, self).validateNiFiSslProperties(properties, recommendedDefaults, configurations, services, hosts)

        validationItems = []
        ssl_enabled = properties['nifi.node.ssl.isenabled'] and str(properties['nifi.node.ssl.isenabled']).lower() != 'false'
        if properties['nifi.toolkit.tls.token'] and ssl_enabled and len(properties['nifi.toolkit.tls.token']) < 16:
            validationItems.append({"config-name": 'nifi.toolkit.tls.token', 'item': self.getErrorItem('The NiFi CA token must be 16 or more characters.')})

        validationProblems = self.toConfigurationValidationProblems(validationItems, "nifi-ambari-ssl-config")
        validationProblems.extend(parentValidationProblems)

        return validationProblems