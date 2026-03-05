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

    def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
        recommender = KuduRecommender()
        recommender.recommendKuduConfigurations(configurations, clusterData, services, hosts)

    def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
        validator = KuduValidator()
        return validator.validateListOfConfigUsingMethod(
            configurations,
            recommendedDefaults,
            services,
            hosts,
            validator.validators
        )


class KuduRecommender(service_advisor.ServiceAdvisor):
    def recommendKuduConfigurations(self, configurations, clusterData, services, hosts):
        self._recommend_tls_defaults(configurations, services)
        self._sync_ranger_plugin_flag(configurations, services)

        services_list = self.get_services_list(services)
        ranger_plugin_enabled = self._is_ranger_plugin_enabled(configurations, services)
        if not ranger_plugin_enabled or "RANGER" not in services_list:
            return

        put_ranger_security = self.putProperty(configurations, "ranger-kudu-security", services)
        ranger_admin_url = self._get_ranger_admin_url(configurations, services)
        if ranger_admin_url:
            put_ranger_security("ranger.plugin.kudu.policy.rest.url", ranger_admin_url)

        cluster_name = self._get_property(configurations, services, "cluster-env", "cluster_name")
        if not cluster_name:
            cluster_name = services.get("Versions", {}).get("cluster_name", "cluster")
        current_repo_name = self._get_property(
            configurations, services, "ranger-kudu-security", "ranger.plugin.kudu.service.name"
        )
        if not current_repo_name or "{{" in current_repo_name or "}}" in current_repo_name:
            put_ranger_security("ranger.plugin.kudu.service.name", "{0}_kudu".format(cluster_name))

        if "HDFS" not in services_list:
            put_ranger_audit = self.putProperty(configurations, "ranger-kudu-audit", services)
            put_ranger_audit("xasecure.audit.destination.hdfs", "false")

    def _recommend_tls_defaults(self, configurations, services):
        put_kudu_env = self.putProperty(configurations, "kudu-env", services)
        kudu_env = self._get_site_properties(configurations, services, "kudu-env")

        if "kudu_enable_tls" not in kudu_env:
            put_kudu_env("kudu_enable_tls", "false")
        if not str(kudu_env.get("kudu_tls_cert_file", "")).strip():
            put_kudu_env("kudu_tls_cert_file", "/etc/kudu/conf/tls/kudu-server.crt")
        if not str(kudu_env.get("kudu_tls_private_key_file", "")).strip():
            put_kudu_env("kudu_tls_private_key_file", "/etc/kudu/conf/tls/kudu-server.key")
        if "kudu_tls_ca_cert_file" not in kudu_env:
            put_kudu_env("kudu_tls_ca_cert_file", "")

    def _sync_ranger_plugin_flag(self, configurations, services):
        if "ranger-env" not in services.get("configurations", {}):
            return
        if "ranger-kudu-plugin-properties" not in services.get("configurations", {}):
            return
        ranger_env_props = services["configurations"]["ranger-env"].get("properties", {})
        if "ranger-kudu-plugin-enabled" not in ranger_env_props:
            return

        put_ranger_plugin = self.putProperty(configurations, "ranger-kudu-plugin-properties", services)
        put_ranger_plugin("ranger-kudu-plugin-enabled", ranger_env_props["ranger-kudu-plugin-enabled"])

    def _is_ranger_plugin_enabled(self, configurations, services):
        plugin_value = self._get_property(
            configurations, services, "ranger-kudu-plugin-properties", "ranger-kudu-plugin-enabled"
        )
        return str(plugin_value).strip().lower() == "yes"

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
        if site in services.get("configurations", {}) and name in services["configurations"][site].get("properties", {}):
            return services["configurations"][site]["properties"][name]
        return None

    def _get_site_properties(self, configurations, services, site):
        if site in configurations and "properties" in configurations[site]:
            return configurations[site]["properties"]
        if site in services.get("configurations", {}) and "properties" in services["configurations"][site]:
            return services["configurations"][site]["properties"]
        return {}


class KuduValidator(service_advisor.ServiceAdvisor):
    def __init__(self, *args, **kwargs):
        self.as_super = super(KuduValidator, self)
        self.as_super.__init__(*args, **kwargs)
        self.validators = [
            ("ranger-kudu-plugin-properties", self.validateRangerKuduPluginProperties),
            ("ranger-kudu-audit", self.validateRangerKuduAudit),
            ("kudu-env", self.validateKuduEnv),
        ]

    def validateRangerKuduPluginProperties(self, properties, recommendedDefaults, configurations, services, hosts):
        validation_items = []

        ranger_plugin_enabled = str(properties.get("ranger-kudu-plugin-enabled", "No")).lower() == "yes"
        if not ranger_plugin_enabled:
            return self.toConfigurationValidationProblems(validation_items, "ranger-kudu-plugin-properties")

        ranger_env = self.getServicesSiteProperties(services, "ranger-env")
        if not ranger_env or ranger_env.get("ranger-kudu-plugin-enabled", "No").lower() != "yes":
            validation_items.append({
                "config-name": "ranger-kudu-plugin-enabled",
                "item": self.getWarnItem(
                    "ranger-kudu-plugin-properties/ranger-kudu-plugin-enabled must match ranger-env/ranger-kudu-plugin-enabled"
                )
            })

        if not properties.get("REPOSITORY_CONFIG_USERNAME"):
            validation_items.append({
                "config-name": "REPOSITORY_CONFIG_USERNAME",
                "item": self.getWarnItem("Repository username is required when Ranger plugin is enabled")
            })

        ranger_security = self.getSiteProperties(configurations, "ranger-kudu-security")
        if not ranger_security:
            ranger_security = self.getServicesSiteProperties(services, "ranger-kudu-security")
        policy_url = (ranger_security or {}).get("ranger.plugin.kudu.policy.rest.url", "")
        if not str(policy_url).strip():
            validation_items.append({
                "config-name": "ranger.plugin.kudu.policy.rest.url",
                "item": self.getWarnItem(
                    "ranger-kudu-security/ranger.plugin.kudu.policy.rest.url should be set when Ranger plugin is enabled"
                )
            })

        return self.toConfigurationValidationProblems(validation_items, "ranger-kudu-plugin-properties")

    def validateRangerKuduAudit(self, properties, recommendedDefaults, configurations, services, hosts):
        validation_items = []
        services_list = self.get_services_list(services)
        include_hdfs = "HDFS" in services_list

        hdfs_audit_enabled = str(properties.get("xasecure.audit.destination.hdfs", "false")).lower() == "true"
        if hdfs_audit_enabled and not include_hdfs:
            validation_items.append({
                "config-name": "xasecure.audit.destination.hdfs",
                "item": self.getErrorItem(
                    "HDFS service is not installed. Disable ranger-kudu-audit/xasecure.audit.destination.hdfs or install HDFS."
                )
            })

        return self.toConfigurationValidationProblems(validation_items, "ranger-kudu-audit")

    def validateKuduEnv(self, properties, recommendedDefaults, configurations, services, hosts):
        validation_items = []

        tls_enabled = str(properties.get("kudu_enable_tls", "false")).lower() == "true"
        cert_file = str(properties.get("kudu_tls_cert_file", "")).strip()
        key_file = str(properties.get("kudu_tls_private_key_file", "")).strip()
        if tls_enabled and (not cert_file or not key_file):
            validation_items.append({
                "config-name": "kudu_enable_tls",
                "item": self.getWarnItem(
                    "TLS is enabled. Configure both kudu_tls_cert_file and kudu_tls_private_key_file."
                )
            })

        return self.toConfigurationValidationProblems(validation_items, "kudu-env")
