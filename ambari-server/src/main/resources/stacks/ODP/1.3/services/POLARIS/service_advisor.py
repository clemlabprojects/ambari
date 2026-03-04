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
    put_polaris_env = self.putProperty(configurations, "polaris-env", services)
    put_polaris_env("polaris_filesystem_type", self.getCoreFilesystemType(configurations, services))

    services_list = self.get_services_list(services)

    self.recommendPolarisTlsConfigurations(configurations, services)
    self.recommendPolarisAuthConfigurations(configurations, services)
    self.recommendPolarisDatabaseConfigurations(configurations, services)
    self.recommendPolarisMcpConfigurations(configurations, services, hosts)
    self.recommendPolarisConsoleConfigurations(configurations, services)
    self._sync_ranger_plugin_flag(configurations, services)

    ranger_plugin_enabled = self._is_ranger_plugin_enabled(configurations, services)
    put_app_props = self.putProperty(configurations, "polaris-application-properties", services)
    if ranger_plugin_enabled:
      put_app_props("polaris.authorization.type", "ranger")

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

  def recommendPolarisMcpConfigurations(self, configurations, services, hosts):
    polaris_service_url = self._get_polaris_service_url(configurations, services, hosts)
    if not polaris_service_url:
      return

    polaris_env = self._get_site_properties(configurations, services, "polaris-env")
    put_polaris_env = self.putProperty(configurations, "polaris-env", services)
    current_mcp_base_url = self._get_property(configurations, services, "polaris-env", "polaris_mcp_base_url")
    if not current_mcp_base_url:
      put_polaris_env("polaris_mcp_base_url", "{0}/".format(polaris_service_url.rstrip('/')))

    if not str(polaris_env.get("polaris_mcp_host", "")).strip():
      put_polaris_env("polaris_mcp_host", "0.0.0.0")
    if not str(polaris_env.get("polaris_mcp_port", "")).strip():
      put_polaris_env("polaris_mcp_port", "8000")
    if not str(polaris_env.get("polaris_mcp_transport", "")).strip():
      put_polaris_env("polaris_mcp_transport", "http")
    if not str(polaris_env.get("polaris_mcp_protocol", "")).strip():
      put_polaris_env("polaris_mcp_protocol", "http")

  def recommendPolarisConsoleConfigurations(self, configurations, services):
    polaris_env = self._get_site_properties(configurations, services, "polaris-env")
    put_polaris_env = self.putProperty(configurations, "polaris-env", services)

    if not str(polaris_env.get("polaris_console_host", "")).strip():
      put_polaris_env("polaris_console_host", "0.0.0.0")
    if not polaris_env.get("polaris_console_protocol"):
      put_polaris_env("polaris_console_protocol", "http")
    if not str(polaris_env.get("polaris_console_port", "")).strip():
      put_polaris_env("polaris_console_port", "8282")

  def recommendPolarisTlsConfigurations(self, configurations, services):
    polaris_env = self._get_site_properties(configurations, services, "polaris-env")
    app_props = self._get_site_properties(configurations, services, "polaris-application-properties")
    put_polaris_env = self.putProperty(configurations, "polaris-env", services)
    put_app_props = self.putProperty(configurations, "polaris-application-properties", services)

    if "polaris_ssl_enabled" not in polaris_env:
      put_polaris_env("polaris_ssl_enabled", "false")
    if "polaris_ssl_auto_generate" not in polaris_env:
      put_polaris_env("polaris_ssl_auto_generate", "true")
    if not str(polaris_env.get("polaris_ssl_keystore_password", "")).strip():
      put_polaris_env("polaris_ssl_keystore_password", "changeit")
    if not str(polaris_env.get("polaris_ssl_truststore_password", "")).strip():
      put_polaris_env("polaris_ssl_truststore_password", "changeit")

    ssl_enabled = str(polaris_env.get("polaris_ssl_enabled", "false")).strip().lower() == "true"

    if "polaris_mcp_tls_enabled" not in polaris_env:
      put_polaris_env("polaris_mcp_tls_enabled", "true" if ssl_enabled else "false")
    if "polaris_mcp_tls_auto_generate" not in polaris_env:
      put_polaris_env("polaris_mcp_tls_auto_generate", "true")
    if not str(polaris_env.get("polaris_mcp_tls_cert_file", "")).strip():
      put_polaris_env("polaris_mcp_tls_cert_file", "/etc/polaris/conf/tls/polaris-mcp-cert.pem")
    if not str(polaris_env.get("polaris_mcp_tls_key_file", "")).strip():
      put_polaris_env("polaris_mcp_tls_key_file", "/etc/polaris/conf/tls/polaris-mcp-key.pem")

    if "polaris_console_tls_enabled" not in polaris_env:
      put_polaris_env("polaris_console_tls_enabled", "true" if ssl_enabled else "false")
    if "polaris_console_tls_auto_generate" not in polaris_env:
      put_polaris_env("polaris_console_tls_auto_generate", "true")
    if not str(polaris_env.get("polaris_console_tls_cert_file", "")).strip():
      put_polaris_env("polaris_console_tls_cert_file", "/etc/polaris/conf/tls/polaris-console-cert.pem")
    if not str(polaris_env.get("polaris_console_tls_key_file", "")).strip():
      put_polaris_env("polaris_console_tls_key_file", "/etc/polaris/conf/tls/polaris-console-key.pem")

    mcp_tls_enabled = str(
      polaris_env.get("polaris_mcp_tls_enabled", "true" if ssl_enabled else "false")
    ).strip().lower() == "true"
    if mcp_tls_enabled:
      put_polaris_env("polaris_mcp_protocol", "https")
    elif str(polaris_env.get("polaris_mcp_protocol", "http")).strip().lower() == "https":
      put_polaris_env("polaris_mcp_protocol", "http")

    console_tls_enabled = str(
      polaris_env.get("polaris_console_tls_enabled", "true" if ssl_enabled else "false")
    ).strip().lower() == "true"
    if console_tls_enabled:
      put_polaris_env("polaris_console_protocol", "https")
    elif str(polaris_env.get("polaris_console_protocol", "http")).strip().lower() == "https":
      put_polaris_env("polaris_console_protocol", "http")

    if ssl_enabled:
      put_polaris_env("polaris_protocol", "https")

      if not str(app_props.get("quarkus.http.ssl-port", "")).strip():
        put_app_props("quarkus.http.ssl-port", "8443")

      insecure_requests = str(app_props.get("quarkus.http.insecure-requests", "")).strip().lower()
      if insecure_requests not in ("enabled", "redirect", "disabled"):
        put_app_props("quarkus.http.insecure-requests", "redirect")

      if not str(app_props.get("quarkus.http.ssl.certificate.key-store-file", "")).strip():
        put_app_props("quarkus.http.ssl.certificate.key-store-file", "/etc/polaris/conf/tls/polaris-server-keystore.p12")
      if not str(app_props.get("quarkus.http.ssl.certificate.key-store-file-type", "")).strip():
        put_app_props("quarkus.http.ssl.certificate.key-store-file-type", "PKCS12")
      if not str(app_props.get("quarkus.http.ssl.certificate.key-store-key-alias", "")).strip():
        put_app_props("quarkus.http.ssl.certificate.key-store-key-alias", "polaris")

      if not str(app_props.get("quarkus.http.ssl.certificate.trust-store-file", "")).strip():
        put_app_props("quarkus.http.ssl.certificate.trust-store-file", "/etc/polaris/conf/tls/polaris-server-truststore.p12")
      if not str(app_props.get("quarkus.http.ssl.certificate.trust-store-file-type", "")).strip():
        put_app_props("quarkus.http.ssl.certificate.trust-store-file-type", "PKCS12")
    else:
      if str(polaris_env.get("polaris_protocol", "http")).strip().lower() == "https":
        put_polaris_env("polaris_protocol", "http")
      put_app_props("quarkus.http.insecure-requests", "enabled")
      # Keep TLS-specific Quarkus keys empty when Polaris TLS is disabled.
      for tls_key in (
        "quarkus.http.ssl.certificate.key-store-file",
        "quarkus.http.ssl.certificate.key-store-file-type",
        "quarkus.http.ssl.certificate.key-store-key-alias",
        "quarkus.http.ssl.certificate.trust-store-file",
        "quarkus.http.ssl.certificate.trust-store-file-type",
      ):
        if str(app_props.get(tls_key, "")).strip():
          put_app_props(tls_key, "")

  def recommendPolarisAuthConfigurations(self, configurations, services):
    polaris_env = self._get_site_properties(configurations, services, "polaris-env")
    app_props = self._get_site_properties(configurations, services, "polaris-application-properties")
    oidc_env = self._get_site_properties(configurations, services, "oidc-env")

    put_polaris_env = self.putProperty(configurations, "polaris-env", services)
    put_app_props = self.putProperty(configurations, "polaris-application-properties", services)

    if not str(app_props.get("quarkus.log.console.enabled", "")).strip():
      put_app_props("quarkus.log.console.enabled", "false")
    if not str(app_props.get("quarkus.log.file.enabled", "")).strip():
      put_app_props("quarkus.log.file.enabled", "true")

    auth_type = str(app_props.get("polaris.authentication.type", "internal")).strip().lower()
    if auth_type not in ("internal", "external", "mixed"):
      auth_type = "internal"
      put_app_props("polaris.authentication.type", auth_type)

    security_enabled = self._is_security_enabled(configurations, services)
    oidc_admin_url = str(oidc_env.get("oidc_admin_url", "")).strip()
    oidc_realm = str(oidc_env.get("oidc_realm", "")).strip()
    oidc_available = security_enabled and oidc_admin_url != "" and oidc_realm != ""

    if not str(polaris_env.get("polaris_bootstrap_realms", "")).strip():
      default_realms = str(app_props.get("polaris.realm-context.realms", "POLARIS")).strip() or "POLARIS"
      put_polaris_env("polaris_bootstrap_realms", default_realms)

    if "polaris_oidc_use_cluster_config" not in polaris_env:
      put_polaris_env("polaris_oidc_use_cluster_config", "true")

    if auth_type in ("external", "mixed"):
      put_app_props("quarkus.oidc.tenant-enabled", "true")
      if not str(app_props.get("quarkus.oidc.application-type", "")).strip():
        put_app_props("quarkus.oidc.application-type", "service")

      use_cluster_oidc = str(polaris_env.get("polaris_oidc_use_cluster_config", "true")).strip().lower() == "true"
      if use_cluster_oidc and oidc_available:
        put_app_props("quarkus.oidc.auth-server-url", "{0}/realms/{1}".format(oidc_admin_url.rstrip('/'), oidc_realm))
        cluster_name = services.get("clusterName", "cluster")
        put_app_props("quarkus.oidc.client-id", "{0}-polaris".format(cluster_name))
    else:
      put_app_props("quarkus.oidc.tenant-enabled", "false")

  def _is_ranger_plugin_enabled(self, configurations, services):
    plugin_value = self._get_property(configurations, services, "ranger-polaris-plugin-properties", "ranger-polaris-plugin-enabled")
    return str(plugin_value).lower() == "yes"

  def _is_security_enabled(self, configurations, services):
    security_flag = self._get_property(configurations, services, "cluster-env", "security_enabled")
    return str(security_flag).strip().lower() == "true"

  def _get_polaris_service_url(self, configurations, services, hosts):
    polaris_hosts = self.getHostsWithComponent("POLARIS", "POLARIS_SERVER", services, hosts)
    if not polaris_hosts:
      return None

    ssl_enabled = str(
      self._get_property(configurations, services, "polaris-env", "polaris_ssl_enabled") or "false"
    ).strip().lower() == "true"

    protocol = (self._get_property(configurations, services, "polaris-env", "polaris_protocol") or "http").lower()
    if ssl_enabled:
      protocol = "https"
    elif protocol != "http":
      protocol = "http"

    if protocol == "https":
      port = self._get_property(configurations, services, "polaris-application-properties", "quarkus.http.ssl-port") or "8443"
    else:
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

  def _get_site_properties(self, configurations, services, site):
    if site in configurations and "properties" in configurations[site]:
      return configurations[site]["properties"]
    if site in services["configurations"] and "properties" in services["configurations"][site]:
      return services["configurations"][site]["properties"]
    return {}

  def recommendPolarisDatabaseConfigurations(self, configurations, services):
    put_db_props = self.putProperty(configurations, "polaris-db-properties", services)
    put_app_props = self.putProperty(configurations, "polaris-application-properties", services)

    db_flavor = str(self._get_property(configurations, services, "polaris-db-properties", "DB_FLAVOR") or "POSTGRES").upper()
    db_host = str(self._get_property(configurations, services, "polaris-db-properties", "db_host") or "localhost").strip() or "localhost"
    db_port = str(self._get_property(configurations, services, "polaris-db-properties", "db_port") or "5432").strip() or "5432"
    db_name = str(self._get_property(configurations, services, "polaris-db-properties", "db_name") or "polaris").strip() or "polaris"
    db_user = str(self._get_property(configurations, services, "polaris-db-properties", "db_user") or "polaris").strip() or "polaris"
    db_password = self._get_property(configurations, services, "polaris-db-properties", "db_password")
    create_db_dbuser = self._get_property(configurations, services, "polaris-db-properties", "create_db_dbuser")
    db_root_user = self._get_property(configurations, services, "polaris-db-properties", "db_root_user")
    persistence_type = self._get_property(configurations, services, "polaris-application-properties", "polaris.persistence.type")

    if db_flavor != "POSTGRES":
      db_flavor = "POSTGRES"

    db_host_port = db_host if ":" in db_host else "{0}:{1}".format(db_host, db_port)
    root_jdbc_url = "jdbc:postgresql://{0}/postgres".format(db_host_port)

    put_db_props("polaris_privelege_user_jdbc_url", root_jdbc_url)
    if create_db_dbuser is None:
      put_db_props("create_db_dbuser", "true")
    if not str(db_root_user or "").strip():
      put_db_props("db_root_user", "postgres")

    if not str(persistence_type or "").strip():
      put_app_props("polaris.persistence.type", "relational-jdbc")

    put_app_props("quarkus.datasource.db-kind", "postgresql")
    put_app_props("quarkus.datasource.jdbc.driver", "org.postgresql.Driver")
    put_app_props("quarkus.datasource.jdbc.url", "jdbc:postgresql://{0}/{1}".format(db_host_port, db_name))
    put_app_props("quarkus.datasource.username", db_user)
    if db_password is not None:
      put_app_props("quarkus.datasource.password", db_password)


class PolarisValidator(service_advisor.ServiceAdvisor):

  def __init__(self, *args, **kwargs):
    self.as_super = super(PolarisValidator, self)
    self.as_super.__init__(*args, **kwargs)
    self.validators = [
      ("ranger-polaris-plugin-properties", self.validateRangerPolarisPluginProperties),
      ("polaris-db-properties", self.validatePolarisDbProperties),
      ("polaris-application-properties", self.validatePolarisApplicationProperties),
      ("polaris-env", self.validatePolarisEnvProperties)
    ]

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

  def validatePolarisDbProperties(self, properties, recommendedDefaults, configurations, services, hosts):
    validation_items = []

    db_flavor = str(properties.get("DB_FLAVOR", "POSTGRES")).upper()
    if db_flavor != "POSTGRES":
      validation_items.append({
        "config-name": "DB_FLAVOR",
        "item": self.getWarnItem("Only POSTGRES is supported for Polaris relational persistence in this stack")
      })

    create_db = str(properties.get("create_db_dbuser", "true")).lower() == "true"
    if create_db:
      if not str(properties.get("db_root_user", "")).strip():
        validation_items.append({
          "config-name": "db_root_user",
          "item": self.getWarnItem("db_root_user should be set when create_db_dbuser=true")
        })
      if not str(properties.get("db_root_password", "")).strip():
        validation_items.append({
          "config-name": "db_root_password",
          "item": self.getWarnItem("db_root_password should be set when create_db_dbuser=true")
        })
      if not str(properties.get("polaris_privelege_user_jdbc_url", "")).strip():
        validation_items.append({
          "config-name": "polaris_privelege_user_jdbc_url",
          "item": self.getWarnItem("polaris_privelege_user_jdbc_url should be set when create_db_dbuser=true")
        })
    else:
      validation_items.append({
        "config-name": "create_db_dbuser",
        "item": self.getWarnItem(
          "When create_db_dbuser=false, DB/user/grants must be pre-created manually (including CREATE on the Polaris DB)."
        )
      })

    return self.toConfigurationValidationProblems(validation_items, "polaris-db-properties")

  def validatePolarisApplicationProperties(self, properties, recommendedDefaults, configurations, services, hosts):
    validation_items = []

    polaris_env = {}
    if "polaris-env" in configurations and "properties" in configurations["polaris-env"]:
      polaris_env = configurations["polaris-env"]["properties"]
    else:
      polaris_env = self.getServicesSiteProperties(services, "polaris-env") or {}
    ssl_enabled = str(polaris_env.get("polaris_ssl_enabled", "false")).strip().lower() == "true"

    if "quarkus.log.console.enable" in properties:
      validation_items.append({
        "config-name": "quarkus.log.console.enable",
        "item": self.getWarnItem("quarkus.log.console.enable is deprecated; use quarkus.log.console.enabled")
      })
    if "quarkus.log.file.enable" in properties:
      validation_items.append({
        "config-name": "quarkus.log.file.enable",
        "item": self.getWarnItem("quarkus.log.file.enable is deprecated; use quarkus.log.file.enabled")
      })

    auth_type = str(properties.get("polaris.authentication.type", "internal")).strip().lower()
    if auth_type not in ("internal", "external", "mixed"):
      validation_items.append({
        "config-name": "polaris.authentication.type",
        "item": self.getWarnItem("polaris.authentication.type must be one of: internal, external, mixed")
      })
      auth_type = "internal"

    authz_type = str(properties.get("polaris.authorization.type", "internal")).strip().lower()
    if authz_type not in ("internal", "opa", "ranger"):
      validation_items.append({
        "config-name": "polaris.authorization.type",
        "item": self.getWarnItem("polaris.authorization.type must be one of: internal, opa, ranger")
      })
      authz_type = "internal"

    ranger_plugin_props = self.getSiteProperties(configurations, "ranger-polaris-plugin-properties")
    if not ranger_plugin_props:
      ranger_plugin_props = self.getServicesSiteProperties(services, "ranger-polaris-plugin-properties")
    ranger_plugin_enabled = str(
      (ranger_plugin_props or {}).get("ranger-polaris-plugin-enabled", "No")
    ).strip().lower() == "yes"

    if not ranger_plugin_enabled and authz_type == "ranger":
      validation_items.append({
        "config-name": "polaris.authorization.type",
        "item": self.getWarnItem(
          "polaris.authorization.type=ranger requires ranger-polaris-plugin-enabled=true"
        )
      })

    if auth_type in ("external", "mixed"):
      tenant_enabled = str(properties.get("quarkus.oidc.tenant-enabled", "false")).strip().lower()
      if tenant_enabled != "true":
        validation_items.append({
          "config-name": "quarkus.oidc.tenant-enabled",
          "item": self.getWarnItem("quarkus.oidc.tenant-enabled should be true when using external or mixed authentication")
        })
      if not str(properties.get("quarkus.oidc.auth-server-url", "")).strip():
        validation_items.append({
          "config-name": "quarkus.oidc.auth-server-url",
          "item": self.getWarnItem("quarkus.oidc.auth-server-url should be set when using external or mixed authentication")
        })
      if not str(properties.get("quarkus.oidc.client-id", "")).strip():
        validation_items.append({
          "config-name": "quarkus.oidc.client-id",
          "item": self.getWarnItem("quarkus.oidc.client-id should be set when using external or mixed authentication")
        })

    persistence_type = str(properties.get("polaris.persistence.type", "in-memory")).lower()
    if persistence_type == "relational-jdbc":
      required_props = [
        "quarkus.datasource.db-kind",
        "quarkus.datasource.jdbc.driver",
        "quarkus.datasource.jdbc.url",
        "quarkus.datasource.username",
      ]
      for required_prop in required_props:
        if not str(properties.get(required_prop, "")).strip():
          validation_items.append({
            "config-name": required_prop,
            "item": self.getWarnItem("{0} must be configured when polaris.persistence.type=relational-jdbc".format(required_prop))
          })

      db_password = properties.get("quarkus.datasource.password", None)
      if db_password is None or str(db_password).strip() == "":
        validation_items.append({
          "config-name": "quarkus.datasource.password",
          "item": self.getWarnItem("quarkus.datasource.password should be set when polaris.persistence.type=relational-jdbc")
        })

      db_kind = str(properties.get("quarkus.datasource.db-kind", "")).lower()
      if db_kind and db_kind != "postgresql":
        validation_items.append({
          "config-name": "quarkus.datasource.db-kind",
          "item": self.getWarnItem("Only postgresql db-kind is supported for Polaris relational persistence in this stack")
        })

    insecure_requests = str(properties.get("quarkus.http.insecure-requests", "redirect")).strip().lower()
    if ssl_enabled:
      if insecure_requests not in ("enabled", "redirect", "disabled"):
        validation_items.append({
          "config-name": "quarkus.http.insecure-requests",
          "item": self.getErrorItem("quarkus.http.insecure-requests must be one of: enabled, redirect, disabled")
        })
    else:
      if insecure_requests != "enabled":
        validation_items.append({
          "config-name": "quarkus.http.insecure-requests",
          "item": self.getErrorItem(
            "quarkus.http.insecure-requests must be enabled when polaris_ssl_enabled=false"
          )
        })

      for tls_key in (
        "quarkus.http.ssl.certificate.key-store-file",
        "quarkus.http.ssl.certificate.key-store-file-type",
        "quarkus.http.ssl.certificate.key-store-key-alias",
        "quarkus.http.ssl.certificate.trust-store-file",
        "quarkus.http.ssl.certificate.trust-store-file-type",
      ):
        if str(properties.get(tls_key, "")).strip():
          validation_items.append({
            "config-name": tls_key,
            "item": self.getErrorItem(
              "{0} must be empty when polaris_ssl_enabled=false".format(tls_key)
            )
          })

    ssl_port = str(properties.get("quarkus.http.ssl-port", "")).strip()
    if ssl_port and not ssl_port.isdigit():
      validation_items.append({
        "config-name": "quarkus.http.ssl-port",
        "item": self.getWarnItem("quarkus.http.ssl-port must be a valid numeric TCP port")
      })

    return self.toConfigurationValidationProblems(validation_items, "polaris-application-properties")

  def validatePolarisEnvProperties(self, properties, recommendedDefaults, configurations, services, hosts):
    validation_items = []

    protocol = str(properties.get("polaris_protocol", "http")).strip().lower()
    if protocol not in ("http", "https"):
      validation_items.append({
        "config-name": "polaris_protocol",
        "item": self.getWarnItem("polaris_protocol must be either http or https")
      })

    ssl_enabled = str(properties.get("polaris_ssl_enabled", "false")).strip().lower() == "true"
    if ssl_enabled and protocol != "https":
      validation_items.append({
        "config-name": "polaris_protocol",
        "item": self.getErrorItem("polaris_protocol should be https when polaris_ssl_enabled=true")
      })
    if not ssl_enabled and protocol == "https":
      validation_items.append({
        "config-name": "polaris_ssl_enabled",
        "item": self.getErrorItem("polaris_ssl_enabled should be true when polaris_protocol=https")
      })

    if ssl_enabled:
      if not str(properties.get("polaris_ssl_keystore_password", "")).strip():
        validation_items.append({
          "config-name": "polaris_ssl_keystore_password",
          "item": self.getWarnItem("polaris_ssl_keystore_password should be set when TLS is enabled")
        })
      if not str(properties.get("polaris_ssl_truststore_password", "")).strip():
        validation_items.append({
          "config-name": "polaris_ssl_truststore_password",
          "item": self.getWarnItem("polaris_ssl_truststore_password should be set when TLS is enabled")
        })

    mcp_transport = str(properties.get("polaris_mcp_transport", "http")).lower()
    if mcp_transport not in ("http", "sse", "stdio"):
      validation_items.append({
        "config-name": "polaris_mcp_transport",
        "item": self.getWarnItem("polaris_mcp_transport must be one of: http, sse, stdio")
      })

    mcp_protocol = str(properties.get("polaris_mcp_protocol", "http")).lower()
    if mcp_protocol not in ("http", "https"):
      validation_items.append({
        "config-name": "polaris_mcp_protocol",
        "item": self.getWarnItem("polaris_mcp_protocol must be either http or https")
      })

    mcp_port = str(properties.get("polaris_mcp_port", "8000")).strip()
    if not mcp_port.isdigit():
      validation_items.append({
        "config-name": "polaris_mcp_port",
        "item": self.getWarnItem("polaris_mcp_port must be a valid numeric TCP port")
      })

    mcp_hosts = self.getHostsWithComponent("POLARIS", "POLARIS_MCP_SERVER", services, hosts)
    if mcp_hosts and mcp_transport == "stdio":
      validation_items.append({
        "config-name": "polaris_mcp_transport",
        "item": self.getWarnItem("POLARIS_MCP_SERVER should use http or sse transport in managed mode")
      })

    console_protocol = str(properties.get("polaris_console_protocol", "http")).lower()
    if console_protocol not in ("http", "https"):
      validation_items.append({
        "config-name": "polaris_console_protocol",
        "item": self.getWarnItem("polaris_console_protocol must be either http or https")
      })

    console_port = str(properties.get("polaris_console_port", "8282")).strip()
    if not console_port.isdigit():
      validation_items.append({
        "config-name": "polaris_console_port",
        "item": self.getWarnItem("polaris_console_port must be a valid numeric TCP port")
      })

    app_props = {}
    if "polaris-application-properties" in configurations and "properties" in configurations["polaris-application-properties"]:
      app_props = configurations["polaris-application-properties"]["properties"]
    else:
      app_defaults = self.getServicesSiteProperties(services, "polaris-application-properties")
      if app_defaults:
        app_props = app_defaults

    if ssl_enabled:
      if not str(app_props.get("quarkus.http.ssl-port", "")).strip():
        validation_items.append({
          "config-name": "quarkus.http.ssl-port",
          "item": self.getErrorItem("quarkus.http.ssl-port should be set when TLS is enabled")
        })
      if not str(app_props.get("quarkus.http.ssl.certificate.key-store-file", "")).strip():
        validation_items.append({
          "config-name": "quarkus.http.ssl.certificate.key-store-file",
          "item": self.getErrorItem("TLS keystore file should be set when TLS is enabled")
        })

    auth_type = str(app_props.get("polaris.authentication.type", "internal")).strip().lower()
    if auth_type in ("internal", "mixed"):
      if not str(properties.get("polaris_admin_username", "")).strip():
        validation_items.append({
          "config-name": "polaris_admin_username",
          "item": self.getWarnItem("polaris_admin_username must be set when using internal or mixed authentication")
        })
      if not str(properties.get("polaris_admin_password", "")).strip():
        validation_items.append({
          "config-name": "polaris_admin_password",
          "item": self.getWarnItem("polaris_admin_password should be set when using internal or mixed authentication")
        })
      if not str(properties.get("polaris_bootstrap_realms", "")).strip():
        validation_items.append({
          "config-name": "polaris_bootstrap_realms",
          "item": self.getWarnItem("polaris_bootstrap_realms should be set when using internal or mixed authentication")
        })

    if auth_type in ("external", "mixed"):
      use_cluster_oidc = str(properties.get("polaris_oidc_use_cluster_config", "true")).strip().lower() == "true"
      if use_cluster_oidc:
        oidc_env = self.getServicesSiteProperties(services, "oidc-env") or {}
        if not str(oidc_env.get("oidc_admin_url", "")).strip():
          validation_items.append({
            "config-name": "polaris_oidc_use_cluster_config",
            "item": self.getWarnItem("Cluster OIDC admin URL is missing while polaris_oidc_use_cluster_config=true")
          })
        if not str(oidc_env.get("oidc_realm", "")).strip():
          validation_items.append({
            "config-name": "polaris_oidc_use_cluster_config",
            "item": self.getWarnItem("Cluster OIDC realm is missing while polaris_oidc_use_cluster_config=true")
          })
      else:
        if not str(properties.get("polaris_oidc_override_auth_server_url", "")).strip():
          validation_items.append({
            "config-name": "polaris_oidc_override_auth_server_url",
            "item": self.getWarnItem("polaris_oidc_override_auth_server_url should be set when not using cluster OIDC config")
          })
        if not str(properties.get("polaris_oidc_override_client_id", "")).strip():
          validation_items.append({
            "config-name": "polaris_oidc_override_client_id",
            "item": self.getWarnItem("polaris_oidc_override_client_id should be set when not using cluster OIDC config")
          })
        if not str(properties.get("polaris_oidc_override_client_secret", "")).strip():
          validation_items.append({
            "config-name": "polaris_oidc_override_client_secret",
            "item": self.getWarnItem("polaris_oidc_override_client_secret should be set when not using cluster OIDC config")
          })

    return self.toConfigurationValidationProblems(validation_items, "polaris-env")
