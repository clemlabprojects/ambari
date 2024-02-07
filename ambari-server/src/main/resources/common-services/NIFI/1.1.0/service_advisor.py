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
from importlib.machinery import SourceFileLoader
import traceback

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SERVICE_DIR = os.path.join(SCRIPT_DIR, '../1.0.0/')
PARENT_FILE = os.path.join(SERVICE_DIR, 'service_advisor.py')

try:
    with open(PARENT_FILE, 'rb') as fp:
        service_advisor = SourceFileLoader('service_advisor', PARENT_FILE).load_module()
except Exception as e:
    traceback.print_exc()
    print("Failed to load parent")

class NIFI110ServiceAdvisor(service_advisor.NIFI100ServiceAdvisor):

    def validateNiFiAmbariConfigurations(self, properties, recommendedDefaults, configurations, services, hosts):

        parentValidationProblems = super(NIFI110ServiceAdvisor, self).validateNiFiAmbariConfigurations(properties, recommendedDefaults, configurations, services, hosts)

        validationItems = []

        if 'nifi.security.encrypt.configuration.password' in properties and len(properties['nifi.security.encrypt.configuration.password']) < 12:
            validationItems.append({"config-name": 'nifi.security.encrypt.configuration.password', 'item': self.getErrorItem('The password for encrypting configuration settings must be 12 or more characters.')})

        validationProblems = self.toConfigurationValidationProblems(validationItems, "nifi-ambari-config")
        validationProblems.extend(parentValidationProblems)

        return validationProblems