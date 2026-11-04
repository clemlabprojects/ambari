#!/usr/bin/env python3
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

Configuration of the Amoro Management Service and its REST automation (optimizers).
"""
import base64
import glob
import json
import os
import secrets
import shlex
import ssl
import time
import urllib.error
import urllib.request

from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Directory, Execute, File, Link
from resource_management.core.source import DownloadSource, InlineTemplate
from resource_management.core.utils import PasswordString
from resource_management.libraries.resources.xml_config import XmlConfig

LDAP_LOGIN_PROVIDER = "org.apache.amoro.server.authentication.LdapPasswdAuthenticationProvider"
DEFAULT_LOGIN_PROVIDER = "org.apache.amoro.server.authentication.DefaultPasswdAuthenticationProvider"
LOCAL_CONTAINER = "localContainer"
SPARK_CONTAINER = "sparkContainer"
MIN_PARALLELISM = "dynamic-allocation.min-parallelism"


def amoro():
  """Directories and configuration files of the Amoro Management Service."""
  import params

  for path in (params.amoro_log_dir, params.amoro_pid_dir, params.amoro_data_dir):
    Directory(path,
              owner=params.amoro_user,
              group=params.user_group,
              mode=0o755,
              create_parents=True,
              cd_access="a")
  Directory(params.amoro_conf_dir,
            owner=params.amoro_user,
            group=params.user_group,
            mode=0o755,
            create_parents=True)
  # Amoro writes the configuration files of the catalogs (hive-site.xml, krb5.conf, keytabs) under
  # <user.dir>/amoro_krb_conf, and ams.sh sets user.dir to the root-owned package directory.
  conf_cache_dir = os.path.join(params.amoro_data_dir, "amoro_krb_conf")
  Directory(conf_cache_dir,
            owner=params.amoro_user,
            group=params.user_group,
            mode=0o700)
  Link(os.path.join(params.amoro_home, "amoro_krb_conf"), to=conf_cache_dir)
  # Files Ambari does not manage: the plugin configurations (table maintenance processes, execute
  # engines, Iceberg REST extension) and the log configuration of the optimizers. Seeded from the
  # packaged defaults, operator changes are kept.
  for name in ("plugins", "optimize"):
    source = os.path.join(params.amoro_home, "conf.dist", name)
    target = os.path.join(params.amoro_conf_dir, name)
    if os.path.isdir(source) and not os.path.exists(target):
      Execute(("cp", "-r", source, target), sudo=True)
      Execute(("chown", "-R", "{0}:{1}".format(params.amoro_user, params.user_group), target), sudo=True)

  # config.yaml holds the admin, database and LDAP bind passwords.
  File(os.path.join(params.amoro_conf_dir, "config.yaml"),
       content=to_yaml(amoro_config()),
       owner=params.amoro_user,
       group=params.user_group,
       mode=0o600)
  File(os.path.join(params.amoro_conf_dir, "jvm.properties"),
       content=jvm_properties(jvm_truststore()),
       owner=params.amoro_user,
       group=params.user_group,
       mode=0o644)
  if params.hive_site:
    Directory(params.hive_conf_dir,
              owner=params.amoro_user,
              group=params.user_group,
              mode=0o755)
    XmlConfig("hive-site.xml",
              conf_dir=params.hive_conf_dir,
              configurations=amoro_hive_site(),
              configuration_attributes=params.hive_site_attributes,
              owner=params.amoro_user,
              group=params.user_group,
              mode=0o644)
  File(os.path.join(params.amoro_conf_dir, "env.sh"),
       content=InlineTemplate(params.amoro_env_sh_template),
       owner=params.amoro_user,
       group=params.user_group,
       mode=0o755)
  File(os.path.join(params.amoro_conf_dir, "log4j2.xml"),
       content=InlineTemplate(params.amoro_log4j_template),
       owner=params.amoro_user,
       group=params.user_group,
       mode=0o644)

  if params.amoro_db_flavor == "MYSQL":
    setup_mysql_driver()
  if params.ozone_manager_hosts:
    setup_ozone_filesystem()


def amoro_config():
  """The Amoro config.yaml: amoro-site keys nested under "ams", plus the values computed by Ambari."""
  import params

  site = {key: value for key, value in params.amoro_site.items() if str(value).strip() != ""}
  site["server-expose-host"] = params.amoro_server_address
  # Amoro starts the optimizers of a group running below its min-parallelism: check every minute
  # (5 minutes by default, so long without an optimizer after a restart).
  site.setdefault("optimizer-group.min-parallelism-check-interval", "1min")

  ldap_url = str(site.get("http-server.login-auth-ldap-url", "")).strip()
  site["http-server.login-auth-provider"] = LDAP_LOGIN_PROVIDER if ldap_url else DEFAULT_LOGIN_PROVIDER

  # Dashboard RBAC: roles come from LDAP groups, so it needs the LDAP login.
  rbac = ldap_url and str(site.get("http-server.authorization.enabled", "false")).lower() == "true"
  site["http-server.authorization.enabled"] = bool(rbac)
  if rbac:
    site["http-server.authorization.ldap-role-mapping.enabled"] = True
    site["http-server.authorization.ldap-role-mapping.user-dn-pattern"] = site.get("http-server.login-auth-ldap-user-pattern", "")
    if params.amoro_admin_ldap_group_dn:
      site["http-server.authorization.ldap-role-mapping.groups"] = [
        {"group-dn": params.amoro_admin_ldap_group_dn, "role": "SERVICE_ADMIN"}]
  else:
    site = {key: value for key, value in site.items() if not key.startswith("http-server.authorization.") or key == "http-server.authorization.enabled"}

  if not params.amoro_ssl_enabled:
    site = {key: value for key, value in site.items() if not key.startswith("http-server.ssl.") or key == "http-server.ssl.enabled"}

  site["database.type"] = "mysql" if params.amoro_db_flavor == "MYSQL" else "postgres"
  site["database.jdbc-driver-class"] = params.amoro_jdbc_driver_class
  site["database.url"] = params.amoro_jdbc_url
  site["database.username"] = params.amoro_db_user
  site["database.password"] = params.amoro_db_password
  site["database.auto-create-tables"] = True

  for key in ("http-server.bind-port", "thrift-server.table-service.bind-port", "thrift-server.optimizing-service.bind-port"):
    if key in site:
      site[key] = int(site[key])
  for key in ("http-server.ssl.enabled",):
    if key in site:
      site[key] = str(site[key]).lower() == "true"

  containers = [{
    "name": LOCAL_CONTAINER,
    "container-impl": "org.apache.amoro.server.manager.LocalOptimizerContainer",
    "properties": {"export.JAVA_HOME": params.amoro_java_home},
  }]
  if params.amoro_optimizer_container == "spark":
    containers.append(spark_container())
  return {"ams": nest(site), "containers": containers}


def spark_container():
  """Container submitting the optimizers to YARN as Spark applications (cluster mode), from the Spark3
  client of the Amoro host. The optimizer job and Amoro need the JDK of Amoro (Java 21 class files)."""
  import params

  properties = {
    "spark-home": params.spark_home,
    "master": "yarn",
    "deploy-mode": "cluster",
    "job-uri": os.path.join(params.amoro_home, "plugin/optimizer/spark/optimizer-job.jar"),
    "export.JAVA_HOME": params.amoro_java_home,
    "export.HADOOP_CONF_DIR": params.hadoop_conf_dir,
    # The user the optimizer runs as, through spark-submit --proxy-user (without Kerberos: the
    # hadoop.proxyuser settings of core-site allow it).
    "export.HADOOP_USER_NAME": params.amoro_user,
    "spark-conf.spark.yarn.queue": params.amoro_optimizer_yarn_queue,
    "spark-conf.spark.yarn.appMasterEnv.JAVA_HOME": params.amoro_java_home,
    "spark-conf.spark.executorEnv.JAVA_HOME": params.amoro_java_home,
    # spark-submit returns once YARN accepted the application, instead of following it.
    "spark-conf.spark.yarn.submit.waitAppCompletion": "false",
    # Dynamic allocation (on by default in Amoro) without the YARN shuffle service: the optimizer
    # shuffles nothing, its idle executors go back to YARN.
    "spark-conf.spark.dynamicAllocation.shuffleTracking.enabled": "true",
  }
  if params.security_enabled:
    # Submitted with the keytab instead of --proxy-user, which also renews the delegation tokens.
    properties["spark-conf.spark.kerberos.principal"] = params.amoro_principal
    properties["spark-conf.spark.kerberos.keytab"] = params.amoro_keytab
  return {"name": SPARK_CONTAINER,
          "container-impl": "org.apache.amoro.server.manager.SparkOptimizerContainer",
          "properties": properties}


def optimizer_group_properties():
  """Resources of the optimizers of the Ambari group: one optimizing thread per Spark executor, with
  the memory per thread of the local optimizer."""
  import params

  # Amoro keeps the group at its min-parallelism: it starts optimizers when fewer threads run (none
  # while optimizing is paused, nor when the operator manages the optimizers).
  managed = params.amoro_optimizer_managed and not is_optimizing_paused()
  min_parallelism = params.amoro_optimizer_parallelism if managed else 0
  properties = {"memory": str(params.amoro_optimizer_memory),
                MIN_PARALLELISM: str(min_parallelism)}
  if params.amoro_optimizer_container == "spark":
    # spark.dynamicAllocation.maxExecutors: the threads of the optimizer (Spark container default).
    properties.update({
      "spark-conf.spark.executor.cores": "1",
      "spark-conf.spark.executor.memory": "{0}m".format(params.amoro_optimizer_memory),
      "spark-conf.spark.driver.memory": "{0}m".format(params.amoro_optimizer_spark_driver_memory),
    })
  return properties


def nest(flat):
  """{"a.b": 1} -> {"a": {"b": 1}}."""
  tree = {}
  for key in sorted(flat):
    node = tree
    parts = key.split(".")
    for part in parts[:-1]:
      node = node.setdefault(part, {})
    node[parts[-1]] = flat[key]
  return tree


def to_yaml(value, indent=0):
  """YAML of dicts, lists and scalars (strings JSON-quoted, which YAML reads as such)."""
  pad = "  " * indent
  lines = []
  if isinstance(value, dict):
    for key, item in value.items():
      if isinstance(item, (dict, list)) and item:
        lines.append("{0}{1}:".format(pad, key))
        lines.append(to_yaml(item, indent + 1))
      else:
        lines.append("{0}{1}: {2}".format(pad, key, _yaml_scalar(item)))
  elif isinstance(value, list):
    for item in value:
      if isinstance(item, dict):
        first = True
        for key, sub in item.items():
          prefix = "- " if first else "  "
          first = False
          if isinstance(sub, (dict, list)) and sub:
            lines.append("{0}{1}{2}:".format(pad, prefix, key))
            lines.append(to_yaml(sub, indent + 2))
          else:
            lines.append("{0}{1}{2}: {3}".format(pad, prefix, key, _yaml_scalar(sub)))
      else:
        lines.append("{0}- {1}".format(pad, _yaml_scalar(item)))
  else:
    lines.append(pad + _yaml_scalar(value))
  return "\n".join(lines) + ("\n" if indent == 0 else "")


def _yaml_scalar(value):
  if isinstance(value, bool):
    return "true" if value else "false"
  if isinstance(value, int):
    return str(value)
  if isinstance(value, (dict, list)):
    return "{}" if isinstance(value, dict) else "[]"
  return json.dumps(str(value))


def amoro_hive_site():
  """hive-site of the cluster without its secrets (metastore database password, keystore passwords, S3 keys):
  Amoro only needs the metastore client settings, and uploads this file into its database with the Hive
  catalog. Secrets are the properties Ambari hides (password properties) and the names that say so."""
  import params

  hidden = (params.hive_site_attributes or {}).get("hidden", {})
  return {name: value for name, value in params.hive_site.items()
          if name not in hidden and "password" not in name.lower() and "secret" not in name.lower()}


def jvm_truststore():
  """(path, type) of the truststore of the server JVM. With a truststore password, a JKS copy of the cluster
  truststore owned by Amoro: Java reads the trusted certificates of a JKS file without its password, which
  then stays off the command line of the server (ps), unlike -Djavax.net.ssl.trustStorePassword."""
  import params

  if not params.amoro_truststore_path or not params.amoro_truststore_password:
    return params.amoro_truststore_path, params.amoro_truststore_type
  target = params.amoro_jvm_truststore_path
  source_password = target + ".source-password"
  target_password = target + ".password"
  File(source_password, content=PasswordString(params.amoro_truststore_password), owner=params.amoro_user, mode=0o600)
  File(target_password, content=PasswordString(secrets.token_urlsafe(24)), owner=params.amoro_user, mode=0o600)
  File(target, action="delete")
  try:
    # the source type is detected by keytool (a PKCS12 file named .jks is common)
    Execute((os.path.join(params.amoro_java_home, "bin", "keytool"), "-importkeystore", "-noprompt",
             "-srckeystore", params.amoro_truststore_path, "-srcstorepass:file", source_password,
             "-destkeystore", target, "-deststoretype", "JKS", "-deststorepass:file", target_password),
            user=params.amoro_user)
  finally:
    File(source_password, action="delete")
    File(target_password, action="delete")
  File(target, owner=params.amoro_user, group=params.user_group, mode=0o640)
  return target, "JKS"


def jvm_properties(truststore=None):
  import params

  path, store_type = truststore or (params.amoro_truststore_path, params.amoro_truststore_type)
  options = "-XX:NewRatio=1 -XX:SurvivorRatio=3 -XX:+ExitOnOutOfMemoryError"
  if path:
    # The cluster CA, for the TLS clients of the server (Polaris). Not JAVA_TOOL_OPTIONS in env.sh: the
    # scripts parse the first line "java -version" prints, which the JVM would replace by "Picked up ...".
    options += " -Djavax.net.ssl.trustStore={0} -Djavax.net.ssl.trustStoreType={1}".format(path, store_type)
  return "xms={0}\nxmx={0}\njmx.remote.port=\nextra.options=\"{1}\"\n".format(params.amoro_server_heapsize, options)


def amoro_environment():
  """Environment of bin/ams.sh: JDK and the Ambari-managed directories."""
  import params

  return {
    "JAVA_HOME": params.amoro_java_home,
    "AMORO_CONF_DIR": params.amoro_conf_dir,
    "AMORO_LOG_DIR": params.amoro_log_dir,
    "AMORO_PID_DIR": params.amoro_pid_dir,
  }


# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

def setup_database():
  """Creates the PostgreSQL database and user of Amoro (create_db_dbuser); Amoro creates its tables."""
  import params

  if not params.amoro_create_db_dbuser:
    Logger.info("create_db_dbuser is disabled: the Amoro database and user must already exist.")
    return
  if params.amoro_db_flavor != "POSTGRES":
    raise Fail("Ambari creates the Amoro database for PostgreSQL only: pre-create the {0} database and user "
               "and set create_db_dbuser to false.".format(params.amoro_db_flavor))
  if not params.amoro_db_root_password.strip():
    raise Fail("db_root_password is required when create_db_dbuser is true.")

  user = _sql_identifier(params.amoro_db_user)
  database = _sql_identifier(params.amoro_db_name)
  root_url = params.amoro_privelege_user_jdbc_url or "jdbc:postgresql://{0}:{1}/postgres".format(
    params.amoro_db_host, params.amoro_db_port)
  # The role password follows db_password at every start.
  _run_postgres_sql(("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = {name}) THEN "
                     "CREATE ROLE {user} LOGIN PASSWORD {password}; "
                     "ELSE ALTER ROLE {user} LOGIN PASSWORD {password}; END IF; END $$").format(
                       name=_sql_literal(params.amoro_db_user), user=user,
                       password=_sql_literal(params.amoro_db_password)),
                    root_url, "role")
  # CREATE DATABASE cannot run in a DO block (transaction): the query returns it when missing.
  _run_postgres_sql("SELECT {0} WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = {1})".format(
                      _sql_literal("CREATE DATABASE {0} OWNER {1}".format(database, user)),
                      _sql_literal(params.amoro_db_name)),
                    root_url, "database", create_database=True)
  _run_postgres_sql("ALTER DATABASE {0} OWNER TO {1}".format(database, user), root_url, "owner")
  _run_postgres_sql("GRANT USAGE, CREATE ON SCHEMA public TO {0}".format(user), params.amoro_jdbc_url, "schema")


def _sql_identifier(value):
  return '"{0}"'.format(str(value).replace('"', '""'))


def _sql_literal(value):
  return "'{0}'".format(str(value or "").replace("'", "''"))


def _run_postgres_sql(sql, jdbc_url, label, create_database=False):
  """Runs sql as the DBA through JDBC (no psql on the hosts). With create_database, sql is a query
  returning the CREATE DATABASE statement to run outside a transaction, or nothing."""
  import params

  driver = _postgres_driver()
  runner = os.path.join(params.amoro_data_dir, "AmoroJdbcSqlRunner.java")
  File(runner, content=JDBC_SQL_RUNNER, owner=params.amoro_user, group=params.user_group, mode=0o600)
  sql_file = os.path.join(params.amoro_data_dir, "amoro-db-{0}.sql".format(label))
  File(sql_file, content=sql, owner=params.amoro_user, group=params.user_group, mode=0o600)
  password_file = os.path.join(params.amoro_data_dir, "amoro-db-admin.secret")
  File(password_file, content=params.amoro_db_root_password, owner=params.amoro_user,
       group=params.user_group, mode=0o600)
  try:
    command = [os.path.join(params.amoro_java_home, "bin", "java"), "-cp", driver, runner,
               jdbc_url, params.amoro_db_root_user, sql_file, password_file, "create-database" if create_database else "execute"]
    Execute(" ".join(shlex.quote(part) for part in command), user=params.amoro_user)
  finally:
    # Both hold secrets (the role statement carries the Amoro DB password).
    File(password_file, action="delete")
    File(sql_file, action="delete")


def _postgres_driver():
  import params

  bundled = sorted(glob.glob(os.path.join(params.amoro_home, "lib", "postgresql-*.jar")))
  if bundled:
    return bundled[-1]
  raise Fail("PostgreSQL JDBC driver not found in {0}/lib.".format(params.amoro_home))


def setup_mysql_driver():
  """Amoro does not ship the MySQL driver: the one registered on the Ambari server goes into its lib."""
  import params

  name = os.path.basename(str(params.custom_mysql_jdbc_name or "").strip())
  if not name:
    raise Fail("The MySQL JDBC driver is not registered on the Ambari server: run "
               "ambari-server setup --jdbc-db=mysql --jdbc-driver=/path/to/mysql-connector-j.jar")
  File(os.path.join(params.amoro_home, "lib", name),
       content=DownloadSource("{0}/{1}".format(params.jdk_location.rstrip("/"), name)),
       mode=0o644)



def setup_ozone_filesystem():
  """The Hadoop client Amoro ships has no ofs:// / o3fs:// filesystem: the terminal and the optimizers
  cannot read a warehouse on Ozone. Amoro bundles the shaded Hadoop client, which takes the shaded Ozone
  filesystem jar (the regular one needs the unshaded protobuf). Copied into lib: the launchers of the
  server and of the optimizers only add the regular jar files of lib to the classpath."""
  import params

  jars = sorted(glob.glob(os.path.join(params.ozone_client_lib_dir, "ozone-filesystem-hadoop3-client-*.jar")))
  if not jars:
    Logger.warning("No Ozone filesystem jar in {0}: Amoro cannot read the tables stored on Ozone.".format(
      params.ozone_client_lib_dir))
    return
  target = os.path.join(params.amoro_home, "lib", os.path.basename(jars[-1]))
  Execute(("cp", "--remove-destination", jars[-1], target), sudo=True)
  Execute(("chmod", "644", target), sudo=True)


JDBC_SQL_RUNNER = """import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class AmoroJdbcSqlRunner {
  public static void main(String[] args) throws Exception {
    String password = Files.readString(Paths.get(args[3])).replaceAll("[\\\\r\\\\n]+$", "");
    String sql = Files.readString(Paths.get(args[2]));
    Class.forName("org.postgresql.Driver");
    try (Connection conn = DriverManager.getConnection(args[0], args[1], password);
         Statement stmt = conn.createStatement()) {
      if ("create-database".equals(args[4])) {
        String create = null;
        try (ResultSet rs = stmt.executeQuery(sql)) {
          if (rs.next()) {
            create = rs.getString(1);
          }
        }
        if (create != null) {
          stmt.execute(create);
        }
      } else {
        stmt.execute(sql);
      }
    }
  }
}
"""


# ---------------------------------------------------------------------------
# REST API of the Amoro Management Service (built-in admin, basic authentication)
# ---------------------------------------------------------------------------

def amoro_api(method, path, body=None, timeout=60, raw_body=None, content_type="application/json"):
  """Calls the REST API with a JSON body (or raw_body); returns the "result" of the response, raises
  Fail on an error."""
  import params

  headers = {
    "Authorization": "Basic " + base64.b64encode("{0}:{1}".format(
      params.amoro_admin_username, params.amoro_admin_password).encode("utf-8")).decode("utf-8"),
    "Content-Type": content_type,
  }
  data = raw_body if raw_body is not None else (json.dumps(body).encode("utf-8") if body is not None else None)
  request = urllib.request.Request(params.amoro_url + "/api/ams/v1" + path, data=data, method=method, headers=headers)
  context = ssl._create_unverified_context() if params.amoro_url.startswith("https://") else None
  try:
    payload = json.loads(urllib.request.urlopen(request, timeout=timeout, context=context).read().decode("utf-8") or "{}")
  except urllib.error.HTTPError as error:
    raise Fail("Amoro {0} {1}: HTTP {2} {3}".format(method, path, error.code, error.read().decode("utf-8", "replace")[:300]))
  except (urllib.error.URLError, OSError) as error:
    raise Fail("Cannot reach Amoro at {0}: {1}".format(params.amoro_url, error))
  if payload.get("code") not in (None, 200):
    raise Fail("Amoro {0} {1}: {2}".format(method, path, payload.get("message") or payload))
  return payload.get("result")


def wait_for_amoro(timeout=300):
  deadline = time.time() + timeout
  while True:
    try:
      return amoro_api("GET", "/catalogs")
    except Fail as error:
      if time.time() > deadline:
        raise
      Logger.info("Waiting for the Amoro Management Service: {0}".format(error))
      time.sleep(5)


def ensure_optimizer_group():
  """The optimizer group of Ambari, running its optimizers in the local or the Spark container. Amoro
  keeps it at its min-parallelism (optimizer_group_properties): it starts the optimizers, and starts
  new ones when they die."""
  import params

  groups = amoro_api("GET", "/optimize/resourceGroups") or []
  names = [(group.get("resourceGroup") or group).get("name") for group in groups if isinstance(group, dict)]
  container = SPARK_CONTAINER if params.amoro_optimizer_container == "spark" else LOCAL_CONTAINER
  body = {"name": params.amoro_optimizer_group, "container": container,
          "properties": optimizer_group_properties()}
  if params.amoro_optimizer_group in names:
    amoro_api("PUT", "/optimize/resourceGroups", body)
  else:
    amoro_api("POST", "/optimize/resourceGroups", body)
    Logger.info("Created the Amoro optimizer group '{0}'.".format(params.amoro_optimizer_group))


def optimizers(group=None):
  """Optimizers of a group (default: the group of Ambari; "all": every group)."""
  import params

  result = amoro_api("GET", "/optimize/optimizerGroups/{0}/optimizers?page=1&pageSize=1000".format(
    group or params.amoro_optimizer_group)) or {}
  return result.get("list", [])


def release_optimizers():
  """Releases the optimizers of the Ambari group: their container ends them, and Amoro forgets them, so
  that the next start does not take their registrations for running optimizers."""
  for optimizer in optimizers():
    amoro_api("DELETE", "/optimize/optimizers/{0}".format(optimizer["jobId"]))
    Logger.info("Released the optimizer {0}.".format(optimizer["jobId"]))


# ---------------------------------------------------------------------------
# Pause and resume of the optimizing (stack upgrade)
# ---------------------------------------------------------------------------

# Optimizing states of a table holding a process (planning and pending ones hold none yet).
ACTIVE_OPTIMIZING_STATUSES = ("full", "major", "minor", "committing")
# The flag the PAUSE_OPTIMIZING action leaves in the data dir: the server starts no optimizer while it
# exists, and the "Amoro Optimizing Paused" alert reports it.
PAUSED_FLAG = "optimizing.paused"


def paused_flag():
  import params
  return os.path.join(params.amoro_data_dir, PAUSED_FLAG)


def is_optimizing_paused():
  return os.path.exists(paused_flag())


def pause_optimizing(timeout=900):
  """Stops the optimizing: no optimizer is left to poll tasks (Amoro plans only for a polling optimizer)
  and the running processes are cancelled. Their persisted tasks hold Java-serialized Iceberg objects
  a newer Iceberg cannot read, which is why a stack upgrade requires it."""
  import params

  File(paused_flag(), content="Optimizing paused by Ambari\n", owner=params.amoro_user,
       group=params.user_group, mode=0o644)
  # min-parallelism 0 first, or Amoro would start the released optimizers again.
  ensure_optimizer_group()
  for optimizer in optimizers(group="all"):
    amoro_api("DELETE", "/optimize/optimizers/{0}".format(optimizer["jobId"]))
    Logger.info("Released the optimizer {0} of the group '{1}'.".format(optimizer["jobId"], optimizer.get("groupName")))

  deadline = time.time() + timeout
  while True:
    active = active_optimizing_tables()
    if not active:
      Logger.info("Optimizing is paused: no optimizer, no running optimizing process.")
      return
    for table in active:
      for process in running_processes(table):
        amoro_api("POST", "/catalogs/{0}/dbs/{1}/tables/{2}/optimizing-processes/{3}/cancel".format(
          table["catalog"], table["database"], table["tableName"], process["processId"]))
        Logger.info("Cancelled the optimizing process {0} of {1}.{2}.{3}.".format(
          process["processId"], table["catalog"], table["database"], table["tableName"]))
    if time.time() > deadline:
      raise Fail("Optimizing processes are still active after {0}s: {1}".format(timeout, ", ".join(
        "{catalog}.{database}.{tableName}".format(**table) for table in active)))
    time.sleep(10)


def resume_optimizing():
  import params

  File(paused_flag(), action="delete")
  ensure_optimizer_group()
  Logger.info("Optimizing resumed: Amoro starts the optimizers of the group '{0}'.".format(
    params.amoro_optimizer_group))


def check_optimizing_paused():
  """Fails unless the optimizing is paused (stack upgrade, before the restart of the server)."""
  if not is_optimizing_paused():
    raise Fail("Optimizing is not paused: run the Amoro service action 'Pause Optimizing', then retry.")
  if optimizers(group="all"):
    raise Fail("Optimizers are running although optimizing is paused: run 'Pause Optimizing' again, then retry.")
  active = active_optimizing_tables()
  if active:
    raise Fail("Optimizing processes are still active: {0}. Run 'Pause Optimizing' again, then retry.".format(
      ", ".join("{catalog}.{database}.{tableName}".format(**table) for table in active)))
  Logger.info("Optimizing is paused.")


def active_optimizing_tables():
  """Table identifiers (catalog, database, tableName) holding an optimizing process."""
  query = "&".join("actions%5B%5D=" + status for status in ACTIVE_OPTIMIZING_STATUSES)
  result = amoro_api("GET", "/optimize/optimizerGroups/all/tables?page=1&pageSize=1000&" + query) or {}
  return [table["tableIdentifier"] for table in result.get("list", [])
          if table.get("optimizeStatus") in ACTIVE_OPTIMIZING_STATUSES]


def running_processes(table):
  result = amoro_api("GET", "/catalogs/{0}/dbs/{1}/tables/{2}/optimizing-processes?status=RUNNING&page=1&pageSize=100".format(
    table["catalog"], table["database"], table["tableName"])) or {}
  return result.get("list", [])
