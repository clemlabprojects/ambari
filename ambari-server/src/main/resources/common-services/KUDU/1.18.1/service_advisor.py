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


class KuduServiceAdvisor(service_advisor.ServiceAdvisor):
    def __init__(self, *args, **kwargs):
        super(KuduServiceAdvisor, self).__init__(*args, **kwargs)
        self.cardinalitiesDict = {}
        self.mastersWithMultipleInstances = set()
        self.notPreferableOnServerComponents = set()
        self.modifyMastersWithMultipleInstances()
        self.modifyCardinalitiesDict()
        self.modifyHeapSizeProperties()
        self.modifyNotValuableComponents()
        self.modifyComponentsNotPreferableOnServer()
        self.modifyComponentLayoutSchemes()

    def modifyMastersWithMultipleInstances(self):
        pass

    def modifyCardinalitiesDict(self):
        self.cardinalitiesDict.update(
            {
                "KUDU_MASTER": {"min": 1},
                "KUDU_TSERVER": {"min": 1}
            }
        )

    def modifyHeapSizeProperties(self):
        pass

    def modifyNotValuableComponents(self):
        pass

    def modifyComponentsNotPreferableOnServer(self):
        pass

    def modifyComponentLayoutSchemes(self):
        self.componentLayoutSchemes = {}

    def getServiceComponentLayoutValidations(self, services, hosts):
        return self.getServiceComponentCardinalityValidations(services, hosts, "KUDU")
