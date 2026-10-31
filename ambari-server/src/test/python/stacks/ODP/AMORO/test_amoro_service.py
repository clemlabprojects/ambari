"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""

import importlib.util
import json
import os
import sys
import types
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[7]
RESOURCES = ROOT / "ambari-server/src/main/resources"
STACK = RESOURCES / "stacks/ODP/1.3"
SERVICE = STACK / "services/AMORO"


def load_module(name, path):
  spec = importlib.util.spec_from_file_location(name, path)
  module = importlib.util.module_from_spec(spec)
  spec.loader.exec_module(module)
  return module


def load_amoro_advisor():
  for name, path in (("ambari_configuration", "stacks/ambari_configuration.py"), ("stack_advisor", "stacks/stack_advisor.py")):
    sys.modules[name] = load_module(name, RESOURCES / path)
  return load_module("odp_amoro_service_advisor", SERVICE / "service_advisor.py")


def load_amoro_scripts(**overrides):
  """amoro.py against a params module holding the given values."""
  sys.path.insert(0, str(ROOT / "ambari-common/src/main/python"))
  params = types.ModuleType("params")
  params.amoro_site = {
    "admin-username": "admin", "admin-password": "secret", "server-bind-host": "0.0.0.0",
    "http-server.bind-port": "1630", "thrift-server.table-service.bind-port": "1260",
    "thrift-server.optimizing-service.bind-port": "1261", "http-server.rest-auth-type": "basic",
    "http-server.login-auth-ldap-url": "", "http-server.login-auth-ldap-user-pattern": "",
    "http-server.authorization.enabled": "true", "http-server.authorization.default-role": "VIEWER",
    "http-server.authorization.ldap-role-mapping.group-member-attribute": "member",
    "http-server.authorization.ldap-role-mapping.bind-dn": "", "http-server.ssl.enabled": "false",
    "http-server.ssl.keystore-path": "/etc/security/serverKeys/keystore.jks", "http-server.ssl.keystore-type": "JKS",
    "http-server.ssl.keystore-password": "",
  }
  params.amoro_server_address = "10.0.0.1"
  params.amoro_admin_ldap_group_dn = ""
  params.amoro_ssl_enabled = False
  params.amoro_db_flavor = "POSTGRES"
  params.amoro_jdbc_driver_class = "org.postgresql.Driver"
  params.amoro_jdbc_url = "jdbc:postgresql://db:5432/amoro"
  params.amoro_db_user = "amoro"
  params.amoro_db_password = "db'secret"
  params.amoro_java_home = "/usr/lib/jvm/java-21"
  params.amoro_server_heapsize = 2048
  for key, value in overrides.items():
    if key == "site":
      params.amoro_site.update(value)
    else:
      setattr(params, key, value)
  sys.modules["params"] = params
  from resource_management.core.logger import Logger
  if Logger.logger is None:
    Logger.initialize_logger()
  sys.modules["amoro"] = load_module("amoro", SERVICE / "package/scripts/amoro.py")
  return sys.modules["amoro"]


class TestAmoroConfig(unittest.TestCase):

  def config(self, **overrides):
    amoro = load_amoro_scripts(**overrides)
    return amoro.amoro_config(), amoro

  def test_built_in_login_without_ldap(self):
    config, amoro = self.config()
    http = config["ams"]["http-server"]
    self.assertEqual(amoro.DEFAULT_LOGIN_PROVIDER, http["login-auth-provider"])
    self.assertEqual({"enabled": False}, http["authorization"])
    self.assertEqual({"enabled": False}, http["ssl"])
    self.assertNotIn("login-auth-ldap-url", http)
    self.assertEqual("10.0.0.1", config["ams"]["server-expose-host"])
    self.assertEqual(1630, http["bind-port"])

  def test_ldap_login_maps_the_admin_group(self):
    config, amoro = self.config(amoro_admin_ldap_group_dn="cn=amoro-admins,cn=groups,dc=example,dc=com", site={
      "http-server.login-auth-ldap-url": "ldaps://ipa:636",
      "http-server.login-auth-ldap-user-pattern": "uid={0},cn=users,dc=example,dc=com"})
    http = config["ams"]["http-server"]
    self.assertEqual(amoro.LDAP_LOGIN_PROVIDER, http["login-auth-provider"])
    mapping = http["authorization"]["ldap-role-mapping"]
    self.assertTrue(http["authorization"]["enabled"])
    self.assertEqual("uid={0},cn=users,dc=example,dc=com", mapping["user-dn-pattern"])
    self.assertEqual([{"group-dn": "cn=amoro-admins,cn=groups,dc=example,dc=com", "role": "SERVICE_ADMIN"}], mapping["groups"])

  def test_database_and_local_container(self):
    config, _ = self.config()
    self.assertEqual({"type": "postgres", "jdbc-driver-class": "org.postgresql.Driver", "url": "jdbc:postgresql://db:5432/amoro",
                      "username": "amoro", "password": "db'secret", "auto-create-tables": True}, config["ams"]["database"])
    self.assertEqual([{"name": "localContainer", "container-impl": "org.apache.amoro.server.manager.LocalOptimizerContainer",
                       "properties": {"export.JAVA_HOME": "/usr/lib/jvm/java-21"}}], config["containers"])

  def test_tls_keys_only_when_enabled(self):
    config, _ = self.config(amoro_ssl_enabled=True, site={"http-server.ssl.enabled": "true",
                                                          "http-server.ssl.keystore-password": "changeit"})
    self.assertEqual({"enabled": True, "keystore-path": "/etc/security/serverKeys/keystore.jks", "keystore-type": "JKS",
                      "keystore-password": "changeit"}, config["ams"]["http-server"]["ssl"])

  def test_jvm_properties_carry_the_truststore(self):
    _, amoro = self.config(amoro_truststore_path="", amoro_truststore_type="JKS")
    self.assertNotIn("trustStore", amoro.jvm_properties())
    _, amoro = self.config(amoro_truststore_path="/etc/security/clientKeys/all.jks", amoro_truststore_type="JKS")
    self.assertIn('+ExitOnOutOfMemoryError -Djavax.net.ssl.trustStore=/etc/security/clientKeys/all.jks '
                  '-Djavax.net.ssl.trustStoreType=JKS"', amoro.jvm_properties())
    self.assertTrue(amoro.jvm_properties().startswith("xms=2048\nxmx=2048\n"))

  def test_yaml_round_trip(self):
    try:
      import yaml
    except ImportError:
      self.skipTest("PyYAML is not installed")
    config, amoro = self.config(amoro_admin_ldap_group_dn="cn=admins,dc=x", site={
      "http-server.login-auth-ldap-url": "ldap://ipa:389", "http-server.login-auth-ldap-user-pattern": "uid={0},dc=x",
      "admin-password": "a\"b: c#d"})
    self.assertEqual(json.loads(json.dumps(config)), yaml.safe_load(amoro.to_yaml(config)))


def load_amoro_catalogs(**overrides):
  """amoro_catalogs.py against a params module, with the Amoro REST API recorded instead of called."""
  amoro = load_amoro_scripts()
  params = sys.modules["params"]
  defaults = dict(
    has_hive=True, has_polaris_ozone_catalog=False, amoro_hive_catalog_name="hive", polaris_catalog_name="ozone",
    hadoop_conf_dir="/etc/hadoop/conf", hive_conf_dir="/etc/hive/conf", security_enabled=True,
    amoro_principal="amoro/edge.example.com@EXAMPLE.COM", amoro_keytab="/etc/security/keytabs/amoro.service.keytab",
    krb5_conf_path="/etc/krb5.conf", amoro_user="amoro", amoro_optimizer_group="local",
    amoro_polaris_principal="amoro", polaris_authorization_type="ranger", polaris_ranger_service="c1_polaris",
    polaris_url="https://polaris:8443", polaris_realm="POLARIS", polaris_realm_header="Polaris-Realm",
    ranger_admin_url="https://ranger:6182", ranger_admin_username="admin", ranger_admin_password="x")
  defaults.update(overrides)
  for key, value in defaults.items():
    setattr(params, key, value)
  module = load_module("amoro_catalogs", SERVICE / "package/scripts/amoro_catalogs.py")
  sys.modules["amoro_catalogs"] = module
  module.calls = []

  def amoro_api(method, path, body=None, timeout=60, raw_body=None, content_type=None):
    module.calls.append((method, path, body))
    if path == "/catalogs" and method == "GET":
      return [{"catalogName": name} for name in module.existing]
    if path == "/files":
      return {"id": str(len(module.calls))}
    return None
  module.existing = []
  module.amoro.amoro_api = amoro_api
  return module


class TestAmoroCatalogs(unittest.TestCase):

  def test_hive_catalog_uploads_configurations_and_keytab(self):
    catalogs = load_amoro_catalogs()
    catalogs.os.path.isfile = lambda path: True
    uploads = []
    catalogs.upload_file = lambda path: uploads.append(path) or "f{0}".format(len(uploads))
    catalogs.register_catalogs(required=True)
    method, path, body = catalogs.calls[-1]
    self.assertEqual(("POST", "/catalogs"), (method, path))
    self.assertEqual("hive", body["type"])
    self.assertEqual(["ICEBERG"], body["tableFormatList"])
    self.assertEqual({"storage.type": "Hadoop", "hadoop.core.site": "f1", "hadoop.hdfs.site": "f2", "hive.site": "f3"},
                     body["storageConfig"])
    self.assertEqual({"auth.type": "kerberos", "auth.kerberos.principal": "amoro/edge.example.com@EXAMPLE.COM",
                      "auth.kerberos.keytab": "f4", "auth.kerberos.krb5": "f5"}, body["authConfig"])

  def test_registered_catalogs_are_left_alone(self):
    catalogs = load_amoro_catalogs()
    catalogs.existing = ["hive"]
    catalogs.register_catalogs(required=True)
    self.assertEqual([("GET", "/catalogs", None)], catalogs.calls)

  def test_existing_polaris_principal_gets_rotated_credentials(self):
    catalogs = load_amoro_catalogs()
    requests = []

    def polaris_api(method, path, token=None, body=None, form=None, ok=(200, 201, 204)):
      requests.append((method, path))
      if path == "/api/management/v1/principals":
        raise catalogs.PolarisError(409, "exists")
      return {"credentials": {"clientId": "id", "clientSecret": "secret"}}
    catalogs.polaris_api = polaris_api
    self.assertEqual(("id", "secret"), catalogs.polaris_principal_credentials("token"))
    self.assertEqual(("POST", "/api/management/v1/principals/amoro/rotate"), requests[-1])

  def test_ranger_grants_one_policy_per_polaris_level(self):
    catalogs = load_amoro_catalogs()
    granted = []
    catalogs.ensure_ranger_policy = lambda service, name, resources, user, accesses: granted.append((name, resources))
    catalogs.grant_polaris_catalog("token", "ozone")
    self.assertEqual([
      ("amoro-polaris-ozone-catalog", {"root": ["*"], "catalog": ["ozone"]}),
      ("amoro-polaris-ozone-namespace", {"root": ["*"], "catalog": ["ozone"], "namespace": ["*"]}),
      ("amoro-polaris-ozone-table", {"root": ["*"], "catalog": ["ozone"], "namespace": ["*"], "table": ["*"]}),
    ], granted)

  def test_internal_authorization_grants_catalog_manage_content(self):
    catalogs = load_amoro_catalogs(polaris_authorization_type="internal")
    requests = []
    catalogs.polaris_api = lambda method, path, token=None, body=None, form=None, ok=None: requests.append((method, path, body))
    catalogs.grant_polaris_catalog("token", "ozone")
    self.assertIn(("PUT", "/api/management/v1/catalogs/ozone/catalog-roles/amoro/grants",
                   {"grant": {"type": "catalog", "privilege": "CATALOG_MANAGE_CONTENT"}}), requests)
    self.assertEqual(("PUT", "/api/management/v1/principal-roles/amoro/catalog-roles/ozone", {"catalogRole": {"name": "amoro"}}),
                     requests[-1])


class TestAmoroRangerPolicies(unittest.TestCase):

  def ranger(self, responses):
    catalogs = load_amoro_catalogs()
    catalogs.requests = []

    def ranger_api(method, path, body=None):
      catalogs.requests.append((method, path, body))
      for (expected_method, prefix), response in responses:
        if method == expected_method and path.startswith(prefix):
          return response
      raise AssertionError("unexpected {0} {1}".format(method, path))
    catalogs.ranger_api = ranger_api
    return catalogs

  USER = (("GET", "/service/xusers/users"), (200, {"vXUsers": [{"name": "amoro"}]}))

  def test_policy_is_created(self):
    catalogs = self.ranger([self.USER, (("GET", "/service/public/v2/api/service/"), (200, [])),
                            (("POST", "/service/public/v2/api/policy"), (200, {"id": 7}))])
    catalogs.ensure_ranger_policy("c1_hadoop", "amoro-hdfs", {"path": ["/warehouse"]}, "amoro", ["read", "write"])
    body = catalogs.requests[-1][2]
    self.assertEqual({"path": {"values": ["/warehouse"], "isExcludes": False, "isRecursive": False}}, body["resources"])
    self.assertEqual(["amoro"], body["policyItems"][0]["users"])

  def test_grant_joins_the_policy_owning_the_resources(self):
    owner = {"id": 3, "name": "all - path", "policyItems": [{"users": ["hive"], "accesses": [{"type": "read", "isAllowed": True}]}]}
    catalogs = self.ranger([self.USER,
                            (("GET", "/service/public/v2/api/service/c1_hadoop/policy?policyName=amoro-hdfs"), (200, [])),
                            (("POST", "/service/public/v2/api/policy"),
                             (400, {"msgDesc": "Another policy already exists for matching resource: policy-name=[all - path], service=[c1_hadoop]"})),
                            (("GET", "/service/public/v2/api/service/c1_hadoop/policy?policyName=all%20-%20path"), (200, [owner])),
                            (("PUT", "/service/public/v2/api/policy/3"), (200, {}))])
    catalogs.ensure_ranger_policy("c1_hadoop", "amoro-hdfs", {"path": ["/warehouse"]}, "amoro", ["read"])
    method, path, body = catalogs.requests[-1]
    self.assertEqual(("PUT", "/service/public/v2/api/policy/3"), (method, path))
    self.assertEqual([["hive"], ["amoro"]], [item["users"] for item in body["policyItems"]])

  def test_existing_grant_is_not_repeated(self):
    policy = {"id": 3, "name": "amoro-hdfs", "policyItems": [{"users": ["amoro"], "accesses": [
      {"type": "read", "isAllowed": True}, {"type": "write", "isAllowed": True}]}]}
    catalogs = self.ranger([self.USER, (("GET", "/service/public/v2/api/service/"), (200, [policy]))])
    catalogs.ensure_ranger_policy("c1_hadoop", "amoro-hdfs", {"path": ["/warehouse"]}, "amoro", ["read"])
    self.assertNotIn("PUT", [method for method, _, _ in catalogs.requests])


class TestAmoroAccess(unittest.TestCase):

  def access(self, **overrides):
    load_amoro_catalogs()
    params = sys.modules["params"]
    values = dict(default_fs="hdfs://ns1", hive_warehouse_dirs=["/warehouse/tablespace/managed/hive",
                                                                "/warehouse/tablespace/external/hive"],
                  has_polaris_ozone_catalog=False, polaris_catalog_base_location="s3://c1/polaris/",
                  ranger_hdfs_enabled=True, ranger_hdfs_service="c1_hadoop",
                  ranger_ozone_enabled=True, ranger_ozone_service="c1_ozone")
    values.update(overrides)
    for key, value in values.items():
      setattr(params, key, value)
    return load_module("amoro_access", SERVICE / "package/scripts/amoro_access.py")

  def test_storage_locations(self):
    access = self.access()
    self.assertEqual(("hdfs", "/warehouse/x"), access.storage_location("/warehouse/x", "hdfs://ns1"))
    self.assertEqual(("hdfs", "/w"), access.storage_location("hdfs://ns1:8020/w", "ofs://om"))
    self.assertEqual(("ozone", ("vol", "bkt")), access.storage_location("/vol/bkt/warehouse", "ofs://om"))
    self.assertEqual(("ozone", ("vol", "bkt")), access.storage_location("o3fs://bkt.vol.om/warehouse", "hdfs://ns1"))
    self.assertEqual((None, None), access.storage_location("ofs://om/tmp/x", "hdfs://ns1"))

  def test_hdfs_warehouse_is_one_recursive_policy(self):
    self.assertEqual([("c1_hadoop", "amoro-hdfs-warehouse",
                       {"path": ["/warehouse/tablespace/managed/hive", "/warehouse/tablespace/external/hive"]},
                       ["read", "write", "execute"], ("path",))], self.access().access_policies())

  def test_ozone_warehouse_and_polaris_bucket(self):
    policies = self.access(default_fs="ofs://om", hive_warehouse_dirs=["/warehouse/hive/managed"],
                           has_polaris_ozone_catalog=True).access_policies()
    self.assertEqual([("amoro-ozone-s3v", {"volume": ["s3v"]}), ("amoro-ozone-warehouse", {"volume": ["warehouse"]}),
                      ("amoro-ozone-warehouse-hive", {"volume": ["warehouse"], "bucket": ["hive"], "key": ["*"]}),
                      ("amoro-ozone-s3v-c1", {"volume": ["s3v"], "bucket": ["c1"], "key": ["*"]})],
                     [(name, resources) for _, name, resources, _, _ in policies])

  def test_nothing_without_ranger_plugins(self):
    self.assertEqual([], self.access(ranger_hdfs_enabled=False, ranger_ozone_enabled=False,
                                     has_polaris_ozone_catalog=True).access_policies())


class TestAmoroPauseOptimizing(unittest.TestCase):

  def amoro(self, tables):
    import tempfile
    amoro = load_amoro_scripts(amoro_data_dir=tempfile.mkdtemp(), amoro_user="amoro", user_group="hadoop",
                               amoro_optimizer_group="local", amoro_optimizer_parallelism=2, amoro_optimizer_memory=2048)
    amoro.File = lambda path, action="create", **kwargs: open(path, "w").close() if action == "create" else os.remove(path)
    amoro.time.sleep = lambda seconds: None
    amoro.calls = []
    rounds = list(tables)

    def amoro_api(method, path, body=None, timeout=60, raw_body=None, content_type=None):
      amoro.calls.append((method, path.split("?")[0]))
      if "/optimizers" in path and method == "GET":
        return {"list": [] if amoro.released else [{"jobId": "j1", "groupName": "local"}]}
      if method == "DELETE":
        amoro.released = True
      if "/tables?" in path:
        return {"list": rounds.pop(0) if rounds else []}
      if "/optimizing-processes?" in path:
        return {"list": [{"processId": "42"}]}
      return None
    amoro.released = False
    amoro.amoro_api = amoro_api
    return amoro

  TABLE = {"tableIdentifier": {"catalog": "hive", "database": "db", "tableName": "t"}, "optimizeStatus": "minor"}

  def test_pause_releases_optimizers_and_cancels_processes(self):
    amoro = self.amoro([[self.TABLE], []])
    amoro.pause_optimizing()
    self.assertTrue(amoro.is_optimizing_paused())
    self.assertIn(("DELETE", "/optimize/optimizers/j1"), amoro.calls)
    self.assertIn(("POST", "/catalogs/hive/dbs/db/tables/t/optimizing-processes/42/cancel"), amoro.calls)
    amoro.check_optimizing_paused()

  def test_pause_fails_when_processes_stay_active(self):
    amoro = self.amoro([[self.TABLE]] * 3)
    self.assertRaises(amoro.Fail, amoro.pause_optimizing, timeout=-1)

  def test_paused_server_starts_no_optimizer_until_resumed(self):
    amoro = self.amoro([])
    amoro.pause_optimizing()
    amoro.calls = []
    amoro.ensure_optimizer()
    self.assertEqual([], amoro.calls)
    amoro.resume_optimizing()
    self.assertFalse(amoro.is_optimizing_paused())
    self.assertIn(("POST", "/optimize/optimizers"), amoro.calls)
    self.assertRaises(amoro.Fail, amoro.check_optimizing_paused)

  def test_alert_reports_the_paused_flag(self):
    amoro = self.amoro([])
    alert = load_module("alert_optimizing_paused", SERVICE / "package/alerts/alert_optimizing_paused.py")
    configurations = {"{{amoro-env/amoro_data_dir}}": sys.modules["params"].amoro_data_dir}
    self.assertEqual("OK", alert.execute(configurations)[0])
    amoro.pause_optimizing()
    self.assertEqual("WARNING", alert.execute(configurations)[0])


class TestAmoroServiceAdvisor(unittest.TestCase):
  advisor = load_amoro_advisor()

  def services(self, db=None, site=None, env=None, ugsync=None):
    configurations = {
      "amoro-db-properties": {"properties": dict(db or {})},
      "amoro-site": {"properties": dict(site or {})},
      "amoro-env": {"properties": dict(env or {})},
    }
    if ugsync is not None:
      configurations["ranger-ugsync-site"] = {"properties": ugsync}
    return {"services": [{"StackServices": {"service_name": "AMORO"}, "components": []}],
            "configurations": configurations}

  def recommend(self, services):
    configurations = {}
    self.advisor.AmoroRecommender().recommendAmoroConfigurations(configurations, {}, services, {"items": []})
    return configurations

  def test_jdbc_urls_follow_the_flavor(self):
    db = self.recommend(self.services(db={"DB_FLAVOR": "POSTGRES", "db_host": "pg", "db_port": "5432",
                                          "db_name": "amoro"}))["amoro-db-properties"]["properties"]
    self.assertEqual("jdbc:postgresql://pg:5432/amoro", db["amoro.jdbc.url"])
    self.assertEqual("jdbc:postgresql://pg:5432/postgres", db["amoro_privelege_user_jdbc_url"])
    db = self.recommend(self.services(db={"DB_FLAVOR": "MYSQL", "db_host": "my", "db_port": "5432",
                                          "db_name": "amoro"}))["amoro-db-properties"]["properties"]
    self.assertEqual("3306", db["db_port"])
    self.assertEqual("com.mysql.cj.jdbc.Driver", db["amoro.jdbc.driver"])
    self.assertTrue(db["amoro.jdbc.url"].startswith("jdbc:mysql://my:3306/amoro?"))

  def test_ldap_defaults_to_the_ranger_usersync_directory(self):
    ugsync = {"ranger.usersync.source.impl.class": "org.apache.ranger.ldapusersync.process.LdapUserGroupBuilder",
              "ranger.usersync.ldap.url": "ldaps://ipa:636", "ranger.usersync.ldap.user.nameattribute": "uid",
              "ranger.usersync.ldap.user.searchbase": "cn=users,cn=accounts,dc=example,dc=com"}
    site = self.recommend(self.services(ugsync=ugsync))["amoro-site"]["properties"]
    self.assertEqual("ldaps://ipa:636", site["http-server.login-auth-ldap-url"])
    self.assertEqual("uid={0},cn=users,cn=accounts,dc=example,dc=com", site["http-server.login-auth-ldap-user-pattern"])
    site = self.recommend(self.services(site={"http-server.login-auth-ldap-url": "ldap://other"}, ugsync=ugsync))
    self.assertNotIn("http-server.login-auth-ldap-user-pattern", site.get("amoro-site", {}).get("properties", {}))
    self.assertEqual({}, self.recommend(self.services()).get("amoro-site", {}).get("properties", {}))

  def errors(self, method, properties, services):
    items = getattr(self.advisor.AmoroValidator(), method)(properties, {}, services["configurations"], services, {"items": []})
    return sorted((item["config-name"], item["level"]) for item in items)

  def test_admin_password_is_required(self):
    self.assertIn(("admin-password", "ERROR"), self.errors("validateAmoroSiteProperties", {"admin-password": ""}, self.services()))
    self.assertIn(("http-server.login-auth-ldap-user-pattern", "ERROR"), self.errors(
      "validateAmoroSiteProperties", {"admin-password": "x", "http-server.login-auth-ldap-url": "ldap://ipa",
                                      "http-server.login-auth-ldap-user-pattern": "cn=users"}, self.services()))

  def test_database_creation_needs_postgres_and_dba(self):
    self.assertEqual([("create_db_dbuser", "ERROR")], self.errors(
      "validateAmoroDbProperties", {"DB_FLAVOR": "MYSQL", "create_db_dbuser": "true", "db_password": "x"}, self.services()))
    self.assertEqual([("db_root_password", "ERROR")], self.errors(
      "validateAmoroDbProperties", {"DB_FLAVOR": "POSTGRES", "create_db_dbuser": "true", "db_password": "x",
                                    "db_root_user": "postgres", "amoro_privelege_user_jdbc_url": "jdbc:postgresql://pg/postgres"},
      self.services()))
    self.assertEqual([], self.errors(
      "validateAmoroDbProperties", {"DB_FLAVOR": "MYSQL", "create_db_dbuser": "false", "db_password": "x"}, self.services()))


class TestAmoroSecurity(unittest.TestCase):
  advisor = load_amoro_advisor()

  def services(self, kerberos="false", oidc=False, knox=True):
    components = [{"StackServiceComponents": {"service_name": "AMORO", "component_name": "AMORO_SERVER",
                                              "hostnames": ["h1"], "cardinality": "1", "component_category": "MASTER",
                                              "display_name": "Amoro Management Service"}}]
    services = [{"StackServices": {"service_name": "AMORO"}, "components": components}]
    if knox:
      services.append({"StackServices": {"service_name": "KNOX"}, "components": [
        {"StackServiceComponents": {"service_name": "KNOX", "component_name": "KNOX_GATEWAY", "hostnames": ["h1"],
                                    "cardinality": "1+", "component_category": "MASTER",
                                    "display_name": "Knox Gateway"}}]})
    configurations = {"cluster-env": {"properties": {"security_enabled": kerberos}}}
    if oidc:
      configurations["oidc-env"] = {"properties": {"oidc_admin_url": "https://kc:8443", "oidc_realm": "odp"}}
    return {"services": services, "configurations": configurations}

  HOSTS = {"items": [{"Hosts": {"host_name": "h1", "maintenance_state": "OFF"}}]}

  def test_security_modes(self):
    modes = self.advisor.get_amoro_security_modes
    self.assertEqual([], modes({}, self.services()))
    self.assertEqual(["Kerberos", "OIDC"], modes({}, self.services("true", True)))

  def test_tls_is_recommended_and_required(self):
    services = self.services(kerberos="true")
    configurations = {}
    self.advisor.AmoroRecommender().recommendAmoroConfigurations(configurations, {}, services, self.HOSTS)
    self.assertEqual("true", configurations["amoro-site"]["properties"]["http-server.ssl.enabled"])
    items = self.advisor.AmoroValidator().validateAmoroSiteProperties(
      {"admin-password": "x", "http-server.ssl.enabled": "false"}, {}, services["configurations"], services, self.HOSTS)
    self.assertIn(("http-server.ssl.enabled", "ERROR"), [(i["config-name"], i["level"]) for i in items])

  def test_knox_is_required_when_secure(self):
    def knox_errors(services):
      return [item for item in self.advisor.AmoroServiceAdvisor().getServiceComponentLayoutValidations(services, self.HOSTS)
              if "Knox" in item["message"]]
    self.assertEqual(1, len(knox_errors(self.services(oidc=True, knox=False))))
    self.assertEqual([], knox_errors(self.services(oidc=True, knox=True)))
    self.assertEqual([], knox_errors(self.services(knox=False)))


class TestAmoroServiceDefinition(unittest.TestCase):

  def properties(self, site):
    return {p.findtext("name"): p for p in ET.parse(SERVICE / "configuration" / (site + ".xml")).getroot().findall("property")}

  def test_themes_reference_defined_properties(self):
    for theme in ("credentials.json", "database.json"):
      config = json.loads((SERVICE / "themes" / theme).read_text())["configuration"]
      for entry in config["placement"]["configs"] + config.get("widgets", []):
        site, name = entry["config"].split("/")
        if name.startswith("test_"):
          continue
        self.assertIn(name, self.properties(site), entry["config"])

  def test_secrets_are_password_properties(self):
    for site, name in (("amoro-site", "admin-password"), ("amoro-site", "http-server.ssl.keystore-password"),
                       ("amoro-site", "http-server.authorization.ldap-role-mapping.bind-password"),
                       ("amoro-db-properties", "db_password"), ("amoro-db-properties", "db_root_password")):
      self.assertEqual("PASSWORD", self.properties(site)[name].findtext("property-type"), name)

  def test_stack_select_and_conf_select(self):
    packages = json.loads((STACK / "properties/stack_packages.json").read_text())["ODP"]
    self.assertEqual("amoro-server", packages["stack-select"]["AMORO"]["AMORO_SERVER"]["STACK-SELECT-PACKAGE"])
    self.assertEqual([{"conf_dir": "/etc/amoro/conf", "current_dir": "{0}/current/amoro-server/conf"}],
                     packages["conf-select"]["amoro"])

  def test_kerberos_identity_is_published_to_amoro_env(self):
    identity = json.loads((SERVICE / "kerberos.json").read_text())["services"][0]["components"][0]["identities"][0]
    self.assertEqual("amoro-env/amoro_principal", identity["principal"]["configuration"])
    self.assertEqual("amoro-env/amoro_keytab", identity["keytab"]["configuration"])


if __name__ == "__main__":
  unittest.main()
