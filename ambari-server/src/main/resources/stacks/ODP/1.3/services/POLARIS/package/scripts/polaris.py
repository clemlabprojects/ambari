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
import shlex

from resource_management.core.logger import Logger
from resource_management.core.exceptions import Fail
from resource_management.core.resources.system import Directory, Execute, File
from resource_management.core.source import InlineTemplate
from resource_management.libraries.functions.format import format
from resource_management.libraries.resources.properties_file import PropertiesFile


def polaris(component_type='server'):
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

  if params.security_enabled:
    _configure_kerberos()

  PropertiesFile(format("{polaris_conf_dir}/{polaris_conf_file}"),
                 properties=params.application_properties,
                 owner=params.polaris_user,
                 group=params.user_group,
                 mode=0o640
                 )

  setup_tools_tls()

  if component_type == 'server':
    setup_server_tls()
    setup_relational_db()
    setup_metastore_bootstrap()
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


def setup_token_broker():
  import params

  if not _should_configure_token_broker(params.application_properties):
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


def setup_relational_db():
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

  # Idempotent PostgreSQL bootstrap: create/alter user, create database if missing, grant privileges.
  sql = (
    "DO $$ BEGIN "
    "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = {user_name_literal}) THEN "
    "CREATE ROLE {quoted_user} LOGIN PASSWORD {user_password}; "
    "ELSE "
    "ALTER ROLE {quoted_user} LOGIN PASSWORD {user_password}; "
    "END IF; "
    "END $$; "
    "DO $$ BEGIN "
    "IF NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = {db_name_literal}) THEN "
    "CREATE DATABASE {quoted_db} OWNER {quoted_user}; "
    "END IF; "
    "END $$; "
    "GRANT ALL PRIVILEGES ON DATABASE {quoted_db} TO {quoted_user};"
  ).format(
    quoted_db=quoted_db,
    quoted_user=quoted_user,
    user_password=user_password,
    user_name_literal=user_name_literal,
    db_name_literal=db_name_literal
  )

  sql_file = format("{polaris_pid_dir}/polaris-db-bootstrap.sql")
  File(sql_file,
       content=sql,
       owner=params.polaris_user,
       group=params.user_group,
       mode=0o600
       )

  Execute("command -v psql >/dev/null 2>&1",
          user=params.polaris_user
          )

  setup_cmd = format(
    'psql -v ON_ERROR_STOP=1 '
    '-h {polaris_db_host} '
    '-p {polaris_db_port} '
    '-U {polaris_db_root_user} '
    '-d postgres '
    '-f {sql_file}'
  )

  Execute(setup_cmd,
          user=params.polaris_user,
          environment={'PGPASSWORD': params.polaris_db_root_password}
          )


def setup_metastore_bootstrap():
  import params

  persistence_type = _normalize(params.application_properties.get("polaris.persistence.type", "in-memory"))
  if persistence_type != "relational-jdbc":
    return

  realms_source = str(getattr(params, "polaris_bootstrap_realms_raw", "")).strip()
  if not realms_source:
    realms_source = str(params.application_properties.get("polaris.realm-context.realms", "POLARIS")).strip()
  realms = [realm.strip() for realm in realms_source.split(",") if realm.strip()]
  if not realms:
    Logger.info("Skipping Polaris metastore bootstrap; no realms configured.")
    return

  auth_type = _normalize(params.application_properties.get("polaris.authentication.type", "internal")) or "internal"
  bootstrap_env = {}
  if params.polaris_bootstrap_credentials:
    bootstrap_env["POLARIS_BOOTSTRAP_CREDENTIALS"] = params.polaris_bootstrap_credentials
  elif auth_type in ("internal", "mixed"):
    raise Fail("POLARIS bootstrap credentials are required for internal/mixed auth in relational-jdbc mode.")

  bootstrap_cmd_parts = [format("{polaris_home}/bin/admin"), "bootstrap"]
  for realm in realms:
    bootstrap_cmd_parts.extend(["-r", realm])
  bootstrap_cmd = " ".join(shlex.quote(part) for part in bootstrap_cmd_parts)

  Logger.info("Running Polaris metastore bootstrap for realms: {0}".format(", ".join(realms)))
  Execute(
    "source {0}/polaris-env.sh; {1}".format(params.polaris_conf_dir, bootstrap_cmd),
    user=params.polaris_user,
    environment=bootstrap_env
  )


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


def _should_configure_token_broker(properties):
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
  normalized = _normalize(value)
  return normalized in ("internal", "mixed")


def _normalize(value):
  if value is None:
    return None
  return value.strip().lower()


def _sql_identifier(value):
  return '"{0}"'.format(str(value or "").replace('"', '""'))


def _sql_literal(value):
  return "'{0}'".format(str(value or "").replace("'", "''"))


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
