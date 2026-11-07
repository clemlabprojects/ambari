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
import sys
from pathlib import Path
import unittest
from xml.etree import ElementTree


RESOURCES = Path(__file__).resolve().parents[5] / "main/resources"


def load_polaris_advisor():
  for name, path in (("ambari_configuration", "stacks/ambari_configuration.py"), ("stack_advisor", "stacks/stack_advisor.py")):
    spec = importlib.util.spec_from_file_location(name, RESOURCES / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    sys.modules[name] = module
  spec = importlib.util.spec_from_file_location("odp_polaris_service_advisor", RESOURCES / "stacks/ODP/1.3/services/POLARIS/service_advisor.py")
  module = importlib.util.module_from_spec(spec)
  spec.loader.exec_module(module)
  return module


class TestPolarisTlsSecurity(unittest.TestCase):
  advisor = load_polaris_advisor()

  def services(self, security_enabled, auth_type, polaris_env=None):
    components = [{"StackServiceComponents": {"component_name": name, "hostnames": ["h1"]}}
                  for name in ("POLARIS_SERVER", "POLARIS_MCP_SERVER", "POLARIS_CONSOLE")]
    return {
      "services": [{"StackServices": {"service_name": "POLARIS"}, "components": components}],
      "configurations": {
        "cluster-env": {"properties": {"security_enabled": security_enabled}},
        "polaris-application-properties": {"properties": {"polaris.authentication.type": auth_type}},
        "polaris-env": {"properties": dict(polaris_env or {"polaris_ssl_enabled": "false", "polaris_mcp_tls_enabled": "false",
                                                           "polaris_console_tls_enabled": "false"})},
      },
    }

  def tls_errors(self, services):
    properties = services["configurations"]["polaris-env"]["properties"]
    items = self.advisor.PolarisValidator().validatePolarisEnvProperties(properties, {}, services["configurations"], services,
                                                                       {"items": [{"Hosts": {"host_name": "h1"}}]})
    return sorted(item["config-name"] for item in items
                  if item["level"] == "ERROR" and item["config-name"].endswith(("ssl_enabled", "tls_enabled")))

  def test_security_modes(self):
    modes = self.advisor.get_polaris_security_modes
    self.assertEqual([], modes({}, self.services("false", "internal")))
    self.assertEqual(["Kerberos"], modes({}, self.services("true", "internal")))
    self.assertEqual(["OIDC"], modes({}, self.services("false", "mixed")))
    self.assertEqual(["Kerberos", "OIDC"], modes({}, self.services("true", "external")))

  def test_tls_is_required_with_kerberos_or_oidc(self):
    expected = ["polaris_console_tls_enabled", "polaris_mcp_tls_enabled", "polaris_ssl_enabled"]
    self.assertEqual(expected, self.tls_errors(self.services("true", "internal")))
    self.assertEqual(expected, self.tls_errors(self.services("false", "mixed")))
    self.assertEqual([], self.tls_errors(self.services("false", "internal")))
    self.assertEqual([], self.tls_errors(self.services("true", "mixed", {"polaris_ssl_enabled": "true", "polaris_protocol": "https",
                                                                         "polaris_mcp_tls_enabled": "true",
                                                                         "polaris_console_tls_enabled": "true"})))

  def test_tls_stays_off_with_kerberos(self):
    # a trial needs no TLS material: the advisor leaves TLS off, the validator reports it as an error
    services = self.services("true", "internal")
    configurations = {}
    self.advisor.PolarisRecommender().recommendPolarisTlsConfigurations(configurations, services)
    env = dict(services["configurations"]["polaris-env"]["properties"], **configurations["polaris-env"]["properties"])
    self.assertEqual(("false", "false", "false"), (env.get("polaris_ssl_enabled", "false"), env.get("polaris_mcp_tls_enabled", "false"),
                                                   env.get("polaris_console_tls_enabled", "false")))
    self.assertNotEqual("https", env.get("polaris_protocol"))
    self.assertEqual(["polaris_console_tls_enabled", "polaris_mcp_tls_enabled", "polaris_ssl_enabled"], self.tls_errors(services))

  def test_tls_material_defaults_to_the_cluster_stores(self):
    defaults = {}
    for site in ("polaris-env", "polaris-application-properties", "ranger-polaris-policymgr-ssl"):
      root = ElementTree.parse(str(RESOURCES / "stacks/ODP/1.3/services/POLARIS/configuration" / (site + ".xml"))).getroot()
      for prop in root.findall("property"):
        defaults[prop.findtext("name")] = (prop.findtext("value") or "", prop.findtext("value-attributes/empty-value-valid"))
    for name, value in (("quarkus.http.ssl.certificate.key-store-file", "/etc/security/serverKeys/keystore.jks"),
                        ("quarkus.http.ssl.certificate.trust-store-file", "/etc/security/serverKeys/truststore.jks"),
                        ("polaris_ssl_keystore_password", "changeit"), ("polaris_ssl_truststore_password", "changeit"),
                        ("polaris_ssl_key_password", ""),
                        ("polaris_mcp_tls_cert_file", "/etc/security/serverKeys/certificate.cert.pem"),
                        ("polaris_mcp_tls_key_file", "/etc/security/serverKeys/certificate.key.pem"),
                        ("polaris_console_tls_cert_file", "/etc/security/serverKeys/certificate.cert.pem"),
                        ("polaris_console_tls_key_file", "/etc/security/serverKeys/certificate.key.pem"),
                        ("xasecure.policymgr.clientssl.truststore", "/etc/security/serverKeys/truststore.jks"),
                        ("xasecure.policymgr.clientssl.truststore.password", "changeit")):
      # never required by the definitions: Ambari cannot hide the TLS material when TLS is off
      self.assertEqual((value, "true"), defaults[name], name)

  TLS_ON = {"polaris_ssl_enabled": "true", "polaris_protocol": "https", "polaris_mcp_tls_enabled": "true",
            "polaris_console_tls_enabled": "true"}
  TLS_MATERIAL = {"polaris_ssl_keystore_password": "ks", "polaris_ssl_key_password": "key",
                  "polaris_ssl_truststore_password": "ts", "polaris_mcp_tls_cert_file": "/tls/mcp.pem",
                  "polaris_mcp_tls_key_file": "/tls/mcp.key", "polaris_console_tls_cert_file": "/tls/console.pem",
                  "polaris_console_tls_key_file": "/tls/console.key"}

  def material_errors(self, polaris_env, app_props):
    services = self.services("true", "internal", polaris_env)
    services["configurations"]["polaris-application-properties"]["properties"].update(app_props)
    validator = self.advisor.PolarisValidator()
    hosts = {"items": [{"Hosts": {"host_name": "h1"}}]}
    items = validator.validatePolarisEnvProperties(polaris_env, {}, services["configurations"], services, hosts)
    items += validator.validatePolarisApplicationProperties(
      services["configurations"]["polaris-application-properties"]["properties"], {}, services["configurations"], services, hosts)
    return sorted(item["config-name"] for item in items if item["level"] == "ERROR" and "enter" in item["message"])

  def test_tls_material_is_required_when_tls_is_enabled(self):
    # without a key password Quarkus uses the keystore password
    self.assertEqual(sorted([name for name in self.TLS_MATERIAL if name != "polaris_ssl_key_password"] + ["quarkus.http.ssl.certificate.key-store-file",
                                                       "quarkus.http.ssl.certificate.trust-store-file"]),
                     self.material_errors(dict(self.TLS_ON), {}))
    self.assertEqual([], self.material_errors(dict(self.TLS_ON, **self.TLS_MATERIAL),
                                              {"quarkus.http.ssl.certificate.key-store-file": "/tls/polaris.jks",
                                               "quarkus.http.ssl.certificate.trust-store-file": "/tls/truststore.jks"}))
    self.assertEqual([], self.material_errors({"polaris_ssl_enabled": "false", "polaris_mcp_tls_enabled": "false",
                                               "polaris_console_tls_enabled": "false"}, {}))


  def ranger_ssl_errors(self, ssl_props, url="https://ranger:6182", plugin="Yes"):
    services = self.services("true", "internal")
    services["configurations"]["admin-properties"] = {"properties": {"policymgr_external_url": url}}
    services["configurations"]["ranger-polaris-plugin-properties"] = {"properties": {"ranger-polaris-plugin-enabled": plugin}}
    items = self.advisor.PolarisValidator().validateRangerPolarisPolicymgrSsl(ssl_props, {}, services["configurations"], services,
                                                                             {"items": [{"Hosts": {"host_name": "h1"}}]})
    return sorted(item["config-name"] for item in items if item["level"] == "ERROR")

  def test_ranger_plugin_tls_stores_are_entered_by_the_operator(self):
    truststore = {"xasecure.policymgr.clientssl.truststore": "/tls/truststore.jks",
                  "xasecure.policymgr.clientssl.truststore.password": "ts"}
    self.assertEqual(["xasecure.policymgr.clientssl.truststore", "xasecure.policymgr.clientssl.truststore.password"],
                     self.ranger_ssl_errors({}))
    # no client keystore: one-way TLS
    self.assertEqual([], self.ranger_ssl_errors(dict(truststore)))
    self.assertEqual(["xasecure.policymgr.clientssl.keystore.password"],
                     self.ranger_ssl_errors(dict(truststore, **{"xasecure.policymgr.clientssl.keystore": "/tls/client.jks"})))
    self.assertEqual([], self.ranger_ssl_errors({}, url="http://ranger:6080"))
    self.assertEqual([], self.ranger_ssl_errors({}, plugin="No"))


  def test_database_setup_requires_the_administrator_password(self):
    def errors(props):
      services = self.services("false", "internal")
      items = self.advisor.PolarisValidator().validatePolarisDbProperties(props, {}, services["configurations"], services,
                                                                          {"items": [{"Hosts": {"host_name": "h1"}}]})
      return [item["config-name"] for item in items if item["level"] == "ERROR"]
    self.assertEqual(["db_root_password"], errors({"create_db_dbuser": "true", "db_root_user": "postgres"}))
    self.assertEqual([], errors({"create_db_dbuser": "true", "db_root_user": "postgres", "db_root_password": "x"}))
    self.assertEqual([], errors({"create_db_dbuser": "false"}))


if __name__ == "__main__":
  unittest.main()
