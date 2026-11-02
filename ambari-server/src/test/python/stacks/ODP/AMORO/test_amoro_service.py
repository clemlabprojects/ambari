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
  params.amoro_optimizer_container = "local"
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


def _test_keytab():
  import tempfile
  handle = tempfile.NamedTemporaryFile(prefix="amoro-test-", suffix=".keytab", delete=False)
  handle.write(b"\x05\x02test")
  handle.close()
  return handle.name


TEST_KEYTAB = _test_keytab()


def load_amoro_catalogs(**overrides):
  """amoro_catalogs.py against a params module, with the Amoro REST API recorded instead of called."""
  amoro = load_amoro_scripts()
  params = sys.modules["params"]
  defaults = dict(
    has_hive=True, has_polaris_ozone_catalog=False, amoro_hive_catalog_name="hive", polaris_catalog_name="ozone",
    hadoop_conf_dir="/etc/hadoop/conf", hive_conf_dir="/etc/hive/conf", security_enabled=True,
    amoro_principal="amoro/edge.example.com@EXAMPLE.COM", amoro_keytab=TEST_KEYTAB,
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
    catalogs = load_amoro_catalogs(security_enabled=False)
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

  def test_ozone_s3_secret_is_generated_and_set(self):
    catalogs = load_amoro_catalogs(ozone_security_enabled=True)
    commands = []

    def run_ozone(args, secret=False):
      commands.append((list(args), secret))
      return "export AWS_ACCESS_KEY_ID='amoro/edge.example.com@EXAMPLE.COM'" if args[1] == "getsecret" else ""
    catalogs.run_ozone = run_ozone
    access_key, secret = catalogs.ozone_s3_credential()
    self.assertEqual("amoro/edge.example.com@EXAMPLE.COM", access_key)
    self.assertGreaterEqual(len(secret), 40)
    self.assertEqual((["s3", "setsecret", "-s", secret, "-e"], True), commands[-1])
    self.assertTrue(all(quiet for _, quiet in commands))
    self.assertNotEqual(secret, catalogs.ozone_s3_credential()[1])

  def test_ozone_without_security_needs_no_secret_on_ozone(self):
    catalogs = load_amoro_catalogs(ozone_security_enabled=False)
    catalogs.run_ozone = lambda args, secret=False: self.fail("no ozone command expected")
    self.assertEqual("amoro", catalogs.ozone_s3_credential()[0])

  def test_polaris_auth_uses_the_ambari_managed_oidc_client(self):
    catalogs = load_amoro_catalogs(amoro_polaris_oidc=True, amoro_oidc_client_id="c1-amoro",
                                   amoro_oidc_client_secret="s3cr3t", amoro_oidc_auth_server_url="https://kc/realms/odp")
    catalogs.oidc_token_endpoint = lambda: "https://kc/realms/odp/protocol/openid-connect/token"
    catalogs.polaris_principal_credentials = lambda token: self.fail("no Polaris-issued credential with OIDC")
    self.assertEqual({"credential": "c1-amoro:s3cr3t", "scope": "openid",
                      "oauth2-server-uri": "https://kc/realms/odp/protocol/openid-connect/token"},
                     catalogs.polaris_catalog_auth("token"))

  def test_polaris_auth_falls_back_to_a_polaris_issued_credential(self):
    catalogs = load_amoro_catalogs(amoro_polaris_oidc=False)
    catalogs.polaris_principal_credentials = lambda token: ("id", "secret")
    self.assertEqual({"credential": "id:secret", "scope": "PRINCIPAL_ROLE:ALL",
                      "oauth2-server-uri": "https://polaris:8443/api/catalog/v1/oauth/tokens"},
                     catalogs.polaris_catalog_auth("token"))

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


class TestAmoroCatalogCredentialRefresh(unittest.TestCase):

  def catalogs(self, detail, **overrides):
    import tempfile
    keytab = tempfile.NamedTemporaryFile(delete=False)
    keytab.write(b"\x05\x02keytab-v1"); keytab.close()
    catalogs = load_amoro_catalogs(amoro_keytab=keytab.name, **overrides)
    catalogs.upload_file = lambda path: "id-" + os.path.basename(path)
    catalogs.puts = []

    def amoro_api(method, path, body=None, timeout=60, raw_body=None, content_type=None):
      if method == "GET" and path == "/catalogs":
        return [{"catalogName": detail["name"]}]
      if method == "GET":
        return json.loads(json.dumps(detail))
      catalogs.puts.append((method, path, body))
    catalogs.amoro.amoro_api = amoro_api
    return catalogs, keytab.name

  HIVE = {"name": "hive", "type": "hive", "optimizerGroup": "local", "tableFormatList": ["ICEBERG"], "tableProperties": {},
          "properties": {}, "storageConfig": {"storage.type": "Hadoop", "hadoop.core.site": {"fileName": "core-site.xml"}},
          "authConfig": {"auth.type": "KERBEROS"}}

  def test_unchanged_keytab_leaves_the_catalog_alone(self):
    catalogs, _ = self.catalogs(dict(self.HIVE))
    detail = dict(self.HIVE, properties={catalogs.FINGERPRINT: catalogs.hive_credential_fingerprint()})
    catalogs, _ = self.catalogs(detail)
    catalogs.register_catalogs(required=True)
    self.assertEqual([], catalogs.puts)

  def test_regenerated_keytab_is_uploaded_again(self):
    catalogs, keytab = self.catalogs(dict(self.HIVE, properties={"ambari.credential-fingerprint": "old", "x": "1"}))
    catalogs.register_catalogs(required=True)
    method, path, body = catalogs.puts[0]
    self.assertEqual(("PUT", "/catalogs/hive"), (method, path))
    self.assertEqual({"storage.type": "Hadoop"}, body["storageConfig"])
    self.assertEqual("id-" + os.path.basename(keytab), body["authConfig"]["auth.kerberos.keytab"])
    self.assertEqual("1", body["properties"]["x"])
    self.assertEqual(catalogs.hive_credential_fingerprint(), body["properties"][catalogs.FINGERPRINT])

  POLARIS = {"name": "ozone", "type": "rest", "optimizerGroup": "local", "tableFormatList": ["ICEBERG"], "tableProperties": {},
             "properties": {"credential": "********", "uri": "https://polaris:8443/api/catalog", "ambari.credential-fingerprint": "old"},
             "storageConfig": {"storage.type": "S3", "storage.s3.endpoint": "http://s3g:9878", "storage.s3.region": "us-east-1"},
             "authConfig": {"auth.type": "AK/SK", "auth.ak_sk.access_key": "amoro", "auth.ak_sk.secret_key": "********"}}

  def test_new_oidc_secret_updates_the_polaris_catalog(self):
    catalogs, _ = self.catalogs(dict(self.POLARIS), has_hive=False, has_polaris_ozone_catalog=True, security_enabled=False,
                                amoro_polaris_oidc=True, amoro_oidc_client_id="c1-amoro", amoro_oidc_client_secret="new")
    catalogs.oidc_token_endpoint = lambda: "https://kc/token"
    catalogs.register_catalogs(required=True)
    body = catalogs.puts[0][2]
    self.assertEqual("c1-amoro:new", body["properties"]["credential"])
    self.assertEqual("https://polaris:8443/api/catalog", body["properties"]["uri"])
    self.assertEqual("********", body["authConfig"]["auth.ak_sk.secret_key"])
    self.assertEqual(self.POLARIS["storageConfig"], body["storageConfig"])

  def test_polaris_issued_credential_is_not_refreshed(self):
    catalogs, _ = self.catalogs(dict(self.POLARIS), has_hive=False, has_polaris_ozone_catalog=True, amoro_polaris_oidc=False)
    catalogs.register_catalogs(required=True)
    self.assertEqual([], catalogs.puts)


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


class TestAmoroOzoneFilesystem(unittest.TestCase):

  def setup(self, *jars):
    import tempfile
    lib = tempfile.mkdtemp()
    for jar in jars:
      open(os.path.join(lib, jar), "w").close()
    amoro = load_amoro_scripts(ozone_client_lib_dir=lib, amoro_home="/usr/odp/current/amoro-server")
    amoro.commands = []
    amoro.Execute = lambda command, **kwargs: amoro.commands.append(command)
    amoro.setup_ozone_filesystem()
    return amoro, lib

  def test_the_shaded_ozone_filesystem_goes_into_lib(self):
    amoro, lib = self.setup("ozone-filesystem-hadoop3-2.1.0.jar", "ozone-filesystem-hadoop3-client-2.1.0.jar")
    target = "/usr/odp/current/amoro-server/lib/ozone-filesystem-hadoop3-client-2.1.0.jar"
    self.assertEqual([("cp", "--remove-destination", os.path.join(lib, "ozone-filesystem-hadoop3-client-2.1.0.jar"), target),
                      ("chmod", "644", target)], amoro.commands)

  def test_nothing_without_the_ozone_client(self):
    amoro, _ = self.setup()
    self.assertEqual([], amoro.commands)


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


class TestAmoroOptimizerRestart(unittest.TestCase):
  """After a restart, Amoro lists the optimizers of its database until their heartbeat timeout."""

  def amoro(self, rounds):
    import tempfile
    amoro = load_amoro_scripts(amoro_data_dir=tempfile.mkdtemp(), amoro_optimizer_group="local",
                               amoro_optimizer_parallelism=2)
    amoro.time.sleep = lambda seconds: None
    amoro.calls = []
    listed = list(rounds)

    def amoro_api(method, path, body=None, timeout=60, raw_body=None, content_type=None):
      amoro.calls.append((method, path.split("?")[0]))
      if method == "GET" and "/optimizers" in path:
        return {"list": listed.pop(0) if len(listed) > 1 else listed[0]}
      return None
    amoro.amoro_api = amoro_api
    return amoro

  def started(self, amoro):
    return [call for call in amoro.calls if call == ("POST", "/optimize/optimizers")]

  def test_a_dead_optimizer_is_replaced_once_amoro_forgets_it(self):
    dead = [{"jobId": "j1", "touchTime": 1000}]
    amoro = self.amoro([dead, dead, []])
    amoro.ensure_optimizer(since=5000)
    self.assertEqual(1, len(self.started(amoro)))

  def test_an_optimizer_sending_heartbeats_is_kept(self):
    amoro = self.amoro([[{"jobId": "j1", "touchTime": 1000}], [{"jobId": "j1", "touchTime": 6000}]])
    amoro.ensure_optimizer(since=5000)
    self.assertEqual([], self.started(amoro))

  def test_an_optimizer_is_started_when_the_dead_one_does_not_expire(self):
    amoro = self.amoro([[{"jobId": "j1", "touchTime": 1000}]])
    amoro.ensure_optimizer(since=5000, timeout=-1)
    self.assertEqual(1, len(self.started(amoro)))

  def test_stop_releases_the_optimizers(self):
    amoro = self.amoro([[{"jobId": "j1"}, {"jobId": "j2"}]])
    amoro.release_optimizers()
    self.assertEqual([("DELETE", "/optimize/optimizers/j1"), ("DELETE", "/optimize/optimizers/j2")],
                     [call for call in amoro.calls if call[0] == "DELETE"])


class TestAmoroOptimizersOnYarn(unittest.TestCase):
  advisor = load_amoro_advisor()

  SPARK = dict(amoro_optimizer_container="spark", spark_home="/usr/odp/current/spark3-client",
               amoro_home="/usr/odp/current/amoro-server", hadoop_conf_dir="/etc/hadoop/conf", amoro_user="amoro",
               amoro_optimizer_yarn_queue="optimize", amoro_optimizer_memory=2048, amoro_optimizer_parallelism=4,
               amoro_optimizer_spark_driver_memory=1024, security_enabled=False)

  def services(self, names, env=None, changed=None):
    services = [{"StackServices": {"service_name": name}, "components": []} for name in names]
    services.insert(0, {"StackServices": {"service_name": "AMORO"}, "components": [
      {"StackServiceComponents": {"component_name": "AMORO_SERVER", "hostnames": ["ams.example.com"]}}]})
    return {"services": services, "changed-configurations": changed or [],
            "configurations": {"amoro-env": {"properties": dict(env or {})}}}

  def recommend(self, services):
    configurations = {}
    hosts = {"items": [{"Hosts": {"host_name": "ams.example.com"}}]}
    self.advisor.AmoroRecommender().recommendAmoroOptimizerContainer(configurations, services, hosts)
    return configurations

  def test_yarn_and_spark3_run_the_optimizers_on_yarn(self):
    configurations = self.recommend(self.services(["YARN", "SPARK3"]))
    self.assertEqual("spark", configurations["amoro-env"]["properties"]["amoro_optimizer_container"])
    self.assertEqual({"hadoop.proxyuser.amoro.hosts": "ams.example.com", "hadoop.proxyuser.amoro.users": "amoro"},
                     configurations["core-site"]["properties"])

  def test_without_spark3_the_optimizers_stay_local(self):
    configurations = self.recommend(self.services(["YARN"], env={"amoro_optimizer_container": "spark"}))
    self.assertEqual("local", configurations["amoro-env"]["properties"]["amoro_optimizer_container"])
    self.assertNotIn("core-site", configurations)

  def test_the_operator_keeps_the_local_container(self):
    changed = [{"type": "amoro-env", "name": "amoro_optimizer_container", "old_value": "spark"}]
    configurations = self.recommend(self.services(["YARN", "SPARK3"], env={"amoro_optimizer_container": "local"},
                                                  changed=changed))
    self.assertEqual("local", configurations["amoro-env"]["properties"]["amoro_optimizer_container"])

  def test_spark_container_needs_yarn_and_spark3(self):
    def errors(names):
      services = self.services(names)
      items = self.advisor.AmoroValidator().validateAmoroEnvProperties(
        {"amoro_optimizer_container": "spark"}, {}, services["configurations"], services, {"items": []})
      return [item["config-name"] for item in items if item["config-name"] == "amoro_optimizer_container"]
    self.assertEqual(["amoro_optimizer_container"], errors(["YARN"]))
    self.assertEqual([], errors(["YARN", "SPARK3"]))

  def test_spark_container_submits_to_yarn(self):
    amoro = load_amoro_scripts(**self.SPARK)
    containers = {c["name"]: c for c in amoro.amoro_config()["containers"]}
    self.assertEqual(["localContainer", "sparkContainer"], sorted(containers))
    spark = containers["sparkContainer"]
    self.assertEqual("org.apache.amoro.server.manager.SparkOptimizerContainer", spark["container-impl"])
    properties = spark["properties"]
    self.assertEqual(("yarn", "cluster"), (properties["master"], properties["deploy-mode"]))
    self.assertEqual("/usr/odp/current/amoro-server/plugin/optimizer/spark/optimizer-job.jar", properties["job-uri"])
    self.assertEqual("amoro", properties["export.HADOOP_USER_NAME"])
    self.assertEqual("optimize", properties["spark-conf.spark.yarn.queue"])
    self.assertNotIn("spark-conf.spark.kerberos.keytab", properties)
    self.assertEqual({"memory": "2048", "spark-conf.spark.executor.cores": "1", "spark-conf.spark.executor.memory": "2048m",
                      "spark-conf.spark.driver.memory": "1024m", "spark-conf.spark.dynamicAllocation.maxExecutors": "4"},
                     amoro.optimizer_group_properties())

  def test_kerberos_submits_with_the_amoro_keytab(self):
    amoro = load_amoro_scripts(**dict(self.SPARK, security_enabled=True, amoro_principal="amoro/ams@EXAMPLE.COM",
                                      amoro_keytab="/etc/security/keytabs/amoro.service.keytab"))
    properties = amoro.spark_container()["properties"]
    self.assertEqual("amoro/ams@EXAMPLE.COM", properties["spark-conf.spark.kerberos.principal"])
    self.assertEqual("/etc/security/keytabs/amoro.service.keytab", properties["spark-conf.spark.kerberos.keytab"])

  def test_local_container_only_without_spark(self):
    amoro = load_amoro_scripts(amoro_optimizer_memory=2048)
    self.assertEqual(["localContainer"], [c["name"] for c in amoro.amoro_config()["containers"]])
    self.assertEqual({"memory": "2048"}, amoro.optimizer_group_properties())


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

  def test_kerberos_without_oidc_warns_about_the_polaris_credential(self):
    def warnings(services):
      services["services"] += [{"StackServices": {"service_name": name}, "components": []} for name in ("POLARIS", "OZONE")]
      items = self.advisor.AmoroValidator().validateAmoroEnvProperties({}, {}, services["configurations"], services, self.HOSTS)
      return [item["config-name"] for item in items if item["config-name"] == "amoro_oidc_client_id"]
    self.assertEqual(["amoro_oidc_client_id"], warnings(self.services(kerberos="true")))
    self.assertEqual([], warnings(self.services(kerberos="true", oidc=True)))
    self.assertEqual([], warnings(self.services()))

  def test_heap_size_saved_with_the_mb_suffix_is_valid(self):
    def errors(heapsize):
      services = self.services()
      items = self.advisor.AmoroValidator().validateAmoroEnvProperties(
        {"amoro_server_heapsize": heapsize}, {}, services["configurations"], services, self.HOSTS)
      return [item["config-name"] for item in items if item["config-name"] == "amoro_server_heapsize"]
    self.assertEqual([], errors("2048"))
    self.assertEqual([], errors("2048m"))
    self.assertEqual(["amoro_server_heapsize"], errors("lots"))
    self.assertEqual(["amoro_server_heapsize"], errors("0m"))

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

  def test_oidc_client_maps_to_the_amoro_user(self):
    client = json.loads((SERVICE / "oidc.json").read_text())["services"][0]["clients"][0]
    self.assertFalse(client["public_client"])
    self.assertTrue(client["service_accounts_enabled"])
    claim = [m for m in client["protocol_mappers"] if m["protocolMapper"] == "oidc-hardcoded-claim-mapper"][0]["config"]
    self.assertEqual(("polaris.principal_name", "${amoro-env/amoro_user}"), (claim["claim.name"], claim["claim.value"]))
    env = self.properties("amoro-env")
    for name in client["configurations"][0]["amoro-env"]:
      self.assertIn(name, env)
    self.assertEqual("PASSWORD", env["amoro_oidc_client_secret"].findtext("property-type"))

  def test_kerberos_identity_is_published_to_amoro_env(self):
    identity = json.loads((SERVICE / "kerberos.json").read_text())["services"][0]["components"][0]["identities"][0]
    self.assertEqual("amoro-env/amoro_principal", identity["principal"]["configuration"])
    self.assertEqual("amoro-env/amoro_keytab", identity["keytab"]["configuration"])


if __name__ == "__main__":
  unittest.main()
