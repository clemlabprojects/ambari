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

import importlib.util
import os
import traceback

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STACKS_DIR = os.path.join(SCRIPT_DIR, '../../../../../stacks/')
PARENT_FILE = os.path.join(STACKS_DIR, 'service_advisor.py')

try:
  if "BASE_SERVICE_ADVISOR" in os.environ:
    PARENT_FILE = os.environ["BASE_SERVICE_ADVISOR"]
  with open(PARENT_FILE, 'rb') as fp:
    spec = importlib.util.spec_from_file_location('service_advisor', PARENT_FILE)
    service_advisor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(service_advisor)
except Exception:
  traceback.print_exc()
  print("Failed to load parent")


class PolarisServiceAdvisor(service_advisor.ServiceAdvisor):

  def getServiceComponentLayoutValidations(self, services, hosts):
    return self.getServiceComponentCardinalityValidations(services, hosts, "POLARIS")

  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
    recommender = PolarisRecommender()
    recommender.recommendPolarisConfigurations(configurations, clusterData, services, hosts)

  def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
    validator = PolarisValidator()
    return validator.validateListOfConfigUsingMethod(configurations, recommendedDefaults, services, hosts, validator.validators)


class PolarisRecommender(service_advisor.ServiceAdvisor):

  def recommendPolarisConfigurations(self, configurations, clusterData, services, hosts):
    services_list = self.get_services_list(services)

    self._sync_ranger_plugin_flag(configurations, services)

    ranger_plugin_enabled = self._is_ranger_plugin_enabled(configurations, services)
    if not ranger_plugin_enabled or "RANGER" not in services_list:
      return

    ranger_admin_url = self._get_ranger_admin_url(configurations, services)
    if ranger_admin_url:
      put_ranger_security = self.putProperty(configurations, "ranger-polaris-security", services)
      put_ranger_security("ranger.plugin.polaris.policy.rest.url", ranger_admin_url)

    polaris_service_url = self._get_polaris_service_url(configurations, services, hosts)
    if polaris_service_url:
      current_override = self._get_property(configurations, services, "ranger-polaris-plugin-properties", "polaris.service.url")
      if not current_override:
        put_ranger_plugin = self.putProperty(configurations, "ranger-polaris-plugin-properties", services)
        put_ranger_plugin("polaris.service.url", polaris_service_url)

  def _sync_ranger_plugin_flag(self, configurations, services):
    if "ranger-env" in services["configurations"] \
      and "ranger-polaris-plugin-properties" in services["configurations"] \
      and "ranger-polaris-plugin-enabled" in services["configurations"]["ranger-env"]["properties"]:
      put_ranger_plugin = self.putProperty(configurations, "ranger-polaris-plugin-properties", services)
      ranger_enabled = services["configurations"]["ranger-env"]["properties"]["ranger-polaris-plugin-enabled"]
      put_ranger_plugin("ranger-polaris-plugin-enabled", ranger_enabled)

  def _is_ranger_plugin_enabled(self, configurations, services):
    plugin_value = self._get_property(configurations, services, "ranger-polaris-plugin-properties", "ranger-polaris-plugin-enabled")
    return str(plugin_value).lower() == "yes"

  def _get_polaris_service_url(self, configurations, services, hosts):
    polaris_hosts = self.getHostsWithComponent("POLARIS", "POLARIS_SERVER", services, hosts)
    if not polaris_hosts:
      return None

    protocol = self._get_property(configurations, services, "polaris-env", "polaris_protocol") or "http"
    port = self._get_property(configurations, services, "polaris-application-properties", "quarkus.http.port") or "8181"
    host = polaris_hosts[0]["Hosts"]["host_name"]

    return "{0}://{1}:{2}".format(protocol, host, port)

  def _get_ranger_admin_url(self, configurations, services):
    policymgr_url = self._get_property(configurations, services, "admin-properties", "policymgr_external_url")
    if not policymgr_url:
      policymgr_url = self._get_property(configurations, services, "ranger-admin-site", "ranger.service.http.url")
    if policymgr_url and policymgr_url.endswith('/'):
      policymgr_url = policymgr_url.rstrip('/')
    return policymgr_url

  def _get_property(self, configurations, services, site, name):
    if site in configurations and "properties" in configurations[site] and name in configurations[site]["properties"]:
      return configurations[site]["properties"][name]
    if site in services["configurations"] and name in services["configurations"][site]["properties"]:
      return services["configurations"][site]["properties"][name]
    return None


class PolarisValidator(service_advisor.ServiceAdvisor):

  def __init__(self, *args, **kwargs):
    self.as_super = super(PolarisValidator, self)
    self.as_super.__init__(*args, **kwargs)
    self.validators = [("ranger-polaris-plugin-properties", self.validateRangerPolarisPluginProperties)]

  def validateRangerPolarisPluginProperties(self, properties, recommendedDefaults, configurations, services, hosts):
    validation_items = []

    ranger_plugin_enabled = str(properties.get('ranger-polaris-plugin-enabled', 'No')).lower() == 'yes'
    ranger_env = self.getServicesSiteProperties(services, "ranger-env")

    if ranger_plugin_enabled:
      if not ranger_env or ranger_env.get("ranger-polaris-plugin-enabled", "No").lower() != "yes":
        validation_items.append({
          "config-name": "ranger-polaris-plugin-enabled",
          "item": self.getWarnItem(
            "ranger-polaris-plugin-properties/ranger-polaris-plugin-enabled must match ranger-env/ranger-polaris-plugin-enabled"
          )
        })

      if not properties.get("REPOSITORY_CONFIG_USERNAME"):
        validation_items.append({
          "config-name": "REPOSITORY_CONFIG_USERNAME",
          "item": self.getWarnItem("Repository username is required when Ranger plugin is enabled")
        })

    return self.toConfigurationValidationProblems(validation_items, "ranger-polaris-plugin-properties")
