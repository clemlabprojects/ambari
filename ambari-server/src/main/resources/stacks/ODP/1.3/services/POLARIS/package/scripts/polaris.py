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
import json
import os
import re
import base64
import shlex
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

from resource_management.core.logger import Logger
from resource_management.core.exceptions import ExecutionFailed, Fail
from resource_management.core.resources.system import Directory, Execute, File
from resource_management.core.source import DownloadSource, InlineTemplate
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.get_user_call_output import get_user_call_output
from resource_management.libraries.resources.properties_file import PropertiesFile


def configure_polaris(component_type='server'):
  import params

  Directory([params.polaris_conf_dir, params.polaris_log_dir, params.polaris_pid_dir, params.polaris_data_dir],
            mode=0o755,
            cd_access='a',
            owner=params.polaris_user,
            group=params.user_group,
            create_parents=True
            )

  File(format("{polaris_conf_dir}/polaris-env.sh"),
       content=InlineTemplate(params.polaris_env_content),
       owner=params.polaris_user,
       group=params.user_group,
       mode=0o640
       )

  File(format("{polaris_conf_dir}/logging.properties"),
       content=params.polaris_logging_properties_content,
       owner=params.polaris_user,
       group=params.user_group,
       mode=0o640
       )

  if params.security_enabled:
    _configure_kerberos()

  PropertiesFile(format("{polaris_conf_dir}/{polaris_conf_file}"),
                 properties=params.application_properties,
                 owner=params.polaris_user,
                 group=params.user_group,
                 mode=0o640
                 )

  configure_console()

  setup_tools_tls()

  if component_type == 'server':
    setup_server_tls()
    setup_database()
    run_admin_bootstrap()
    setup_token_broker()


def setup_server_tls():
  import params

  if not params.polaris_ssl_enabled:
    return

  if not params.polaris_ssl_keystore_file:
    raise Fail("TLS is enabled but quarkus.http.ssl.certificate.key-store-file is not configured.")

  _ensure_parent_dir(params.polaris_ssl_keystore_file)

  keytool_bin = os.path.join(params.java64_home, "bin", "keytool")
  if not os.path.exists(keytool_bin):
    keytool_bin = "keytool"

  keystore_password = str(params.polaris_ssl_keystore_password or "changeit")
  truststore_password = str(params.polaris_ssl_truststore_password or keystore_password)
  key_alias = str(params.polaris_ssl_keystore_alias or "polaris")
  keystore_type = str(params.polaris_ssl_keystore_file_type or "PKCS12")
  hostname = str(getattr(params, "polaris_hostname", "") or "localhost")

  if params.polaris_ssl_auto_generate:
    dname = "CN={0}, OU=ODP, O=ODP, L=Unknown, ST=Unknown, C=US".format(hostname)
    san = "SAN=dns:{0},dns:localhost,ip:127.0.0.1".format(hostname)
    gen_cmd = " ".join([
      shlex.quote(keytool_bin),
      "-genkeypair",
      "-alias", shlex.quote(key_alias),
      "-keyalg", "RSA",
      "-keysize", "2048",
      "-validity", "3650",
      "-storetype", shlex.quote(keystore_type),
      "-keystore", shlex.quote(params.polaris_ssl_keystore_file),
      "-storepass", shlex.quote(keystore_password),
      "-keypass", shlex.quote(keystore_password),
      "-dname", shlex.quote(dname),
      "-ext", shlex.quote(san),
    ])
    Execute(gen_cmd,
            user=params.polaris_user,
            not_if="test -f {0}".format(shlex.quote(params.polaris_ssl_keystore_file)))
  else:
    Execute("test -f {0}".format(shlex.quote(params.polaris_ssl_keystore_file)),
            user=params.polaris_user)

  File(params.polaris_ssl_keystore_file,
       owner=params.polaris_user,
       group=params.user_group,
       mode=0o640,
       only_if="test -f {0}".format(shlex.quote(params.polaris_ssl_keystore_file)))

  if not params.polaris_ssl_truststore_file:
    return

  _ensure_parent_dir(params.polaris_ssl_truststore_file)
  cert_export_file = format("{polaris_pid_dir}/polaris-server-cert.pem")

  if params.polaris_ssl_auto_generate:
    export_cmd = " ".join([
      shlex.quote(keytool_bin),
      "-exportcert",
      "-rfc",
      "-alias", shlex.quote(key_alias),
      "-keystore", shlex.quote(params.polaris_ssl_keystore_file),
      "-storepass", shlex.quote(keystore_password),
      "-file", shlex.quote(cert_export_file),
    ])
    import_cmd = " ".join([
      shlex.quote(keytool_bin),
      "-importcert",
      "-noprompt",
      "-alias", shlex.quote(key_alias),
      "-file", shlex.quote(cert_export_file),
      "-keystore", shlex.quote(params.polaris_ssl_truststore_file),
      "-storetype", shlex.quote(params.polaris_ssl_truststore_file_type),
      "-storepass", shlex.quote(truststore_password),
    ])

    Execute(export_cmd,
            user=params.polaris_user,
            not_if="test -f {0}".format(shlex.quote(params.polaris_ssl_truststore_file)))
    Execute(import_cmd,
            user=params.polaris_user,
            not_if="test -f {0}".format(shlex.quote(params.polaris_ssl_truststore_file)))
    File(cert_export_file, action="delete")
  else:
    Execute("test -f {0}".format(shlex.quote(params.polaris_ssl_truststore_file)),
            user=params.polaris_user)

  File(params.polaris_ssl_truststore_file,
       owner=params.polaris_user,
       group=params.user_group,
       mode=0o640,
       only_if="test -f {0}".format(shlex.quote(params.polaris_ssl_truststore_file)))


def setup_tools_tls():
  import params

  _setup_pem_tls(
    enabled=params.polaris_mcp_tls_enabled,
    auto_generate=params.polaris_mcp_tls_auto_generate,
    cert_file=params.polaris_mcp_tls_cert_file,
    key_file=params.polaris_mcp_tls_key_file,
    label="Polaris MCP"
  )
  _setup_pem_tls(
    enabled=params.polaris_console_tls_enabled,
    auto_generate=params.polaris_console_tls_auto_generate,
    cert_file=params.polaris_console_tls_cert_file,
    key_file=params.polaris_console_tls_key_file,
    label="Polaris Console"
  )


def configure_console():
  import params

  console_dist_dir = os.path.join(params.polaris_console_home, "console", "dist")
  if not os.path.isdir(console_dist_dir):
    Logger.info(
      "Skipping Polaris Console runtime config generation; dist directory not found: {0}".format(
        console_dist_dir
      )
    )
    return

  realms_source = str(params.application_properties.get("polaris.realm-context.realms", "")).strip()
  realms = [realm.strip() for realm in realms_source.split(",") if realm.strip()]
  default_realm = realms[0] if realms else "POLARIS"

  api_url = str(getattr(params, "polaris_service_url", "") or "").strip().rstrip("/")
  if not api_url:
    protocol = str(getattr(params, "polaris_protocol", "http")).strip().lower()
    host = str(getattr(params, "polaris_service_host", "localhost")).strip() or "localhost"
    port = str(getattr(params, "polaris_port", "8181")).strip()
    api_url = "{0}://{1}:{2}".format(protocol, host, port)

  access_control_mode = str(
    params.application_properties.get("polaris.authorization.type", "internal")
  ).strip().lower()
  if not access_control_mode:
    access_control_mode = "internal"

  app_config = {
    "VITE_POLARIS_API_URL": api_url,
    "VITE_POLARIS_REALM": default_realm,
    "VITE_POLARIS_PRINCIPAL_SCOPE": "PRINCIPAL_ROLE:ALL",
    "VITE_OAUTH_TOKEN_URL": "{0}/api/catalog/v1/oauth/tokens".format(api_url),
    "VITE_POLARIS_REALM_HEADER_NAME": "Polaris-Realm",
    "VITE_POLARIS_ACCESS_CONTROL_MODE": access_control_mode,
  }
  if access_control_mode == "ranger":
    app_config["VITE_POLARIS_ACCESS_CONTROL_NOTICE"] = (
      "Authorization backend is Ranger. Manage authorization policies in Apache Ranger; "
      "Polaris Access Control is read-only in this mode."
    )

  config_js_path = os.path.join(console_dist_dir, "config.js")
  File(
    config_js_path,
    content="// Runtime configuration generated by Ambari.\nwindow.APP_CONFIG = {0};\n".format(
      json.dumps(app_config, sort_keys=True)
    ),
    owner=params.polaris_user,
    group=params.user_group,
    mode=0o640
  )


def setup_token_broker():
  import params

  if not _needs_token_broker(params.application_properties):
    return

  broker_type = _normalize(params.application_properties.get(
    "polaris.authentication.token-broker.type", "rsa-key-pair"))

  if broker_type == "rsa-key-pair":
    private_key = params.application_properties.get(
      "polaris.authentication.token-broker.rsa-key-pair.private-key-file")
    public_key = params.application_properties.get(
      "polaris.authentication.token-broker.rsa-key-pair.public-key-file")

    if not private_key or not public_key:
      Logger.info("Skipping RSA token broker key generation; missing key file paths.")
      return

    _ensure_parent_dir(private_key)
    _ensure_parent_dir(public_key)

    Execute(format("openssl genpkey -algorithm RSA -out {private_key} -pkeyopt rsa_keygen_bits:2048"),
            user=params.polaris_user,
            not_if=format("test -f {private_key}")
            )

    Execute(format("openssl rsa -in {private_key} -pubout -out {public_key}"),
            user=params.polaris_user,
            not_if=format("test -f {public_key}")
            )

    File(private_key, owner=params.polaris_user, group=params.user_group, mode=0o600,
         only_if=format("test -f {private_key}"))
    File(public_key, owner=params.polaris_user, group=params.user_group, mode=0o644,
         only_if=format("test -f {public_key}"))
    return

  if broker_type == "symmetric-key":
    secret = params.application_properties.get(
      "polaris.authentication.token-broker.symmetric-key.secret")
    if secret:
      Logger.info("Symmetric token broker secret is set; skipping file generation.")
      return

    key_file = params.application_properties.get(
      "polaris.authentication.token-broker.symmetric-key.file")
    if not key_file:
      Logger.info("Skipping symmetric token broker key generation; missing key file path.")
      return

    _ensure_parent_dir(key_file)

    Execute(format("openssl rand -base64 32 > {key_file}"),
            user=params.polaris_user,
            not_if=format("test -f {key_file}")
            )

    File(key_file, owner=params.polaris_user, group=params.user_group, mode=0o600,
         only_if=format("test -f {key_file}"))
    return

  Logger.info("Unknown token broker type '%s'; skipping token broker setup." % broker_type)


def setup_database():
  import params

  persistence_type = _normalize(params.application_properties.get("polaris.persistence.type", "in-memory"))
  if persistence_type != "relational-jdbc":
    return

  if not params.polaris_create_db_dbuser:
    Logger.info("create_db_dbuser is disabled; assuming Polaris DB and DB user already exist.")
    return

  if params.polaris_db_flavor != "POSTGRES":
    raise Fail("Automatic DB bootstrap only supports POSTGRES for Polaris in this stack.")

  if not str(params.polaris_db_root_password).strip():
    raise Fail("db_root_password is required when create_db_dbuser=true for Polaris.")

  quoted_db = _sql_identifier(params.polaris_db_name)
  quoted_user = _sql_identifier(params.polaris_db_user)
  user_password = _sql_literal(params.polaris_db_password or "")
  user_name_literal = _sql_literal(params.polaris_db_user)
  db_name_literal = _sql_literal(params.polaris_db_name)

  root_jdbc_url = str(params.polaris_privelege_user_jdbc_url).strip()
  if not root_jdbc_url:
    root_jdbc_url = "jdbc:postgresql://{0}:{1}/postgres".format(params.polaris_db_host, params.polaris_db_port)

  polaris_db_jdbc_url = "jdbc:postgresql://{0}:{1}/{2}".format(
    params.polaris_db_host, params.polaris_db_port, params.polaris_db_name
  )

  # Idempotent PostgreSQL bootstrap through JDBC: create/alter user, create DB if missing,
  # enforce owner/privileges, and grant schema permissions.
  statements = [
    (
      "DO $$ BEGIN "
      "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = {user_name_literal}) THEN "
      "CREATE ROLE {quoted_user} LOGIN PASSWORD {user_password}; "
      "ELSE "
      "ALTER ROLE {quoted_user} LOGIN PASSWORD {user_password}; "
      "END IF; "
      "END $$"
    ).format(
      quoted_user=quoted_user,
      user_password=user_password,
      user_name_literal=user_name_literal
    ),
    (
      "DO $$ BEGIN "
      "IF NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = {db_name_literal}) THEN "
      "CREATE DATABASE {quoted_db} OWNER {quoted_user}; "
      "END IF; "
      "END $$"
    ).format(
      quoted_db=quoted_db,
      quoted_user=quoted_user,
      db_name_literal=db_name_literal
    ),
    "ALTER DATABASE {quoted_db} OWNER TO {quoted_user}".format(
      quoted_db=quoted_db,
      quoted_user=quoted_user
    ),
    "GRANT ALL PRIVILEGES ON DATABASE {quoted_db} TO {quoted_user}".format(
      quoted_db=quoted_db,
      quoted_user=quoted_user
    ),
  ]

  for idx, statement in enumerate(statements):
    sql_file = "{0}/polaris-db-bootstrap-{1}.sql".format(params.polaris_pid_dir, idx)
    File(sql_file,
         content=statement,
         owner=params.polaris_user,
         group=params.user_group,
         mode=0o600
         )
    _run_postgres_sql(sql_file, root_jdbc_url)

  schema_grant_file = format("{polaris_pid_dir}/polaris-db-bootstrap-schema-grant.sql")
  File(schema_grant_file,
       content="GRANT USAGE, CREATE ON SCHEMA public TO {0}".format(quoted_user),
       owner=params.polaris_user,
       group=params.user_group,
       mode=0o600
       )
  _run_postgres_sql(schema_grant_file, polaris_db_jdbc_url)


def run_admin_bootstrap():
  import params

  persistence_type = _normalize(params.application_properties.get("polaris.persistence.type", "in-memory"))
  if persistence_type != "relational-jdbc":
    return

  realms_source = str(getattr(params, "polaris_bootstrap_realms_raw", "")).strip()
  if not realms_source:
    realms_source = str(params.application_properties.get("polaris.realm-context.realms", "POLARIS")).strip()
  realms = [realm.strip() for realm in realms_source.split(",") if realm.strip()]
  if not realms:
    Logger.info("Skipping Polaris admin bootstrap; no realms configured.")
    return

  bootstrap_user = str(getattr(params, "polaris_admin_username", "")).strip()
  bootstrap_password = str(getattr(params, "polaris_admin_password", ""))
  if not bootstrap_user or not bootstrap_password.strip():
    raise Fail("polaris_admin_username and polaris_admin_password are required for Polaris bootstrap.")
  if "," in bootstrap_user or "," in bootstrap_password:
    raise Fail("polaris_admin_username and polaris_admin_password must not contain commas for admin bootstrap.")

  bootstrap_cmd_parts = [format("{polaris_home}/bin/admin"), "bootstrap"]
  for realm in realms:
    bootstrap_cmd_parts.extend(["-r", realm])
    bootstrap_cmd_parts.extend(["-c", "{0},{1},{2}".format(realm, bootstrap_user, bootstrap_password)])
  bootstrap_cmd = " ".join(shlex.quote(part) for part in bootstrap_cmd_parts)

  admin_cfg = "{0}/admin.properties".format(params.polaris_conf_dir)
  quarkus_locations = admin_cfg
  bootstrap_env = {
    "QUARKUS_CONFIG_LOCATIONS": quarkus_locations,
    "SMALLRYE_CONFIG_LOCATIONS": quarkus_locations,
  }
  jdbc_url = str(params.application_properties.get("quarkus.datasource.jdbc.url", "")).strip()
  jdbc_user = str(params.application_properties.get("quarkus.datasource.username", "")).strip()
  jdbc_password = str(params.application_properties.get("quarkus.datasource.password", "")).strip()
  if not jdbc_password:
    jdbc_password = str(getattr(params, "polaris_db_password", "")).strip()
  if jdbc_url:
    bootstrap_env["QUARKUS_DATASOURCE_JDBC_URL"] = jdbc_url
  if jdbc_user:
    bootstrap_env["QUARKUS_DATASOURCE_USERNAME"] = jdbc_user
  if jdbc_password:
    bootstrap_env["QUARKUS_DATASOURCE_PASSWORD"] = jdbc_password
  persistence_type = str(params.application_properties.get("polaris.persistence.type", "")).strip()
  if persistence_type:
    bootstrap_env["POLARIS_PERSISTENCE_TYPE"] = persistence_type

  # Explicitly override JAVA_HOME after sourcing polaris-env.sh so that a
  # stale on-disk polaris-env.sh (rendered with the old java64_home/Java 8)
  # cannot clobber the Java 17+ home needed by Quarkus.
  java_home_override = "export JAVA_HOME={0}".format(shlex.quote(params.polaris_java_home))
  Logger.info("Running Polaris admin bootstrap for realms: {0}".format(", ".join(realms)))
  try:
    Execute(
      "source {0}/polaris-env.sh; {1}; {2}".format(
        params.polaris_conf_dir, java_home_override, bootstrap_cmd),
      user=params.polaris_user,
      environment=bootstrap_env
    )
  except ExecutionFailed as exc:
    if _is_already_bootstrapped(exc):
      Logger.info("Polaris metastore is already bootstrapped; skipping.")
      return
    raise


# ---------------------------------------------------------------------------
# Ozone catalog bootstrap
# ---------------------------------------------------------------------------

def bootstrap_ozone_catalog():
  import params

  if not getattr(params, "has_ozone_service", False):
    Logger.warning("Skipping Ozone catalog bootstrap; OZONE service not detected on this cluster.")
    return

  catalog_name = str(getattr(params, "polaris_ozone_catalog_name", "")).strip()
  base_location = str(getattr(params, "polaris_ozone_catalog_base_location", "")).strip()
  allowed_locations = list(getattr(params, "polaris_ozone_catalog_allowed_locations_list", []) or [])
  s3_endpoint = str(getattr(params, "polaris_ozone_s3_endpoint", "")).strip()
  s3_region = str(getattr(params, "polaris_ozone_s3_region", "us-east-1")).strip() or "us-east-1"
  path_style_access = bool(getattr(params, "polaris_ozone_s3_path_style_access", True))

  if not catalog_name:
    Logger.warning("Skipping Ozone catalog bootstrap; catalog name not configured.")
    return
  if not base_location:
    Logger.warning("Skipping Ozone catalog bootstrap; base location not configured.")
    return
  if not allowed_locations:
    Logger.warning("Skipping Ozone catalog bootstrap; allowed locations not configured.")
    return
  if not s3_endpoint:
    Logger.warning("Skipping Ozone catalog bootstrap; Ozone S3 endpoint not configured.")
    return

  ozone_security = getattr(params, "ozone_security_enabled", False)

  try:
    Logger.info("Step 1/3: Resolving Ozone S3 credentials.")
    access_id, secret = _resolve_ozone_s3_credentials(ozone_security)
    if not access_id or not secret:
      Logger.warning("Skipping Ozone catalog bootstrap; could not resolve Ozone S3 credentials.")
      return

    volume_name, bucket_name = _parse_volume_and_bucket(base_location, allowed_locations)

    Logger.info("Step 2/3: Creating Ozone volume '{0}' and bucket '{1}'.".format(volume_name, bucket_name))
    _create_ozone_storage(volume_name, bucket_name, ozone_security)

    Logger.info("Step 3/3: Applying Ozone access controls for '{0}'.".format(access_id))
    _grant_ozone_access(access_id, volume_name, bucket_name, ozone_security)

    Logger.info("Creating Polaris catalog '{0}' backed by Ozone S3 gateway.".format(catalog_name))
    _create_catalog(
      catalog_name=catalog_name,
      base_location=base_location,
      allowed_locations=allowed_locations,
      s3_endpoint=s3_endpoint,
      s3_region=s3_region,
      path_style_access=path_style_access,
      access_key_id=access_id,
      secret_key=secret,
    )

    Logger.info("Polaris Ozone catalog bootstrap completed (catalog={0}).".format(catalog_name))

  except Exception as err:
    Logger.warning("Polaris Ozone catalog bootstrap failed: {0}".format(err))


def _resolve_ozone_s3_credentials(require_kerberos):
  """
  Returns (access_id, secret) for connecting Polaris to the Ozone S3 gateway.

  Flow:
    1. Run 'ozone s3 getsecret' as the polaris user (with kinit if kerberos).
    2. If secret is missing, fall back to admin 'tenant get-secret'.
    3. If still missing, use polaris_ozone_principal_secret from config and
       enforce it via admin setsecret.
  """
  import params

  configured_secret = str(getattr(params, "polaris_ozone_principal_secret", "")).strip()
  retries = 6
  retry_sleep = 5

  Logger.info(
    "Resolving Ozone S3 credentials via getsecret (kerberos={0}, user={1}).".format(
      require_kerberos,
      str(getattr(params, "polaris_user", "polaris"))
    )
  )

  access_id = ""
  secret = ""
  for attempt in range(1, retries + 1):
    access_id, secret = _ozone_getsecret(require_kerberos=require_kerberos)
    if access_id and secret:
      break
    if attempt < retries:
      Logger.warning(
        "getsecret returned incomplete credentials (attempt {0}/{1}); retrying in {2}s.".format(
          attempt, retries, retry_sleep
        )
      )
      time.sleep(retry_sleep)

  if not access_id:
    Logger.warning(
      "Unable to resolve Ozone access id via 'ozone s3 getsecret' as user '{0}'.".format(
        str(getattr(params, "polaris_user", "polaris"))
      )
    )
    return "", ""

  if not secret:
    Logger.warning(
      "getsecret returned access id '{0}' without secret; trying admin fallback.".format(access_id)
    )
    secret = _ozone_getsecret_admin(access_id, require_kerberos=require_kerberos)

  final_secret = str(secret or "").strip()
  if not final_secret:
    final_secret = configured_secret
    if final_secret:
      Logger.warning(
        "Ozone secret unavailable from getsecret; trying admin setsecret with configured secret."
      )
      reset_secret = _ozone_setsecret_admin(
        access_id=access_id,
        secret=final_secret,
        require_kerberos=require_kerberos
      )
      if reset_secret:
        final_secret = reset_secret

  if not final_secret:
    Logger.warning(
      "Unable to resolve Ozone secret for access id '{0}'.".format(access_id)
    )
    return "", ""

  return access_id, final_secret


def _ozone_getsecret(require_kerberos):
  output = _run_ozone(
    args=["s3", "getsecret", "-e"],
    run_as="polaris",
    require_kerberos=require_kerberos,
    checked=False
  )
  if not output:
    return "", ""
  access_id, secret = _parse_s3_credentials(output)
  if access_id and secret:
    Logger.info("Resolved Ozone S3 credentials for access id '{0}'.".format(access_id))
  elif access_id:
    Logger.info("Resolved access id '{0}' but no secret from getsecret output.".format(access_id))
  return access_id, secret


def _ozone_getsecret_admin(access_id, require_kerberos):
  if not access_id:
    return ""
  output = _run_ozone(
    args=["tenant", "user", "get-secret", access_id],
    run_as="admin",
    require_kerberos=require_kerberos,
    checked=False
  )
  _, secret = _parse_s3_credentials(output)
  if secret:
    Logger.info("Resolved Ozone secret for '{0}' via admin get-secret.".format(access_id))
  else:
    Logger.warning("Admin get-secret did not return a secret for '{0}'.".format(access_id))
  return secret


def _ozone_setsecret_admin(access_id, secret, require_kerberos):
  if not access_id or not secret:
    return ""

  retries = 3
  retry_sleep = 3
  for attempt in range(1, retries + 1):
    try:
      output = _run_ozone(
        args=["s3", "setsecret", "-u", access_id, "-s", secret, "-e"],
        run_as="admin",
        require_kerberos=require_kerberos,
        checked=True
      )
      _, parsed_secret = _parse_s3_credentials(output)
      if parsed_secret:
        Logger.info("Admin setsecret succeeded for access id '{0}'.".format(access_id))
        return parsed_secret
      Logger.info(
        "Admin setsecret succeeded for '{0}' but no secret printed; using configured secret.".format(
          access_id
        )
      )
      return secret
    except Exception as err:
      if attempt < retries:
        Logger.warning(
          "Admin setsecret failed for '{0}' (attempt {1}/{2}): {3}. Retrying in {4}s.".format(
            access_id, attempt, retries, err, retry_sleep
          )
        )
        time.sleep(retry_sleep)
      else:
        Logger.warning(
          "Admin setsecret failed for '{0}' after {1} attempts: {2}".format(
            access_id, retries, err
          )
        )
  return ""


def _parse_volume_and_bucket(base_location, allowed_locations):
  """
  Parses the Ozone volume and bucket names from S3 (s3://) or native Ozone (o3://) URLs.
  S3 gateway buckets live under the default 's3v' volume.
  """
  default_volume = "s3v"
  candidates = [str(base_location or "").strip()] + [str(x or "").strip() for x in (allowed_locations or [])]

  for location in candidates:
    if not location:
      continue
    if location.startswith("s3://"):
      parsed = urllib.parse.urlparse(location)
      bucket_name = parsed.netloc.strip()
      if not bucket_name:
        parts = [p for p in parsed.path.split("/") if p]
        if parts:
          bucket_name = parts[0].strip()
      if bucket_name:
        return default_volume, bucket_name
    if location.startswith("o3://"):
      parsed = urllib.parse.urlparse(location)
      parts = [p for p in parsed.path.split("/") if p]
      if len(parts) >= 2:
        return parts[0], parts[1]

  raise Fail(
    "Unable to derive Ozone volume/bucket from configured locations. "
    "Use s3://bucket/... or o3://.../volume/bucket format."
  )


def _create_ozone_storage(volume_name, bucket_name, require_kerberos):
  volume_uri = _ozone_volume_uri(volume_name)
  bucket_uri = _ozone_bucket_uri(volume_name, bucket_name)
  Logger.info(
    "Ensuring Ozone volume '{0}' and bucket '{1}' exist.".format(volume_name, bucket_name)
  )
  _run_ozone(["sh", "volume", "info", volume_uri], run_as="admin", require_kerberos=require_kerberos, checked=False)
  _run_ozone(["sh", "volume", "create", volume_uri], run_as="admin", require_kerberos=require_kerberos, checked=False)
  _run_ozone(["sh", "bucket", "info", bucket_uri], run_as="admin", require_kerberos=require_kerberos, checked=False)
  _run_ozone(["sh", "bucket", "create", bucket_uri], run_as="admin", require_kerberos=require_kerberos, checked=False)


def _grant_ozone_access(access_id, volume_name, bucket_name, require_kerberos):
  import params

  if not getattr(params, "ozone_acl_enabled", False):
    Logger.info("Ozone ACL is disabled; skipping access control setup.")
    return

  if getattr(params, "ozone_acl_managed_by_ranger", False):
    Logger.info(
      "Applying Ranger-managed Ozone policy for '{0}' on {1}/{2}.".format(
        access_id, volume_name, bucket_name
      )
    )
    _create_ranger_ozone_policy(access_id, volume_name, bucket_name)
  else:
    Logger.info(
      "Applying native Ozone ACLs for '{0}' on {1}/{2}.".format(
        access_id, volume_name, bucket_name
      )
    )
    _set_native_ozone_acl(access_id, volume_name, bucket_name, require_kerberos=require_kerberos)


def _set_native_ozone_acl(access_id, volume_name, bucket_name, require_kerberos):
  volume_uri = _ozone_volume_uri(volume_name)
  bucket_uri = _ozone_bucket_uri(volume_name, bucket_name)
  acl_spec = "user:{0}:a".format(access_id)

  _run_ozone(
    ["sh", "volume", "addacl", volume_uri, "-a", acl_spec],
    run_as="admin",
    require_kerberos=require_kerberos,
    checked=False
  )
  _run_ozone(
    ["sh", "bucket", "addacl", bucket_uri, "-a", acl_spec],
    run_as="admin",
    require_kerberos=require_kerberos,
    checked=False
  )
  Logger.info(
    "Applied native Ozone ACLs for '{0}' on {1}/{2}.".format(access_id, volume_name, bucket_name)
  )


def _create_ranger_ozone_policy(access_id, volume_name, bucket_name):
  import params

  ranger_cfg = params.config.get("configurations", {})
  ranger_ozone_security = ranger_cfg.get("ranger-ozone-security", {})
  ranger_admin_site = ranger_cfg.get("ranger-admin-site", {})
  ranger_env = ranger_cfg.get("ranger-env", {})

  policy_url = str(
    ranger_ozone_security.get("ranger.plugin.ozone.policy.rest.url")
    or ranger_admin_site.get("ranger.service.http.url")
    or ""
  ).strip().rstrip("/")
  service_name = str(ranger_ozone_security.get("ranger.plugin.ozone.service.name", "")).strip()
  admin_user = str(ranger_env.get("admin_username", "")).strip()
  admin_password = str(ranger_env.get("admin_password", "")).strip()

  if not policy_url or not service_name or not admin_user or not admin_password:
    Logger.warning("Skipping Ranger Ozone policy; Ranger URL/service/credentials are incomplete.")
    return

  policy_name = "polaris-ozone-{0}-{1}-{2}".format(volume_name, bucket_name, access_id).replace("/", "_")
  lookup_url = "{0}/service/public/v2/api/policy?serviceName={1}&policyName={2}".format(
    policy_url,
    urllib.parse.quote(service_name, safe=""),
    urllib.parse.quote(policy_name, safe="")
  )
  status, payload = _ranger_http("GET", lookup_url, admin_user, admin_password)
  if status == 200 and isinstance(payload, list) and len(payload) > 0:
    Logger.info("Ranger Ozone policy '{0}' already exists.".format(policy_name))
    return

  create_url = "{0}/service/public/v2/api/policy".format(policy_url)
  body = {
    "service": service_name,
    "name": policy_name,
    "isEnabled": True,
    "isAuditEnabled": True,
    "policyType": 0,
    "resources": {
      "volume": {"values": [volume_name], "isExcludes": False, "isRecursive": False},
      "bucket": {"values": [bucket_name], "isExcludes": False, "isRecursive": False},
      "key": {"values": ["*"], "isExcludes": False, "isRecursive": False},
      "prefix": {"values": ["*"], "isExcludes": False, "isRecursive": False},
    },
    "policyItems": [{
      "users": [access_id],
      "groups": [],
      "accesses": [{"type": "all", "isAllowed": True}],
      "delegateAdmin": True
    }]
  }
  create_status, _ = _ranger_http("POST", create_url, admin_user, admin_password, json_body=body)
  if create_status not in (200, 201, 204, 409):
    raise Fail(
      "Failed to create Ranger Ozone policy '{0}'. HTTP status={1}".format(policy_name, create_status)
    )
  Logger.info(
    "Created Ranger Ozone policy '{0}' for access id '{1}'.".format(policy_name, access_id)
  )


def _parse_s3_credentials(output_text):
  """Parses AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY from ozone s3 getsecret output."""
  if not output_text:
    return "", ""

  access_id = ""
  secret = ""
  patterns = [
    (r"awsAccessKey\s*[:=]\s*([^\s]+)", "access"),
    (r"AWS_ACCESS_KEY_ID\s*[:=]\s*([^\s]+)", "access"),
    (r"Secret\s+for\s+['\"]([^'\"]+)['\"]\s+already\s+exists", "access"),
    (r"accessId\s+['\"]([^'\"]+)['\"]\s+not\s+found", "access"),
    (r"awsSecret(?:AccessKey)?\s*[:=]\s*([^\s]+)", "secret"),
    (r"AWS_SECRET_ACCESS_KEY\s*[:=]\s*([^\s]+)", "secret"),
    (r"export\s+AWS_ACCESS_KEY_ID=['\"]?([^'\"\s]+)['\"]?", "access"),
    (r"export\s+AWS_SECRET_ACCESS_KEY=['\"]?([^'\"\s]+)['\"]?", "secret"),
  ]

  for pattern, target in patterns:
    match = re.search(pattern, output_text, re.IGNORECASE)
    if not match:
      continue
    if target == "access" and not access_id:
      access_id = _strip_wrapping_quotes(match.group(1))
    elif target == "secret" and not secret:
      secret = _strip_wrapping_quotes(match.group(1))

  return access_id, secret


def _strip_wrapping_quotes(raw_value):
  value = str(raw_value or "").strip()
  while len(value) >= 2 and (
    (value.startswith("'") and value.endswith("'"))
    or (value.startswith('"') and value.endswith('"'))
  ):
    value = value[1:-1].strip()
  return value


def _run_ozone(args, run_as, require_kerberos, checked):
  import params

  ozone_conf_dir = str(getattr(params, "hadoop_conf_dir", "/etc/hadoop/conf") or "/etc/hadoop/conf")
  ozone_site_path = os.path.join(ozone_conf_dir, "ozone-site.xml")
  if not os.path.exists(ozone_site_path):
    Logger.warning("Skipping Ozone command; missing config file: {0}".format(ozone_site_path))
    return ""

  ozone_cmd = str(getattr(params, "ozone_cmd", "")).strip()
  if not ozone_cmd or not os.path.exists(ozone_cmd):
    ozone_cmd = "ozone"

  if run_as == "polaris":
    run_user = str(getattr(params, "polaris_user", "polaris")).strip() or "polaris"
    principal = str(getattr(params, "polaris_jaas_principal", "")).strip()
    keytab = str(getattr(params, "polaris_keytab_path", "")).strip()
    if not principal:
      principal_template = str(getattr(params, "polaris_principal_value", "")).strip()
      host = str(getattr(params, "polaris_hostname", "")).strip()
      if principal_template and "_HOST" in principal_template and host:
        principal = principal_template.replace("_HOST", host.lower())
  else:
    run_user = str(getattr(params, "ozone_user", "")).strip() or str(getattr(params, "polaris_user", "polaris"))
    principal = str(getattr(params, "ozone_principal_name", "")).strip()
    keytab = str(getattr(params, "ozone_user_keytab", "")).strip()

  cmd = [ozone_cmd, "--config", ozone_conf_dir] + list(args or [])
  om_service_id = str(getattr(params, "ozone_om_service_id", "")).strip()
  if om_service_id and "s3" in cmd and "--om-service-id" not in cmd:
    cmd += ["--om-service-id", om_service_id]

  Logger.info(
    "Running Ozone command (run_as={0}, kerberos={1}): {2}".format(
      run_as,
      require_kerberos,
      " ".join(shlex.quote(str(x)) for x in _mask_secret_args(cmd))
    )
  )

  base_cmd = " ".join(shlex.quote(str(x)) for x in cmd)
  command = base_cmd

  if require_kerberos:
    kinit = str(getattr(params, "kinit_path_local", "")).strip()
    if not kinit or not principal or not keytab:
      raise Fail(
        "Missing kerberos settings for Ozone command as {0}: kinit={1}, principal={2}, keytab={3}".format(
          run_as, bool(kinit), principal or "<empty>", keytab or "<empty>"
        )
      )
    Logger.info(
      "Kerberos kinit for Ozone (run_as={0}, principal={1}).".format(run_as, principal)
    )
    command = "{0} -kt {1} {2}; {3}".format(
      shlex.quote(kinit), shlex.quote(keytab), shlex.quote(principal), base_cmd
    )

  _, out, err = get_user_call_output(
    command,
    user=run_user,
    quiet=not bool(getattr(params, "retry_enabled", False)),
    is_checked_call=checked
  )
  merged_output = "\n".join(filter(None, [out, err]))
  return merged_output


def _mask_secret_args(args):
  """Returns a copy of args with secrets replaced by ****, safe for logging."""
  masked = []
  hide_next = False
  for part in list(args or []):
    token = str(part)
    if hide_next:
      masked.append("****")
      hide_next = False
      continue
    if token.lower().startswith("s3.secret-access-key="):
      masked.append("s3.secret-access-key=****")
      continue
    masked.append(token)
    if token in ("-s", "--secret", "--secret-key", "--aws-secret-access-key", "--client-secret"):
      hide_next = True
  return masked


def _ozone_volume_uri(volume_name):
  import params

  om_service_id = str(getattr(params, "ozone_om_service_id", "")).strip()
  if om_service_id:
    return "o3://{0}/{1}".format(om_service_id, volume_name)
  return "/{0}".format(volume_name)


def _ozone_bucket_uri(volume_name, bucket_name):
  import params

  om_service_id = str(getattr(params, "ozone_om_service_id", "")).strip()
  if om_service_id:
    return "o3://{0}/{1}/{2}".format(om_service_id, volume_name, bucket_name)
  return "/{0}/{1}".format(volume_name, bucket_name)


def _ranger_http(method, url, username, password, json_body=None, timeout=20):
  basic = "{0}:{1}".format(username, password).encode("utf-8")
  auth = "Basic {0}".format(base64.b64encode(basic).decode("utf-8"))
  headers = {"Accept": "application/json", "Authorization": auth}
  payload = None
  if json_body is not None:
    payload = json.dumps(json_body).encode("utf-8")
    headers["Content-Type"] = "application/json"

  request = urllib.request.Request(url=url, data=payload, headers=headers, method=method)
  context = ssl._create_unverified_context() if str(url).lower().startswith("https://") else None
  raw = ""
  status = None
  try:
    response = urllib.request.urlopen(request, timeout=timeout, context=context)
    status = response.getcode()
    raw = response.read().decode("utf-8")
  except urllib.error.HTTPError as http_err:
    status = http_err.code
    if http_err.fp:
      raw = http_err.read().decode("utf-8")
  except urllib.error.URLError as url_err:
    raise Fail("Failed to reach Ranger API at {0}: {1}".format(url, url_err))

  try:
    return status, json.loads(raw) if raw.strip() else {}
  except Exception:
    return status, {"_raw": raw}


def _create_catalog(
  catalog_name,
  base_location,
  allowed_locations,
  s3_endpoint,
  s3_region,
  path_style_access,
  access_key_id,
  secret_key,
):
  """
  Creates a Polaris catalog backed by S3-compatible storage (Ozone) using the polaris CLI.

  Secrets (admin password, S3 access id, S3 secret) are written to temp files and injected
  via bash variable substitution so they never appear on the command line in Ambari logs.
  """
  import params

  cli_candidates = [
    os.path.join(str(getattr(params, "polaris_tools_home", "")).strip(), "bin", "polaris"),
    os.path.join(str(getattr(params, "polaris_home", "")).strip(), "bin", "polaris"),
    "polaris",
  ]
  polaris_cli = next(
    (c for c in cli_candidates if c == "polaris" or (c and os.path.exists(c))),
    None
  )
  if not polaris_cli:
    raise Fail("Polaris CLI binary not found; cannot create catalog.")

  # Resolve Polaris API base URL
  base_url = str(getattr(params, "polaris_service_url", "")).strip().rstrip("/")
  if not base_url:
    protocol = str(getattr(params, "polaris_protocol", "http")).strip().lower()
    host = str(getattr(params, "polaris_service_host", "localhost")).strip() or "localhost"
    port = str(getattr(params, "polaris_port", "8181")).strip() or "8181"
    base_url = "{0}://{1}:{2}".format(protocol, host, port)

  # Resolve realm context (passed to CLI so it targets the right Polaris realm)
  app_props = getattr(params, "application_properties", {}) or {}
  realm_header = str(app_props.get("polaris.realm-context.header-name", "Polaris-Realm")).strip() or "Polaris-Realm"
  realms_source = str(getattr(params, "polaris_bootstrap_realms_raw", "")).strip()
  if not realms_source:
    realms_source = str(app_props.get("polaris.realm-context.realms", "POLARIS")).strip()
  realms = [r.strip() for r in realms_source.split(",") if r.strip()]
  realm = realms[0] if realms else "POLARIS"

  # Temp files for secrets - written with mode 0o600, owned by polaris user.
  # Injected via bash $(cat FILE) so the actual values never appear on the command line.
  pid_dir = str(getattr(params, "polaris_pid_dir", "/var/run/polaris")).strip()
  admin_secret_file = "{0}/polaris-catalog-admin-secret.tmp".format(pid_dir)
  s3_access_file = "{0}/polaris-catalog-s3-access.tmp".format(pid_dir)
  s3_secret_file = "{0}/polaris-catalog-s3-secret.tmp".format(pid_dir)
  wrapper_script = "{0}/polaris-catalog-create.sh".format(pid_dir)

  polaris_user = str(getattr(params, "polaris_user", "polaris"))

  # Build the exec command for the wrapper script.
  # Normal args are shlex-quoted; secret values are substituted from bash vars.
  script_parts = [shlex.quote(polaris_cli)]
  script_parts += ["--base-url", shlex.quote(base_url)]
  script_parts += ["--client-id", shlex.quote(str(params.polaris_admin_username))]
  script_parts += ["--client-secret", '"$POLARIS_ADMIN_SECRET"']
  script_parts += ["--realm", shlex.quote(realm)]
  script_parts += ["--header", shlex.quote(realm_header)]
  script_parts += ["catalogs", "create"]
  script_parts += ["--storage-type", "s3"]
  script_parts += ["--default-base-location", shlex.quote(base_location)]
  script_parts += ["--endpoint", shlex.quote(s3_endpoint)]
  script_parts += ["--region", shlex.quote(s3_region)]
  script_parts += ["--no-sts"]
  script_parts += ["--property", '"s3.access-key-id=$OZONE_S3_ACCESS"']
  script_parts += ["--property", '"s3.secret-access-key=$OZONE_S3_SECRET"']
  for location in list(allowed_locations or []):
    script_parts += ["--allowed-location", shlex.quote(str(location))]
  if path_style_access:
    script_parts.append("--path-style-access")
  script_parts.append(shlex.quote(catalog_name))

  exec_line = "exec " + " \\\n  ".join(script_parts)

  wrapper_content = "\n".join([
    "#!/bin/bash",
    "# Auto-generated by Ambari Polaris catalog bootstrap - do not edit",
    "set +x",
    "POLARIS_ADMIN_SECRET=$(cat {0})".format(shlex.quote(admin_secret_file)),
    "OZONE_S3_ACCESS=$(cat {0})".format(shlex.quote(s3_access_file)),
    "OZONE_S3_SECRET=$(cat {0})".format(shlex.quote(s3_secret_file)),
    exec_line,
    "",
  ])

  # Log the safe version (secrets masked) before executing
  safe_log_parts = [shlex.quote(polaris_cli)]
  safe_log_parts += ["--base-url", shlex.quote(base_url)]
  safe_log_parts += ["--client-id", shlex.quote(str(params.polaris_admin_username))]
  safe_log_parts += ["--client-secret", "****"]
  safe_log_parts += ["--realm", shlex.quote(realm), "--header", shlex.quote(realm_header)]
  safe_log_parts += ["catalogs", "create", "--storage-type", "s3"]
  safe_log_parts += ["--default-base-location", shlex.quote(base_location)]
  safe_log_parts += ["--endpoint", shlex.quote(s3_endpoint), "--region", shlex.quote(s3_region)]
  safe_log_parts += ["--no-sts"]
  safe_log_parts += ["--property", "s3.access-key-id=****", "--property", "s3.secret-access-key=****"]
  for location in list(allowed_locations or []):
    safe_log_parts += ["--allowed-location", shlex.quote(str(location))]
  if path_style_access:
    safe_log_parts.append("--path-style-access")
  safe_log_parts.append(shlex.quote(catalog_name))
  Logger.info("Creating Polaris catalog via CLI: {0}".format(" ".join(safe_log_parts)))

  retries = 5
  retry_sleep = 3
  try:
    File(admin_secret_file, content=str(params.polaris_admin_password),
         owner=polaris_user, group=params.user_group, mode=0o600)
    File(s3_access_file, content=str(access_key_id),
         owner=polaris_user, group=params.user_group, mode=0o600)
    File(s3_secret_file, content=str(secret_key),
         owner=polaris_user, group=params.user_group, mode=0o600)
    File(wrapper_script, content=wrapper_content,
         owner=polaris_user, group=params.user_group, mode=0o700)

    for attempt in range(1, retries + 1):
      rc, out, err = get_user_call_output(
        wrapper_script,
        user=polaris_user,
        quiet=True,
        is_checked_call=False
      )
      merged = "\n".join(filter(None, [out, err]))
      if rc == 0:
        Logger.info("Polaris catalog '{0}' created successfully.".format(catalog_name))
        return

      lowered = merged.lower()
      if "already exists" in lowered or "http 409" in lowered or "conflict" in lowered:
        Logger.info("Polaris catalog '{0}' already exists; skipping.".format(catalog_name))
        return

      if attempt < retries:
        Logger.warning(
          "Catalog creation failed (attempt {0}/{1}, rc={2}); retrying in {3}s. Output: {4}".format(
            attempt, retries, rc, retry_sleep, merged.strip() or "<empty>"
          )
        )
        time.sleep(retry_sleep)
        continue

      raise Fail(
        "Catalog creation failed after {0} attempts (rc={1}). Output: {2}".format(
          retries, rc, merged.strip() or "<empty>"
        )
      )
  finally:
    for tmp_file in [admin_secret_file, s3_access_file, s3_secret_file, wrapper_script]:
      File(tmp_file, action="delete")


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _configure_kerberos():
  import params

  if not params.polaris_jaas_principal:
    Logger.info("Skipping Polaris JAAS configuration; Kerberos principal is not set.")
    return

  _ensure_parent_dir(params.polaris_jaas_conf)
  File(params.polaris_jaas_conf,
       content=InlineTemplate(params.polaris_jaas_conf_template),
       owner=params.polaris_user,
       group=params.user_group,
       mode=0o400
       )


def _needs_token_broker(properties):
  if properties is None:
    return False

  if _is_internal_or_mixed(properties.get("polaris.authentication.type")):
    return True

  for key, value in properties.items():
    if not key:
      continue
    if not key.startswith("polaris.authentication.") or not key.endswith(".type"):
      continue
    if key in ("polaris.authentication.authenticator.type",
               "polaris.authentication.active-roles-provider.type",
               "polaris.authentication.token-service.type",
               "polaris.authentication.token-broker.type"):
      continue
    if _is_internal_or_mixed(value):
      return True

  return False


def _is_internal_or_mixed(value):
  return _normalize(value) in ("internal", "mixed")


def _normalize(value):
  if value is None:
    return None
  return value.strip().lower()


def _is_already_bootstrapped(error):
  text_parts = [
    str(getattr(error, "exception_message", "") or ""),
    str(getattr(error, "out", "") or ""),
    str(getattr(error, "err", "") or ""),
  ]
  error_text = " ".join(text_parts).lower()
  return (
    "already been bootstrapped" in error_text
    or "please first purge the metastore" in error_text
  )


def _sql_identifier(value):
  return '"{0}"'.format(str(value or "").replace('"', '""'))


def _sql_literal(value):
  return "'{0}'".format(str(value or "").replace("'", "''"))


def _run_postgres_sql(sql_file, jdbc_url):
  import params

  jdbc_driver_jar = _find_jdbc_driver()
  if not jdbc_driver_jar:
    raise Fail(_missing_jdbc_driver_message())

  java_bin = os.path.join(params.java64_home, "bin", "java")
  if not os.path.isfile(java_bin):
    java_bin = "java"

  runner_java_file = format("{polaris_pid_dir}/PolarisJdbcSqlRunner.java")
  File(runner_java_file,
       content=_get_jdbc_sql_runner_source(),
       owner=params.polaris_user,
       group=params.user_group,
       mode=0o600
       )

  password_file = format("{polaris_pid_dir}/polaris-db-admin-password.txt")
  File(password_file,
       content=str(params.polaris_db_root_password or ""),
       owner=params.polaris_user,
       group=params.user_group,
       mode=0o600
       )

  cmd_parts = [
    java_bin,
    "-cp",
    jdbc_driver_jar,
    runner_java_file,
    jdbc_url,
    params.polaris_db_root_user,
    sql_file,
    password_file,
  ]
  Execute(
    " ".join(shlex.quote(str(part)) for part in cmd_parts),
    user=params.polaris_user
  )


def _find_jdbc_driver():
  import params

  custom_name = str(getattr(params, "custom_postgres_jdbc_name", "") or "").strip()
  if not custom_name:
    Logger.warning(
      "custom_postgres_jdbc_name is not set in ambariLevelParams. "
      "Polaris DB bootstrap requires the PostgreSQL JDBC driver configured via ambari-server setup."
    )
    return None

  jdbc_jar_name = os.path.basename(custom_name)
  if not jdbc_jar_name:
    Logger.warning("custom_postgres_jdbc_name is invalid: '{0}'".format(custom_name))
    return None

  jdk_location = str(getattr(params, "jdk_location", "/usr/share/java") or "/usr/share/java").strip()
  is_remote_jdk_location = jdk_location.lower().startswith("http://") or jdk_location.lower().startswith("https://")

  if is_remote_jdk_location:
    source_url = "{0}/{1}".format(jdk_location.rstrip("/"), jdbc_jar_name)
    downloaded_jar = format("{polaris_pid_dir}/{jdbc_jar_name}")
    try:
      Logger.info(
        "Downloading PostgreSQL JDBC driver for Polaris DB setup from: {0}".format(source_url)
      )
      File(downloaded_jar,
           content=DownloadSource(source_url),
           owner=params.polaris_user,
           group=params.user_group,
           mode=0o644
           )
      if os.path.isfile(downloaded_jar):
        Logger.info("Using downloaded PostgreSQL JDBC driver: {0}".format(downloaded_jar))
        return downloaded_jar
    except Exception as err:
      Logger.warning(
        "Failed to download PostgreSQL JDBC driver from {0}: {1}".format(source_url, err)
      )

  candidate_paths = [
    os.path.join(jdk_location, jdbc_jar_name),
    os.path.join("/usr/share/java", jdbc_jar_name),
    os.path.join("/usr/lib/ambari-server/resources", jdbc_jar_name),
  ]
  for path in candidate_paths:
    if os.path.isfile(path):
      Logger.info("Using PostgreSQL JDBC driver for Polaris DB setup: {0}".format(path))
      return path

  Logger.warning(
    "PostgreSQL JDBC driver '{0}' not found in: {1}".format(
      custom_name,
      ", ".join(candidate_paths)
    )
  )
  return None


def _missing_jdbc_driver_message():
  return (
    "To use PostgreSQL with Polaris, you must download https://jdbc.postgresql.org/ from PostgreSQL.\n"
    "Once downloaded to the Ambari Server host, run:\n"
    "ambari-server setup --jdbc-db=postgres --jdbc-driver=/path/to/postgresql-jdbc.jar"
  )


def _get_jdbc_sql_runner_source():
  return """import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class PolarisJdbcSqlRunner {
  public static void main(String[] args) throws Exception {
    if (args.length < 4) {
      throw new IllegalArgumentException(\"Expected arguments: <jdbcUrl> <dbUser> <sqlFile> <passwordFile>\");
    }

    String jdbcUrl = args[0];
    String dbUser = args[1];
    String sqlFile = args[2];
    String passwordFile = args[3];
    String dbPassword = Files.readString(Paths.get(passwordFile));
    if (dbPassword.endsWith(\"\\n\")) {
      dbPassword = dbPassword.substring(0, dbPassword.length() - 1);
    }
    if (dbPassword.endsWith(\"\\r\")) {
      dbPassword = dbPassword.substring(0, dbPassword.length() - 1);
    }
    String sql = Files.readString(Paths.get(sqlFile));

    Class.forName(\"org.postgresql.Driver\");
    try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
         Statement stmt = conn.createStatement()) {
      conn.setAutoCommit(false);
      stmt.execute(sql);
      conn.commit();
    } catch (Exception e) {
      throw new RuntimeException(\"Failed to execute SQL against PostgreSQL\", e);
    }
  }
}
"""


def _ensure_parent_dir(path):
  import params

  if not path:
    return

  parent = os.path.dirname(path)
  if not parent:
    return

  Directory(parent,
            mode=0o750,
            cd_access='a',
            owner=params.polaris_user,
            group=params.user_group,
            create_parents=True
            )


def _setup_pem_tls(enabled, auto_generate, cert_file, key_file, label):
  import params

  if not enabled:
    return

  cert_file = str(cert_file or "").strip()
  key_file = str(key_file or "").strip()
  if not cert_file or not key_file:
    raise Fail("{0} TLS is enabled but certificate/key file is not configured.".format(label))

  _ensure_parent_dir(cert_file)
  _ensure_parent_dir(key_file)

  if auto_generate:
    hostname = str(getattr(params, "polaris_hostname", "") or "localhost")
    subject = "/CN={0}/OU=ODP/O=ODP/L=Unknown/ST=Unknown/C=US".format(hostname)
    generate_cmd = " ".join([
      "openssl", "req",
      "-x509",
      "-newkey", "rsa:2048",
      "-nodes",
      "-keyout", shlex.quote(key_file),
      "-out", shlex.quote(cert_file),
      "-days", "3650",
      "-subj", shlex.quote(subject),
    ])
    Execute(generate_cmd,
            user=params.polaris_user,
            not_if="test -f {0} -a -f {1}".format(shlex.quote(cert_file), shlex.quote(key_file)))
  else:
    Execute("test -f {0}".format(shlex.quote(cert_file)),
            user=params.polaris_user)
    Execute("test -f {0}".format(shlex.quote(key_file)),
            user=params.polaris_user)

  File(cert_file,
       owner=params.polaris_user,
       group=params.user_group,
       mode=0o644,
       only_if="test -f {0}".format(shlex.quote(cert_file)))
  File(key_file,
       owner=params.polaris_user,
       group=params.user_group,
       mode=0o600,
       only_if="test -f {0}".format(shlex.quote(key_file)))
