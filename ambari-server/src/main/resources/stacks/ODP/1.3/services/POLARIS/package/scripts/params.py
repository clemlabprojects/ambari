#!/usr/bin/env python
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

from resource_management.libraries.functions.default import default
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions import conf_select
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.get_bare_principal import get_bare_principal
from resource_management.libraries.functions.get_kinit_path import get_kinit_path
from resource_management.libraries.functions.is_empty import is_empty
from resource_management.libraries.functions.setup_ranger_plugin_xml import generate_ranger_service_config
from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.stack_features import check_stack_feature, get_stack_feature_version


def _to_bool(value, default=False):
  if value is None:
    return default
  return str(value).strip().lower() in ("true", "yes", "1", "on")


def _to_int(value, default):
  try:
    return int(str(value).strip())
  except Exception:
    return default


def _resolve_ambari_template_value(raw_value, replacements):
  if raw_value is None:
    return ""
  value = str(raw_value)
  for key, replacement in replacements.items():
    value = value.replace("{{" + key + "}}", str(replacement))
  return value.strip()


config = Script.get_config()
stack_root = Script.get_stack_root()

# Command/stack execution context from Ambari.
version = default("/commandParams/version", None)
version_for_stack_feature_checks = get_stack_feature_version(config)
retry_enabled = default("/commandParams/command_retry_enabled", False)

# Core runtime identities and Java prerequisites used by all Polaris components.
polaris_user = config['configurations']['polaris-env']['polaris_user']
user_group = config['configurations']['cluster-env']['user_group']
java64_home = config['ambariLevelParams']['java_home']
host_level_params = default("/hostLevelParams", {})
java_home_selector = default("/hostLevelParams/java_home_selector", "java_home")
stack_supports_secondary_java_home = check_stack_feature(
  StackFeature.SECONDARY_JAVA_HOME_SUPPORT, version_for_stack_feature_checks
)
# Polaris requires Java 17+. polaris_java_home must point to a Java 17+ installation;
# it resolves from per-component javaHomeSelector metadata and falls back to java64_home
# so the attribute always exists for template rendering,
# but the default in polaris-env.xml should be set to the correct Java 17 path.
polaris_java_home = java64_home
if stack_supports_secondary_java_home:
  polaris_java_home = host_level_params.get(java_home_selector, None) or java64_home
jdk_location = default("/ambariLevelParams/jdk_location", "/usr/share/java")
custom_postgres_jdbc_name = default("/ambariLevelParams/custom_postgres_jdbc_name", None)

# Cluster-level security context used to decide kerberos-aware execution paths.
security_enabled = str(default("/configurations/cluster-env/security_enabled", "false")).lower() == "true"
kinit_path_local = get_kinit_path(default("/configurations/kerberos-env/executable_search_paths", None))

# Polaris filesystem/layout parameters consumed by scripts during configure/start.
polaris_log_dir = default("/configurations/polaris-env/polaris_log_dir", "/var/log/polaris")
polaris_pid_dir = default("/configurations/polaris-env/polaris_pid_dir", "/var/run/polaris")
polaris_pid_file = format("{polaris_pid_dir}/polaris.pid")
polaris_conf_dir = default("/configurations/polaris-env/polaris_conf_dir", "/etc/polaris/conf")
polaris_conf_file = default("/configurations/polaris-env/polaris_conf_file", "application.properties")
polaris_data_dir = default("/configurations/polaris-env/polaris_data_dir", "/var/lib/polaris")
polaris_keytab_path = default("/configurations/polaris-env/polaris_keytab", "/etc/security/keytabs/polaris.service.keytab")
polaris_principal_value = default("/configurations/polaris-env/polaris_principal", "polaris/_HOST@EXAMPLE.COM")
polaris_jaas_conf = default("/configurations/polaris-env/polaris_jaas_file", format("{polaris_conf_dir}/polaris_jaas.conf"))
polaris_jaas_context = default("/configurations/polaris-env/polaris_jaas_context", "PolarisServer")

polaris_home = default("/configurations/polaris-env/polaris_home",
                      format("{stack_root}/current/polaris-server"))
polaris_tools_home = default("/configurations/polaris-env/polaris_tools_home",
                            format("{stack_root}/current/polaris-tools"))
polaris_mcp_home = default("/configurations/polaris-env/polaris_mcp_home", polaris_tools_home)
polaris_console_home = default("/configurations/polaris-env/polaris_console_home", polaris_tools_home)

# User-facing Polaris service parameters (bootstrap principals, OIDC toggles, TLS).
# These are kept in params.py so both configure-time templating and runtime actions
# use the same normalized values.
polaris_env_content = config['configurations']['polaris-env']['content']
polaris_logging_properties_content = default(
  "/configurations/polaris-logging-properties/content",
  "# Additional Quarkus logging categories for Polaris.\n"
  "# Example:\n"
  "# quarkus.log.category.\"org.apache.polaris.extension.auth.ranger\".level=DEBUG\n"
  "# quarkus.log.category.\"org.apache.ranger.plugin.util.PolicyRefresher\".level=DEBUG\n"
)
polaris_opts = config['configurations']['polaris-env']['polaris_opts']
polaris_admin_username = config['configurations']['polaris-env']['polaris_admin_username']
polaris_admin_password = config['configurations']['polaris-env']['polaris_admin_password']
polaris_admin_password_escaped = str(polaris_admin_password or "").replace("'", "'\"'\"'")
polaris_bootstrap_realms_raw = default("/configurations/polaris-env/polaris_bootstrap_realms", "POLARIS").strip()
polaris_oidc_use_cluster_config = _to_bool(default("/configurations/polaris-env/polaris_oidc_use_cluster_config", "true"), True)
polaris_oidc_override_auth_server_url = str(default("/configurations/polaris-env/polaris_oidc_override_auth_server_url", "")).strip()
polaris_oidc_override_client_id = str(default("/configurations/polaris-env/polaris_oidc_override_client_id", "")).strip()
polaris_oidc_override_client_secret = str(default("/configurations/polaris-env/polaris_oidc_override_client_secret", "")).strip()
polaris_ozone_principal_secret = str(default("/configurations/polaris-env/polaris_ozone_principal_secret", "ozone-engine-secret"))
polaris_ozone_catalog_name = str(default("/configurations/polaris-env/polaris_ozone_catalog_name", "ozone")).strip() or "ozone"
polaris_ozone_catalog_base_location = str(default("/configurations/polaris-env/polaris_ozone_catalog_base_location", "s3://ozone/polaris/")).strip()
polaris_ozone_catalog_allowed_locations_raw = str(default("/configurations/polaris-env/polaris_ozone_catalog_allowed_locations", "s3://ozone/")).strip()
polaris_ozone_s3_endpoint = str(default("/configurations/polaris-env/polaris_ozone_s3_endpoint", "")).strip()
polaris_ozone_s3_region = str(default("/configurations/polaris-env/polaris_ozone_s3_region", "us-east-1")).strip() or "us-east-1"
polaris_ozone_s3_path_style_access = _to_bool(default("/configurations/polaris-env/polaris_ozone_s3_path_style_access", "true"), True)
polaris_ssl_enabled = _to_bool(default("/configurations/polaris-env/polaris_ssl_enabled", "false"), False)
polaris_ssl_auto_generate = _to_bool(default("/configurations/polaris-env/polaris_ssl_auto_generate", "true"), True)
polaris_ssl_keystore_password = str(default("/configurations/polaris-env/polaris_ssl_keystore_password", "changeit"))
polaris_ssl_truststore_password = str(default("/configurations/polaris-env/polaris_ssl_truststore_password", "changeit"))
polaris_ssl_keystore_password_escaped = polaris_ssl_keystore_password.replace("'", "'\"'\"'")
polaris_ssl_truststore_password_escaped = polaris_ssl_truststore_password.replace("'", "'\"'\"'")
polaris_mcp_tls_enabled = _to_bool(default("/configurations/polaris-env/polaris_mcp_tls_enabled", "false"), False)
polaris_mcp_tls_auto_generate = _to_bool(default("/configurations/polaris-env/polaris_mcp_tls_auto_generate", "true"), True)
polaris_mcp_tls_cert_file = str(default("/configurations/polaris-env/polaris_mcp_tls_cert_file", "/etc/polaris/conf/tls/polaris-mcp-cert.pem")).strip()
polaris_mcp_tls_key_file = str(default("/configurations/polaris-env/polaris_mcp_tls_key_file", "/etc/polaris/conf/tls/polaris-mcp-key.pem")).strip()
polaris_mcp_tls_ca_file = str(default("/configurations/polaris-env/polaris_mcp_tls_ca_file", "")).strip()
polaris_console_tls_enabled = _to_bool(default("/configurations/polaris-env/polaris_console_tls_enabled", "false"), False)
polaris_console_tls_auto_generate = _to_bool(default("/configurations/polaris-env/polaris_console_tls_auto_generate", "true"), True)
polaris_console_tls_cert_file = str(default("/configurations/polaris-env/polaris_console_tls_cert_file", "/etc/polaris/conf/tls/polaris-console-cert.pem")).strip()
polaris_console_tls_key_file = str(default("/configurations/polaris-env/polaris_console_tls_key_file", "/etc/polaris/conf/tls/polaris-console-key.pem")).strip()
polaris_jaas_conf_template = config['configurations']['polaris-jaas-conf']['content']

polaris_start_command = default("/configurations/polaris-env/polaris_start_command", "").strip()
polaris_stop_command = default("/configurations/polaris-env/polaris_stop_command", "").strip()

# Backward-compatible command normalization. Older stack definitions referenced
# `bin/polaris` but service control must run `bin/server`.
if not polaris_start_command:
  polaris_start_command = format("{polaris_home}/bin/server")
else:
  # Backward compatibility: older stacks used bin/polaris as start command.
  # bin/polaris is now the CLI wrapper; service runtime must use bin/server.
  start_parts = polaris_start_command.split()
  if len(start_parts) > 0 and start_parts[0].endswith("/bin/polaris"):
    start_parts[0] = start_parts[0][:-len("/bin/polaris")] + "/bin/server"
    polaris_start_command = " ".join(start_parts)
if not polaris_stop_command:
  polaris_stop_command = None
else:
  stop_parts = polaris_stop_command.split()
  if len(stop_parts) > 0 and stop_parts[0].endswith("/bin/polaris"):
    stop_parts[0] = stop_parts[0][:-len("/bin/polaris")] + "/bin/server"
    polaris_stop_command = " ".join(stop_parts)

# Load mutable config dictionaries once; all later derived values are written
# back into these maps before files are rendered by configure().
application_properties = dict(config['configurations']['polaris-application-properties'])
polaris_db_properties = dict(config['configurations']['polaris-db-properties'])
cluster_name = config.get('clusterName', "cluster")

# Backward compatibility: this key was used in older Polaris auth wiring but in
# current Polaris it is interpreted as a realm type and breaks config parsing.
for legacy_auth_key in (
  "polaris.authentication.active-roles-provider.type",
  "polaris.active-roles-provider.type",
):
  if legacy_auth_key in application_properties:
    del application_properties[legacy_auth_key]

oidc_admin_url = str(default("/configurations/oidc-env/oidc_admin_url", "")).strip()
oidc_realm = str(default("/configurations/oidc-env/oidc_realm", "")).strip()
oidc_available = oidc_admin_url != "" and oidc_realm != ""

# Normalize legacy Quarkus keys so operators can upgrade configs without
# startup failures from renamed options.
# Normalize deprecated Quarkus logging keys to current *.enabled variants.
if "quarkus.log.console.enable" in application_properties:
  if "quarkus.log.console.enabled" not in application_properties:
    application_properties["quarkus.log.console.enabled"] = application_properties["quarkus.log.console.enable"]
  del application_properties["quarkus.log.console.enable"]
if "quarkus.log.file.enable" in application_properties:
  if "quarkus.log.file.enabled" not in application_properties:
    application_properties["quarkus.log.file.enabled"] = application_properties["quarkus.log.file.enable"]
  del application_properties["quarkus.log.file.enable"]

if "quarkus.log.console.enabled" not in application_properties:
  application_properties["quarkus.log.console.enabled"] = "false"
if "quarkus.log.file.enabled" not in application_properties:
  application_properties["quarkus.log.file.enabled"] = "true"

# Polaris TLS settings are modeled in polaris-env; map to Quarkus TLS properties.
if polaris_ssl_enabled:
  if not str(application_properties.get("quarkus.http.ssl-port", "")).strip():
    application_properties["quarkus.http.ssl-port"] = "8443"

  insecure_requests = str(application_properties.get("quarkus.http.insecure-requests", "")).strip().lower()
  if insecure_requests not in ("enabled", "redirect", "disabled"):
    application_properties["quarkus.http.insecure-requests"] = "redirect"

  if not str(application_properties.get("quarkus.http.ssl.certificate.key-store-file", "")).strip():
    application_properties["quarkus.http.ssl.certificate.key-store-file"] = "/etc/polaris/conf/tls/polaris-server-keystore.p12"
  if not str(application_properties.get("quarkus.http.ssl.certificate.key-store-file-type", "")).strip():
    application_properties["quarkus.http.ssl.certificate.key-store-file-type"] = "PKCS12"
  if not str(application_properties.get("quarkus.http.ssl.certificate.key-store-key-alias", "")).strip():
    application_properties["quarkus.http.ssl.certificate.key-store-key-alias"] = "polaris"

  if not str(application_properties.get("quarkus.http.ssl.certificate.trust-store-file", "")).strip():
    application_properties["quarkus.http.ssl.certificate.trust-store-file"] = "/etc/polaris/conf/tls/polaris-server-truststore.p12"
  if not str(application_properties.get("quarkus.http.ssl.certificate.trust-store-file-type", "")).strip():
    application_properties["quarkus.http.ssl.certificate.trust-store-file-type"] = "PKCS12"

  application_properties["quarkus.http.ssl.certificate.key-store-password"] = "${POLARIS_SSL_KEYSTORE_PASSWORD}"
  if str(application_properties.get("quarkus.http.ssl.certificate.trust-store-file", "")).strip():
    application_properties["quarkus.http.ssl.certificate.trust-store-password"] = "${POLARIS_SSL_TRUSTSTORE_PASSWORD}"
else:
  # TLS is disabled: remove SSL-only keys so Quarkus does not try to load
  # keystore/truststore material.
  for tls_key in (
    "quarkus.http.ssl.certificate.key-store-file",
    "quarkus.http.ssl.certificate.key-store-file-type",
    "quarkus.http.ssl.certificate.key-store-key-alias",
    "quarkus.http.ssl.certificate.key-store-password",
    "quarkus.http.ssl.certificate.trust-store-file",
    "quarkus.http.ssl.certificate.trust-store-file-type",
    "quarkus.http.ssl.certificate.trust-store-password",
  ):
    if tls_key in application_properties:
      del application_properties[tls_key]

  insecure_requests = str(application_properties.get("quarkus.http.insecure-requests", "")).strip().lower()
  if insecure_requests not in ("enabled", "redirect", "disabled"):
    application_properties["quarkus.http.insecure-requests"] = "enabled"

polaris_auth_type = str(application_properties.get("polaris.authentication.type", "internal")).strip().lower()
if polaris_auth_type not in ("internal", "external", "mixed"):
  polaris_auth_type = "internal"

# Persist normalized auth mode into generated application.properties.
application_properties["polaris.authentication.type"] = polaris_auth_type

# OIDC is mapped from cluster-level oidc-env when enabled, with explicit
# override knobs preserved for custom/external IdP topologies.
if polaris_auth_type in ("external", "mixed"):
  application_properties["quarkus.oidc.tenant-enabled"] = "true"
  if not str(application_properties.get("quarkus.oidc.application-type", "")).strip():
    application_properties["quarkus.oidc.application-type"] = "service"

  if polaris_oidc_use_cluster_config and oidc_available:
    application_properties["quarkus.oidc.auth-server-url"] = "{0}/realms/{1}".format(
      oidc_admin_url.rstrip('/'),
      oidc_realm
    )
    application_properties["quarkus.oidc.client-id"] = "{0}-polaris".format(cluster_name)
  else:
    if polaris_oidc_override_auth_server_url:
      application_properties["quarkus.oidc.auth-server-url"] = polaris_oidc_override_auth_server_url
    if polaris_oidc_override_client_id:
      application_properties["quarkus.oidc.client-id"] = polaris_oidc_override_client_id
    if polaris_oidc_override_client_secret:
      application_properties["quarkus.oidc.credentials.secret"] = polaris_oidc_override_client_secret
else:
  application_properties["quarkus.oidc.tenant-enabled"] = "false"

# Build deterministic bootstrap realm list and credentials payload consumed by
# polaris.py metastore bootstrap/admin operations.
bootstrap_realms_source = default("/configurations/polaris-env/polaris_bootstrap_realms", "POLARIS").strip()
if not bootstrap_realms_source:
  bootstrap_realms_source = application_properties.get("polaris.realm-context.realms", "POLARIS").strip()
bootstrap_realms = [r.strip() for r in bootstrap_realms_source.split(',') if r.strip()]
polaris_bootstrap_credentials = ""
if polaris_auth_type in ("internal", "mixed") and bootstrap_realms:
  entries = []
  for realm in bootstrap_realms:
    entries.append("{0},{1},{2}".format(realm, polaris_admin_username, polaris_admin_password))
  polaris_bootstrap_credentials = ";".join(entries)
polaris_bootstrap_credentials_escaped = polaris_bootstrap_credentials.replace("'", "'\"'\"'")

# RDBMS bootstrap inputs for relational-jdbc persistence mode.
polaris_db_flavor = str(default("/configurations/polaris-db-properties/DB_FLAVOR", "POSTGRES")).upper()
polaris_create_db_dbuser = str(default("/configurations/polaris-db-properties/create_db_dbuser", "true")).lower() == "true"
polaris_db_host = str(default("/configurations/polaris-db-properties/db_host", "localhost")).strip() or "localhost"
polaris_db_port = int(default("/configurations/polaris-db-properties/db_port", 5432))
if ":" in polaris_db_host and polaris_db_host.count(":") == 1:
  host_part, port_part = polaris_db_host.rsplit(":", 1)
  if port_part.isdigit():
    polaris_db_host = host_part
    polaris_db_port = int(port_part)
polaris_db_name = str(default("/configurations/polaris-db-properties/db_name", "polaris")).strip() or "polaris"
polaris_db_user = str(default("/configurations/polaris-db-properties/db_user", "polaris")).strip() or "polaris"
polaris_db_password = default("/configurations/polaris-db-properties/db_password", "")
polaris_db_root_user = str(default("/configurations/polaris-db-properties/db_root_user", "postgres")).strip() or "postgres"
polaris_db_root_password = default("/configurations/polaris-db-properties/db_root_password", "")
polaris_privelege_user_jdbc_url = str(default("/configurations/polaris-db-properties/polaris_privelege_user_jdbc_url",
                                              format("jdbc:postgresql://{polaris_db_host}:{polaris_db_port}/postgres"))).strip()

# Keep datasource runtime settings aligned with polaris-db-properties so agent-side
# config generation does not depend on UI recommendation payload completeness.
persistence_type = str(application_properties.get("polaris.persistence.type", "in-memory")).strip().lower()
if persistence_type == "relational-jdbc":
  db_host_port = "{0}:{1}".format(polaris_db_host, polaris_db_port)
  application_properties["quarkus.datasource.db-kind"] = "postgresql"
  application_properties["quarkus.datasource.jdbc.driver"] = "org.postgresql.Driver"
  application_properties["quarkus.datasource.jdbc.url"] = "jdbc:postgresql://{0}/{1}".format(
    db_host_port, polaris_db_name
  )
  application_properties["quarkus.datasource.username"] = polaris_db_user
  if str(polaris_db_password).strip():
    application_properties["quarkus.datasource.password"] = polaris_db_password

# Resolve effective service protocol/port from requested mode plus TLS state.
polaris_protocol_requested = str(default("/configurations/polaris-env/polaris_protocol", "http")).strip().lower()
if polaris_protocol_requested not in ("http", "https"):
  polaris_protocol_requested = "http"

if polaris_ssl_enabled:
  polaris_protocol = "https"
else:
  # Keep runtime coherent: HTTPS protocol requires TLS to be enabled.
  polaris_protocol = "http" if polaris_protocol_requested == "https" else polaris_protocol_requested

polaris_http_port = _to_int(default("/configurations/polaris-application-properties/quarkus.http.port", 8181), 8181)
polaris_https_port = _to_int(default("/configurations/polaris-application-properties/quarkus.http.ssl-port", 8443), 8443)
polaris_port = polaris_https_port if polaris_protocol == "https" else polaris_http_port

polaris_hosts = sorted(default("/clusterHostInfo/polaris_server_hosts", []))
polaris_service_host = polaris_hosts[0] if polaris_hosts else config['agentLevelParams']['hostname']
polaris_hostname = config['agentLevelParams']['hostname']
polaris_service_url = "{0}://{1}:{2}".format(polaris_protocol, polaris_service_host, polaris_port)

# Ozone topology discovery and integration toggles used by Polaris Ozone
# bootstrap logic (secret retrieval, ACL mode, default catalog endpoint).
ozone_manager_hosts = sorted(default("/clusterHostInfo/ozone_manager_hosts", []))
ozone_s3g_hosts = sorted(default("/clusterHostInfo/ozone_s3g_hosts", []))
ozone_site = config.get('configurations', {}).get('ozone-site', {})
has_ozone_service = len(ozone_manager_hosts) > 0 or "ozone.s3g.http-address" in ozone_site or "ozone.s3g.https-address" in ozone_site
ozone_cmd = format("{stack_root}/current/ozone-client/bin/ozone")
ozone_user = str(default("/configurations/ozone-env/ozone_user", polaris_user)).strip() or polaris_user
ozone_user_keytab = str(default("/configurations/ozone-env/ozone_user_keytab", "")).strip()
ozone_principal_name = str(default("/configurations/ozone-env/ozone_principal_name", "")).strip()
if "_HOST" in ozone_principal_name:
  ozone_principal_name = ozone_principal_name.replace("_HOST", polaris_hostname.lower())
kinit_path_local = get_kinit_path(default("/configurations/kerberos-env/executable_search_paths", None))
ozone_om_service_id = str(default("/configurations/ozone-site/ozone.om.internal.service.id", "")).strip()
if not ozone_om_service_id:
  ozone_om_service_ids = str(default("/configurations/ozone-site/ozone.om.service.ids", "")).strip()
  if ozone_om_service_ids:
    ozone_om_service_id = ozone_om_service_ids.split(",")[0].strip()

ozone_security_enabled = str(default("/configurations/ozone-site/ozone.security.enabled", "false")).strip().lower() == "true"
ozone_acl_enabled = str(default("/configurations/ozone-site/ozone.acl.enabled", "false")).strip().lower() == "true"
ozone_acl_authorizer = str(default("/configurations/ozone-site/ozone.acl.authorizer.class", "")).strip()
ozone_ranger_plugin_enabled = str(default("/configurations/ranger-ozone-plugin-properties/ranger-ozone-plugin-enabled", "No")).strip().lower() == "yes"
ozone_acl_managed_by_ranger = "ranger" in ozone_acl_authorizer.lower() or ozone_ranger_plugin_enabled

# Autodetect S3 gateway endpoint when operators leave explicit endpoint empty.
if has_ozone_service and not polaris_ozone_s3_endpoint:
  ozone_s3g_https_address = str(default("/configurations/ozone-site/ozone.s3g.https-address", "")).strip()
  ozone_s3g_http_address = str(default("/configurations/ozone-site/ozone.s3g.http-address", "")).strip()
  ozone_s3g_https_port = str(default("/configurations/ozone-site/ozone.s3g.https-port", "9879")).strip() or "9879"
  ozone_s3g_http_port = str(default("/configurations/ozone-site/ozone.s3g.http-port", "9878")).strip() or "9878"

  endpoint_candidate = ""
  if ozone_s3g_https_address:
    endpoint_candidate = "https://{0}:{1}".format(ozone_s3g_https_address.split(":", 1)[0], ozone_s3g_https_port)
  elif ozone_s3g_http_address:
    endpoint_candidate = "http://{0}:{1}".format(ozone_s3g_http_address.split(":", 1)[0], ozone_s3g_http_port)
  elif ozone_s3g_hosts:
    endpoint_candidate = "http://{0}:{1}".format(ozone_s3g_hosts[0], ozone_s3g_http_port)

  if endpoint_candidate:
    endpoint_host = endpoint_candidate.split("://", 1)[1].split(":", 1)[0]
    if endpoint_host in ("0.0.0.0", "::", "localhost", "") and ozone_s3g_hosts:
      endpoint_protocol = endpoint_candidate.split("://", 1)[0]
      endpoint_port = endpoint_candidate.rsplit(":", 1)[1]
      endpoint_candidate = "{0}://{1}:{2}".format(endpoint_protocol, ozone_s3g_hosts[0], endpoint_port)
    polaris_ozone_s3_endpoint = endpoint_candidate

if has_ozone_service and not polaris_ozone_catalog_base_location:
  polaris_ozone_catalog_base_location = "s3://{0}/polaris/".format(cluster_name.lower())
if has_ozone_service and not polaris_ozone_catalog_allowed_locations_raw:
  polaris_ozone_catalog_allowed_locations_raw = "s3://{0}/".format(cluster_name.lower())

# Normalize allowed locations to a deterministic list/string representation.
polaris_ozone_catalog_allowed_locations_list = [
  location.strip()
  for location in polaris_ozone_catalog_allowed_locations_raw.split(",")
  if location and location.strip()
]
polaris_ozone_catalog_allowed_locations = ",".join(polaris_ozone_catalog_allowed_locations_list)

polaris_ssl_keystore_file = str(application_properties.get("quarkus.http.ssl.certificate.key-store-file", "")).strip()
polaris_ssl_keystore_file_type = str(application_properties.get("quarkus.http.ssl.certificate.key-store-file-type", "PKCS12")).strip() or "PKCS12"
polaris_ssl_keystore_alias = str(application_properties.get("quarkus.http.ssl.certificate.key-store-key-alias", "polaris")).strip() or "polaris"
polaris_ssl_truststore_file = str(application_properties.get("quarkus.http.ssl.certificate.trust-store-file", "")).strip()
polaris_ssl_truststore_file_type = str(application_properties.get("quarkus.http.ssl.certificate.trust-store-file-type", "PKCS12")).strip() or "PKCS12"

# MCP/Console launcher parameters are derived here so start scripts stay simple
# and do not replicate option assembly in multiple component scripts.
polaris_mcp_transport = default("/configurations/polaris-env/polaris_mcp_transport", "http")
polaris_mcp_host = default("/configurations/polaris-env/polaris_mcp_host", "0.0.0.0")
polaris_mcp_port = int(default("/configurations/polaris-env/polaris_mcp_port", 8000))
polaris_mcp_protocol_requested = str(default("/configurations/polaris-env/polaris_mcp_protocol", "http")).strip().lower()
if polaris_mcp_protocol_requested not in ("http", "https"):
  polaris_mcp_protocol_requested = "http"
polaris_mcp_protocol = "https" if polaris_mcp_tls_enabled else (
  "http" if polaris_mcp_protocol_requested == "https" else polaris_mcp_protocol_requested
)
polaris_mcp_pid_file = format("{polaris_pid_dir}/polaris-mcp.pid")

polaris_mcp_base_url = default("/configurations/polaris-env/polaris_mcp_base_url", "").strip()
if not polaris_mcp_base_url:
  polaris_mcp_base_url = "{0}/".format(polaris_service_url.rstrip('/'))

polaris_mcp_start_command = default("/configurations/polaris-env/polaris_mcp_start_command", "").strip()
if not polaris_mcp_start_command:
  polaris_mcp_start_command = format(
    "{polaris_mcp_home}/bin/polaris-mcp --transport {polaris_mcp_transport} "
    "--host {polaris_mcp_host} --port {polaris_mcp_port}"
  )
  if polaris_mcp_tls_enabled:
    tls_parts = ["--tls-enabled"]
    if polaris_mcp_tls_cert_file:
      tls_parts.extend(["--tls-cert-file", polaris_mcp_tls_cert_file])
    if polaris_mcp_tls_key_file:
      tls_parts.extend(["--tls-key-file", polaris_mcp_tls_key_file])
    if polaris_mcp_tls_ca_file:
      tls_parts.extend(["--tls-ca-file", polaris_mcp_tls_ca_file])
    polaris_mcp_start_command = "{0} {1}".format(
      polaris_mcp_start_command,
      " ".join(tls_parts)
    )
polaris_mcp_stop_command = default("/configurations/polaris-env/polaris_mcp_stop_command", "").strip()
if not polaris_mcp_stop_command:
  polaris_mcp_stop_command = None

polaris_mcp_hosts = sorted(default("/clusterHostInfo/polaris_mcp_server_hosts", []))

polaris_console_host = default("/configurations/polaris-env/polaris_console_host", "0.0.0.0")
polaris_console_protocol_requested = str(default("/configurations/polaris-env/polaris_console_protocol", "http")).strip().lower()
if polaris_console_protocol_requested not in ("http", "https"):
  polaris_console_protocol_requested = "http"
polaris_console_protocol = "https" if polaris_console_tls_enabled else (
  "http" if polaris_console_protocol_requested == "https" else polaris_console_protocol_requested
)
polaris_console_port = int(default("/configurations/polaris-env/polaris_console_port", 8282))
polaris_console_pid_file = format("{polaris_pid_dir}/polaris-console.pid")

polaris_console_start_command = default("/configurations/polaris-env/polaris_console_start_command", "").strip()
if not polaris_console_start_command:
  polaris_console_start_command = format(
    "{polaris_console_home}/bin/polaris-console --host {polaris_console_host} --port {polaris_console_port}"
  )
  if polaris_console_tls_enabled:
    tls_parts = ["--tls-enabled"]
    if polaris_console_tls_cert_file:
      tls_parts.extend(["--tls-cert-file", polaris_console_tls_cert_file])
    if polaris_console_tls_key_file:
      tls_parts.extend(["--tls-key-file", polaris_console_tls_key_file])
    polaris_console_start_command = "{0} {1}".format(
      polaris_console_start_command,
      " ".join(tls_parts)
    )
polaris_console_stop_command = default("/configurations/polaris-env/polaris_console_stop_command", "").strip()
if not polaris_console_stop_command:
  polaris_console_stop_command = None

polaris_console_hosts = sorted(default("/clusterHostInfo/polaris_console_hosts", []))

# Polaris Console performs direct browser calls to Polaris REST API, so CORS must
# be enabled server-side for the console origin.
console_origin_host = polaris_console_hosts[0] if polaris_console_hosts else polaris_hostname
if not console_origin_host or console_origin_host in ("0.0.0.0", "::", "localhost"):
  console_origin_host = polaris_hostname
console_origin = "{0}://{1}:{2}".format(
  polaris_console_protocol,
  console_origin_host,
  polaris_console_port
)

if "quarkus.http.cors.enabled" not in application_properties:
  application_properties["quarkus.http.cors.enabled"] = "true"
if not str(application_properties.get("quarkus.http.cors.origins", "")).strip():
  application_properties["quarkus.http.cors.origins"] = console_origin
if not str(application_properties.get("quarkus.http.cors.methods", "")).strip():
  application_properties["quarkus.http.cors.methods"] = "GET,POST,PUT,DELETE,PATCH,OPTIONS"
if not str(application_properties.get("quarkus.http.cors.headers", "")).strip():
  application_properties["quarkus.http.cors.headers"] = "Content-Type,Authorization,Polaris-Realm,X-Request-ID"
if not str(application_properties.get("quarkus.http.cors.exposed-headers", "")).strip():
  application_properties["quarkus.http.cors.exposed-headers"] = "*"
if "quarkus.http.cors.access-control-allow-credentials" not in application_properties:
  application_properties["quarkus.http.cors.access-control-allow-credentials"] = "true"
if not str(application_properties.get("quarkus.http.cors.access-control-max-age", "")).strip():
  application_properties["quarkus.http.cors.access-control-max-age"] = "PT10M"

# Kerberos-specific JVM/JAAS parameters are prepared once here and reused by
# configure/start actions to avoid drift.
polaris_jaas_principal = None
polaris_bare_principal = None
polaris_kerberos_opts = ""
if security_enabled:
  current_host = config['agentLevelParams']['hostname'].lower()
  polaris_jaas_principal = polaris_principal_value.replace('_HOST', current_host)
  polaris_bare_principal = get_bare_principal(polaris_principal_value)
  polaris_kerberos_opts = format(
    "-Djava.security.auth.login.config={polaris_jaas_conf} -Djavax.security.auth.useSubjectCredsOnly=false -Dsolr.httpclient.builder.factory=org.apache.solr.client.solrj.impl.Krb5HttpClientBuilder"
  )

# Ranger plugin integration expands Ambari templates, computes repository
# metadata, and injects plugin-specific runtime keys for Polaris.
ranger_policy_config = {}
stack_supports_ranger_kerberos = check_stack_feature(StackFeature.RANGER_KERBEROS_SUPPORT, version_for_stack_feature_checks)
xml_configurations_supported = check_stack_feature(StackFeature.RANGER_XML_CONFIGURATION, version_for_stack_feature_checks)
ranger_admin_hosts = default("/clusterHostInfo/ranger_admin_hosts", [])
has_ranger_admin = len(ranger_admin_hosts) > 0
enable_ranger_polaris = default("/configurations/ranger-polaris-plugin-properties/ranger-polaris-plugin-enabled", "No")
enable_ranger_polaris = str(enable_ranger_polaris).lower() == "yes"

namenode_hosts = default("/clusterHostInfo/namenode_hosts", [])
has_namenode = not len(namenode_hosts) == 0
hadoop_bin_dir = stack_select.get_hadoop_dir("bin") if has_namenode else None
hadoop_conf_dir = conf_select.get_hadoop_conf_dir() if has_namenode else "/etc/hadoop/conf"

enable_ranger_polaris = default("/configurations/polaris-application-properties/polaris.authorization.type", "internal").strip().lower() == "ranger"
if enable_ranger_polaris:
    # get ranger policy url
    policymgr_mgr_url = config['configurations']['admin-properties']['policymgr_external_url']
    if xml_configurations_supported:
      policymgr_mgr_url = config['configurations']['ranger-polaris-security']['ranger.plugin.polaris.policy.rest.url']

    if not is_empty(policymgr_mgr_url) and policymgr_mgr_url.endswith('/'):
      policymgr_mgr_url = policymgr_mgr_url.rstrip('/')

    # ranger audit db user
    xa_audit_db_user = default('/configurations/admin-properties/audit_db_user', 'rangerlogger')

    # ranger polaris service/repository name
    repo_name = str(config['clusterName']) + '_polaris'
    repo_name_value = config['configurations']['ranger-polaris-security']['ranger.plugin.polaris.service.name']
    if not is_empty(repo_name_value) and repo_name_value != "{{repo_name}}":
      repo_name = repo_name_value

    common_name_for_certificate = config['configurations']['ranger-polaris-plugin-properties']['common.name.for.certificate']
    repo_config_username = config['configurations']['ranger-polaris-plugin-properties']['REPOSITORY_CONFIG_USERNAME']
    ranger_plugin_properties = config['configurations']['ranger-polaris-plugin-properties']
    policy_user = config['configurations']['ranger-polaris-plugin-properties']['policy_user']
    repo_config_password = config['configurations']['ranger-polaris-plugin-properties']['REPOSITORY_CONFIG_PASSWORD']

    # ranger-env config
    ranger_env = config['configurations']['ranger-env']

    # create ranger-env config having external ranger credential properties
    if not has_ranger_admin and enable_ranger_polaris:
      external_admin_username = default('/configurations/ranger-polaris-plugin-properties/external_admin_username', 'admin')
      external_admin_password = default('/configurations/ranger-polaris-plugin-properties/external_admin_password', 'admin')
      external_ranger_admin_username = default('/configurations/ranger-polaris-plugin-properties/external_ranger_admin_username', 'amb_ranger_admin')
      external_ranger_admin_password = default('/configurations/ranger-polaris-plugin-properties/external_ranger_admin_password', 'amb_ranger_admin')
      ranger_env = {}
      ranger_env['admin_username'] = external_admin_username
      ranger_env['admin_password'] = external_admin_password
      ranger_env['ranger_admin_username'] = external_ranger_admin_username
      ranger_env['ranger_admin_password'] = external_ranger_admin_password
      ranger_admin_username = external_admin_username
      ranger_admin_password = external_admin_password

    if has_ranger_admin:
      ranger_admin_username = config['configurations']['ranger-env']['admin_username']
      ranger_admin_password = config['configurations']['ranger-env']['admin_password']
is_ranger_kms_ssl_enabled = default("configurations/ranger-kms-site/ranger.service.https.attrib.ssl.enabled", False)

# Defaults for Ranger setup arguments that are optional for Polaris
previous_jdbc_jar = None
downloaded_custom_connector = None
driver_curl_source = None
driver_curl_target = None

if enable_ranger_polaris:
  repo_name = "{0}_polaris".format(config['clusterName'])
  repo_name_value = config['configurations']['ranger-polaris-security']['ranger.plugin.polaris.service.name']
  if not is_empty(repo_name_value) and repo_name_value != "{{repo_name}}":
    repo_name = repo_name_value

  ranger_policy_config = {
    "isEnabled": "true",
    "service": repo_name,
    "name": "[AMBARI] - Polaris Admin Access",
    "resources": {
      "root": {
        "values": ["*"],
        "isExcludes": "false",
        "isRecursive": "false"
      }
    },
    "policyItems": [{
      "accesses": [
        {"type": "catalog_create", "isAllowed": "true"},
        {"type": "catalog_list", "isAllowed": "true"},
        {"type": "principal_create", "isAllowed": "true"},
        {"type": "principal_list", "isAllowed": "true"},
        {"type": "principal_role_create", "isAllowed": "true"},
        {"type": "principal_role_list", "isAllowed": "true"},
        {"type": "service_manage_access", "isAllowed": "true"},
      ],
      "users": [polaris_admin_username],
      "groups": [],
      "roles": [],
      "conditions": [],
      "delegateAdmin": "false"
    }]
  }

  ssl_keystore_password = config['configurations']['ranger-polaris-policymgr-ssl']['xasecure.policymgr.clientssl.keystore.password']
  ssl_truststore_password = config['configurations']['ranger-polaris-policymgr-ssl']['xasecure.policymgr.clientssl.truststore.password']
  credential_file = format('/etc/ranger/{repo_name}/cred.jceks')
  xa_audit_hdfs_is_enabled = default('/configurations/ranger-polaris-audit/xasecure.audit.destination.hdfs', False)

  policymgr_mgr_url = config['configurations']['ranger-polaris-security']['ranger.plugin.polaris.policy.rest.url']
  if is_empty(policymgr_mgr_url) or policymgr_mgr_url == "{{policymgr_mgr_url}}":
    policymgr_mgr_url = default('/configurations/admin-properties/policymgr_external_url', None)
    if is_empty(policymgr_mgr_url):
      policymgr_mgr_url = default('/configurations/ranger-admin-site/ranger.service.http.url', None)
  if not is_empty(policymgr_mgr_url) and policymgr_mgr_url.endswith('/'):
    policymgr_mgr_url = policymgr_mgr_url.rstrip('/')

  ranger_env = config['configurations']['ranger-env']
  if not has_ranger_admin:
    external_admin_username = default('/configurations/ranger-polaris-plugin-properties/external_admin_username', 'admin')
    external_admin_password = default('/configurations/ranger-polaris-plugin-properties/external_admin_password', 'admin')
    external_ranger_admin_username = default('/configurations/ranger-polaris-plugin-properties/external_ranger_admin_username', 'amb_ranger_admin')
    external_ranger_admin_password = default('/configurations/ranger-polaris-plugin-properties/external_ranger_admin_password', 'amb_ranger_admin')
    ranger_env = {
      'admin_username': external_admin_username,
      'admin_password': external_admin_password,
      'ranger_admin_username': external_ranger_admin_username,
      'ranger_admin_password': external_ranger_admin_password,
    }
    ranger_admin_username = external_ranger_admin_username
    ranger_admin_password = external_admin_password
  else:
    ranger_admin_username = config['configurations']['ranger-env']['admin_username']
    ranger_admin_password = config['configurations']['ranger-env']['admin_password']

  ranger_plugin_properties = config['configurations']['ranger-polaris-plugin-properties']
  ranger_polaris_audit = dict(config['configurations']['ranger-polaris-audit'])
  ranger_polaris_audit_attrs = config['configurationAttributes']['ranger-polaris-audit']
  ranger_polaris_security = config['configurations']['ranger-polaris-security']
  ranger_polaris_security_attrs = config['configurationAttributes']['ranger-polaris-security']
  ranger_polaris_policymgr_ssl = config['configurations']['ranger-polaris-policymgr-ssl']
  ranger_polaris_policymgr_ssl_attrs = config['configurationAttributes']['ranger-polaris-policymgr-ssl']

  policy_user = ranger_plugin_properties['policy_user']
  polaris_service_url_override = default('/configurations/ranger-polaris-plugin-properties/polaris.service.url', '').strip()
  polaris_repository_url = polaris_service_url_override if polaris_service_url_override else polaris_service_url
  # Ranger service-config expects a single realm value for Polaris-Realm header.
  # Resolve it from Polaris realm-context config (first realm if multiple are configured).
  polaris_repository_realm = str(
    application_properties.get('polaris.realm-context.default-realm', '')
  ).strip()
  if is_empty(polaris_repository_realm):
    realms_source = str(application_properties.get('polaris.realm-context.realms', 'POLARIS')).strip()
    if not is_empty(realms_source):
      polaris_repository_realm = realms_source.split(',')[0].strip()
  if is_empty(polaris_repository_realm):
    polaris_repository_realm = 'POLARIS'

  polaris_repository_configuration = {
    'username': ranger_plugin_properties['REPOSITORY_CONFIG_USERNAME'],
    'password': ranger_plugin_properties['REPOSITORY_CONFIG_PASSWORD'],
    # Ranger Polaris service definition requires this field name.
    'polaris.base.url': polaris_repository_url,
    'polaris.realm': polaris_repository_realm,
    # Keep legacy alias for compatibility with older service-def variants.
    'polaris.rest.address': polaris_repository_url,
    'commonNameForCertificate': ranger_plugin_properties['common.name.for.certificate'],
    'ambari.service.check.user': policy_user,
  }

  custom_ranger_service_config = generate_ranger_service_config(ranger_plugin_properties)
  if len(custom_ranger_service_config) > 0:
    polaris_repository_configuration.update(custom_ranger_service_config)

  if security_enabled:
    polaris_repository_configuration['policy.download.auth.users'] = polaris_user
    polaris_repository_configuration['tag.download.auth.users'] = polaris_user

  polaris_ranger_plugin_repo = {
    'isEnabled': 'true',
    'configs': polaris_repository_configuration,
    'description': 'polaris repo',
    'name': repo_name,
    'type': 'polaris',
  }

  # Keep Ranger runtime keys available when the plugin is enabled, without
  # forcing the selected Polaris authorization backend.
  ranger_template_values = {
    "repo_name": repo_name,
    "policymgr_mgr_url": policymgr_mgr_url or "",
    "credential_file": credential_file,
    "cluster_name": cluster_name,
  }
  ranger_service_name = _resolve_ambari_template_value(
    ranger_polaris_security.get("ranger.plugin.polaris.service.name", ""),
    ranger_template_values,
  )
  if not ranger_service_name or "{{" in ranger_service_name or "}}" in ranger_service_name:
    ranger_service_name = repo_name
  application_properties["polaris.authorization.ranger.service-name"] = ranger_service_name
  if not str(application_properties.get("polaris.authorization.ranger.app-id", "")).strip():
    application_properties["polaris.authorization.ranger.app-id"] = "polaris"

  # When Kerberos is enabled, configure the Ranger plugin to log in via the Polaris keytab so
  # that the Ranger Admin REST client uses SPNEGO/Kerberos rather than SIMPLE authentication.
  if security_enabled and polaris_jaas_principal and polaris_keytab_path:
    application_properties["polaris.authorization.ranger.kerberos.principal"] = polaris_jaas_principal
    application_properties["polaris.authorization.ranger.kerberos.keytab"] = polaris_keytab_path

  ranger_config_files = [
    format("{polaris_conf_dir}/ranger-polaris-security.xml"),
    format("{polaris_conf_dir}/ranger-polaris-audit.xml"),
    format("{polaris_conf_dir}/ranger-policymgr-ssl.xml"),
  ]
  for idx, cfg_file in enumerate(ranger_config_files):
    application_properties["polaris.authorization.ranger.config-files[{0}]".format(idx)] = cfg_file

  policy_source_impl = _resolve_ambari_template_value(
    ranger_polaris_security.get("ranger.plugin.polaris.policy.source.impl", ""),
    ranger_template_values,
  )
  if policy_source_impl and "{{" not in policy_source_impl and "}}" not in policy_source_impl:
    application_properties["polaris.authorization.ranger.policy-source-impl"] = policy_source_impl

  ranger_plugin_sources = [ranger_polaris_security, ranger_polaris_audit]
  for ranger_source in ranger_plugin_sources:
    for ranger_key, ranger_value in ranger_source.items():
      if not ranger_key.startswith("ranger.plugin.polaris."):
        continue
      plugin_suffix = ranger_key[len("ranger.plugin.polaris."):]
      if not plugin_suffix:
        continue
      plugin_value = _resolve_ambari_template_value(ranger_value, ranger_template_values)
      if not plugin_value or "{{" in plugin_value or "}}" in plugin_value:
        continue
      application_properties["polaris.authorization.ranger.plugin.{0}".format(plugin_suffix)] = plugin_value

  if has_namenode:
    hdfs_user = config['configurations']['hadoop-env']['hdfs_user']
    hdfs_user_keytab = config['configurations']['hadoop-env']['hdfs_user_keytab']
    hdfs_principal_name = config['configurations']['hadoop-env']['hdfs_principal_name']
    hdfs_site = config['configurations']['hdfs-site']
    default_fs = config['configurations']['core-site']['fs.defaultFS']
    dfs_type = default("/clusterLevelParams/dfs_type", "")

    import functools
    from resource_management.libraries.functions.get_not_managed_resources import get_not_managed_resources
    from resource_management.libraries.resources.hdfs_resource import HdfsResource

    HdfsResource = functools.partial(
      HdfsResource,
      user=hdfs_user,
      hdfs_resource_ignore_file="/var/lib/ambari-agent/data/.hdfs_resource_ignore",
      security_enabled=security_enabled,
      keytab=hdfs_user_keytab,
      kinit_path_local=kinit_path_local,
      hadoop_bin_dir=hadoop_bin_dir,
      hadoop_conf_dir=hadoop_conf_dir,
      principal_name=hdfs_principal_name,
      hdfs_site=hdfs_site,
      default_fs=default_fs,
      immutable_paths=get_not_managed_resources(),
      dfs_type=dfs_type,
    )
smoke_test_user = config['configurations']['cluster-env']['smokeuser']
