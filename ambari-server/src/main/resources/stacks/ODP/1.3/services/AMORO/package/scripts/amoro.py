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
import shlex
import ssl
import time
import urllib.error
import urllib.request

from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Directory, Execute, File, Link
from resource_management.core.source import DownloadSource, InlineTemplate

LDAP_LOGIN_PROVIDER = "org.apache.amoro.server.authentication.LdapPasswdAuthenticationProvider"
DEFAULT_LOGIN_PROVIDER = "org.apache.amoro.server.authentication.DefaultPasswdAuthenticationProvider"
LOCAL_CONTAINER = "localContainer"


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
       content=jvm_properties(),
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


def amoro_config():
  """The Amoro config.yaml: amoro-site keys nested under "ams", plus the values computed by Ambari."""
  import params

  site = {key: value for key, value in params.amoro_site.items() if str(value).strip() != ""}
  site["server-expose-host"] = params.amoro_server_address

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

  container = {
    "name": LOCAL_CONTAINER,
    "container-impl": "org.apache.amoro.server.manager.LocalOptimizerContainer",
    "properties": {"export.JAVA_HOME": params.amoro_java_home},
  }
  return {"ams": nest(site), "containers": [container]}


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


def jvm_properties():
  import params

  options = "-XX:NewRatio=1 -XX:SurvivorRatio=3 -XX:+ExitOnOutOfMemoryError"
  if params.amoro_truststore_path:
    # The cluster CA, for the TLS clients of the server (Polaris). Not JAVA_TOOL_OPTIONS in env.sh: the
    # scripts parse the first line "java -version" prints, which the JVM would replace by "Picked up ...".
    options += " -Djavax.net.ssl.trustStore={0} -Djavax.net.ssl.trustStoreType={1}".format(
      params.amoro_truststore_path, params.amoro_truststore_type)
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
  """The optimizer group of Ambari, running its optimizers in the local container."""
  import params

  groups = amoro_api("GET", "/optimize/resourceGroups") or []
  names = [(group.get("resourceGroup") or group).get("name") for group in groups if isinstance(group, dict)]
  body = {"name": params.amoro_optimizer_group, "container": LOCAL_CONTAINER,
          "properties": {"memory": str(params.amoro_optimizer_memory)}}
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


def ensure_optimizer():
  """Starts the optimizer of the Ambari group when none is running, unless optimizing is paused."""
  import params

  if is_optimizing_paused():
    Logger.warning("Optimizing is paused: no optimizer started. Run the Amoro service action "
                   "'Resume Optimizing' to resume table maintenance.")
    return
  if optimizers():
    return
  amoro_api("POST", "/optimize/optimizers", {"optimizerGroup": params.amoro_optimizer_group,
                                             "parallelism": params.amoro_optimizer_parallelism})
  Logger.info("Started an optimizer in the Amoro optimizer group '{0}' (parallelism {1}).".format(
    params.amoro_optimizer_group, params.amoro_optimizer_parallelism))


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
  File(paused_flag(), action="delete")
  ensure_optimizer_group()
  ensure_optimizer()
  Logger.info("Optimizing resumed.")


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
