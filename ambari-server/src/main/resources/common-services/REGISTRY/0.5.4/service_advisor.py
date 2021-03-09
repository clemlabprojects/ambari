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

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SERVICE_DIR = os.path.join(SCRIPT_DIR, '../0.5.0/')
PARENT_FILE = os.path.join(SERVICE_DIR, 'service_advisor.py')

try:
  with open(PARENT_FILE, 'rb') as fp:
    service_advisor = imp.load_module('service_advisor', fp, PARENT_FILE, ('.py', 'rb', imp.PY_SOURCE))
except Exception as e:
  traceback.print_exc()
  print "Failed to load parent"


class REGISTRY054ServiceAdvisor(service_advisor.REGISTRY050ServiceAdvisor):
  def getServiceConfigurationRecommendationsForSSO(self, configurations, clusterData, services, hosts):
    """
    Any SSO-related configuration recommendations for the service should be defined in this function.
    """
    ambari_configuration = self.get_ambari_configuration(services)
    ambari_sso_details = ambari_configuration.get_ambari_sso_details() if ambari_configuration else None

    if ambari_sso_details and ambari_sso_details.is_managing_services():
      putRegistryCommonProperty = self.putProperty(configurations, "registry-sso-config")

      # If SSO should be enabled for this service
      if ambari_sso_details.should_enable_sso('REGISTRY'):
        putRegistryCommonProperty("registry.sso.enabled", "true")
        putRegistryCommonProperty("registry.authentication.provider.url", ambari_sso_details.get_sso_provider_url())
        putRegistryCommonProperty("registry.public.key.pem", ambari_sso_details.get_sso_provider_certificate(False, True))
      else:
        putRegistryCommonProperty("registry.sso.enabled", "false")

  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
    super(REGISTRY054ServiceAdvisor, self).getServiceConfigurationRecommendations(configurations, clusterData, services, hosts)
    self.getServiceConfigurationRecommendationsForSSO(configurations, clusterData, services, hosts)