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

from resource_management.libraries.functions.version import compare_versions

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STACKS_DIR = os.path.join(SCRIPT_DIR, '../../../../')
PARENT_FILE = os.path.join(STACKS_DIR, 'service_advisor.py')

try:
    with open(PARENT_FILE, 'rb') as fp:
        service_advisor = SourceFileLoader('service_advisor', PARENT_FILE).load_module()
except Exception as e:
    traceback.print_exc()
    print("Failed to load parent")

# NiFi 2.x (ODP 1.3.2.0 and later) no longer ships the TLS Toolkit the NiFi Certificate Authority runs on
NIFI2_REPOSITORY_VERSION = '1.3.2.0'
# NiFi 2.x removed the legacy PBE algorithms; NiFi 1.x and 2.x require 12+ characters for these two
NIFI2_SENSITIVE_PROPS_ALGORITHMS = ('NIFI_PBKDF2_AES_GCM_256', 'NIFI_ARGON2_AES_GCM_256')
SENSITIVE_PROPS_KEY_MIN_LENGTH = 12
NIFI_CA_UNSUPPORTED_MESSAGE = 'The NiFi Certificate Authority is not available from ODP 1.3.2.0: NiFi 2.x no longer ships the TLS Toolkit. ' \
                              'Remove it and configure NiFi TLS with your own certificates in nifi-ambari-ssl-config.'

class NIFI170ServiceAdvisor(service_advisor.ServiceAdvisor):

    def isNiFi2(self, services):
        """
        The stack version (1.3) does not tell NiFi 1.x from NiFi 2.x, the repository version does. It is only known
        when Ambari passes it to the advisor; without it the layout is left as it always was.
        """
        repository_version = services.get("Versions", {}).get("repository_version")
        if not repository_version:
            return False
        return compare_versions(repository_version.split('-')[0], NIFI2_REPOSITORY_VERSION) >= 0

    def colocateServiceWithServicesInfo(self, hostsComponentsMap, serviceComponents, services):
        """
        NIFI_CA is optional (cardinality 0-1), but the default layout still puts it on a host.
        Leave it out of new layouts when the target repository ships NiFi 2.x.
        """
        if not self.isNiFi2(services):
            return
        for hostName in hostsComponentsMap:
            hostsComponentsMap[hostName] = [c for c in hostsComponentsMap[hostName] if c.get("name") != "NIFI_CA"]

    def getServiceComponentLayoutValidations(self, services, hosts):
        if not self.isNiFi2(services):
            return []
        if not self.getComponentHostNames(services, "NIFI", "NIFI_CA"):
            return []
        return [{"type": 'host-component', "level": 'ERROR', "message": NIFI_CA_UNSUPPORTED_MESSAGE, "component-name": 'NIFI_CA'}]

    def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
        properties = self.getSiteProperties(configurations, "nifi-properties")
        if not properties:
            return []
        items = []
        nifi2 = self.isNiFi2(services)
        algorithm = properties.get("nifi.sensitive.props.algorithm") or ""
        if nifi2 and algorithm not in NIFI2_SENSITIVE_PROPS_ALGORITHMS:
            items.append({"config-name": "nifi.sensitive.props.algorithm", "item": self.getErrorItem(
                "NiFi 2.x supports only NIFI_PBKDF2_AES_GCM_256 and NIFI_ARGON2_AES_GCM_256.")})
        # an existing cluster's password reaches the advisor masked (SECRET:...), so only typed values are measured
        key = properties.get("nifi.sensitive.props.key") or ""
        if key and not key.startswith("SECRET:") and len(key) < SENSITIVE_PROPS_KEY_MIN_LENGTH \
                and (nifi2 or algorithm in NIFI2_SENSITIVE_PROPS_ALGORITHMS):
            items.append({"config-name": "nifi.sensitive.props.key", "item": self.getErrorItem(
                "NiFi requires a sensitive properties key of at least {0} characters.".format(SENSITIVE_PROPS_KEY_MIN_LENGTH))})
        return self.toConfigurationValidationProblems(items, "nifi-properties")
