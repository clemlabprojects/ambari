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

  def test_tls_is_recommended_with_kerberos(self):
    services = self.services("true", "internal")
    configurations = {}
    self.advisor.PolarisRecommender().recommendPolarisTlsConfigurations(configurations, services)
    env = configurations["polaris-env"]["properties"]
    self.assertEqual(("true", "true", "true"), (env["polaris_ssl_enabled"], env["polaris_mcp_tls_enabled"], env["polaris_console_tls_enabled"]))
    self.assertEqual("https", env["polaris_protocol"])

  def test_tls_material_is_not_recommended(self):
    # until auto-TLS, the operator enters the stores, PEM files and passwords
    services = self.services("true", "internal")
    configurations = {}
    self.advisor.PolarisRecommender().recommendPolarisTlsConfigurations(configurations, services)
    recommended = dict(configurations["polaris-env"]["properties"],
                       **configurations.get("polaris-application-properties", {}).get("properties", {}))
    for name in ("polaris_ssl_keystore_password", "polaris_ssl_truststore_password", "polaris_ssl_key_password",
                 "polaris_mcp_tls_cert_file", "polaris_mcp_tls_key_file", "polaris_console_tls_cert_file",
                 "polaris_console_tls_key_file", "quarkus.http.ssl.certificate.key-store-file",
                 "quarkus.http.ssl.certificate.trust-store-file"):
      self.assertNotIn(name, recommended)

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
    self.assertEqual(sorted(list(self.TLS_MATERIAL) + ["quarkus.http.ssl.certificate.key-store-file",
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


if __name__ == "__main__":
  unittest.main()
