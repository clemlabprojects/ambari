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

  def test_yaml_round_trip(self):
    try:
      import yaml
    except ImportError:
      self.skipTest("PyYAML is not installed")
    config, amoro = self.config(amoro_admin_ldap_group_dn="cn=admins,dc=x", site={
      "http-server.login-auth-ldap-url": "ldap://ipa:389", "http-server.login-auth-ldap-user-pattern": "uid={0},dc=x",
      "admin-password": "a\"b: c#d"})
    self.assertEqual(json.loads(json.dumps(config)), yaml.safe_load(amoro.to_yaml(config)))


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
