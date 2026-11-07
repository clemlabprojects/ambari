#!/usr/bin/env python3
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

from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger

from test_tls_security import load_polaris_advisor

Logger.initialize_logger()

SCRIPTS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.3/services/POLARIS/package/scripts"


def load_polaris(**values):
  params = types.ModuleType("params")
  params.has_ozone_service = True
  params.polaris_ozone_catalog_name = "ozone"
  params.polaris_ozone_catalog_base_location = "s3://lakehouse/polaris/"
  params.polaris_ozone_catalog_allowed_locations_list = ["s3://lakehouse/polaris/"]
  params.polaris_ozone_s3_endpoint = "http://s3g:9878"
  params.polaris_ozone_principal_secret = ""
  params.ozone_security_enabled = False
  for key, value in values.items():
    setattr(params, key, value)
  sys.modules["params"] = params
  spec = importlib.util.spec_from_file_location("odp_polaris_scripts", SCRIPTS / "polaris.py")
  module = importlib.util.module_from_spec(spec)
  spec.loader.exec_module(module)
  return module


class TestPolarisOzoneCatalogBootstrap(unittest.TestCase):

  def test_the_action_fails_without_the_s3_secret(self):
    with self.assertRaisesRegex(Fail, "polaris_ozone_principal_secret"):
      load_polaris().bootstrap_ozone_catalog(required=True)

  def test_the_start_only_warns_without_the_s3_secret(self):
    load_polaris().bootstrap_ozone_catalog(required=False)

  def test_the_action_fails_without_the_s3_endpoint(self):
    with self.assertRaisesRegex(Fail, "S3 endpoint not configured"):
      load_polaris(polaris_ozone_s3_endpoint="").bootstrap_ozone_catalog(required=True)


class TestRangerOzonePolicyUser(unittest.TestCase):

  def test_the_policy_names_the_short_user_of_a_kerberos_access_id(self):
    polaris = load_polaris()
    self.assertEqual("polaris", polaris._ranger_user_name("polaris/master03.example.com@EXAMPLE.COM"))
    self.assertEqual("polaris", polaris._ranger_user_name("polaris@EXAMPLE.COM"))
    self.assertEqual("kdps-release", polaris._ranger_user_name("kdps-release"))


class TestSecretsStayOutOfTheTaskLog(unittest.TestCase):
  """resource_management logs the content of a File unless it is a PasswordString."""

  # the SQL statements that create the Polaris database user embed its password
  SECRET_PATTERN = r"password|secret|\bstatement\b"

  def test_secret_file_contents_are_password_strings(self):
    import ast
    import re
    tree = ast.parse((SCRIPTS / "polaris.py").read_text())
    unmasked = []
    for node in ast.walk(tree):
      if isinstance(node, ast.Call) and getattr(node.func, "id", None) == "File":
        for keyword in node.keywords:
          source = ast.unparse(keyword.value)
          if keyword.arg == "content" and re.search(self.SECRET_PATTERN, source.lower()) \
              and not source.startswith("PasswordString("):
            unmasked.append("line {0}: {1}".format(node.lineno, source))
    self.assertEqual([], unmasked)

  def test_the_admin_bootstrap_takes_the_root_secret_from_a_file(self):
    polaris = load_polaris(application_properties={"polaris.persistence.type": "relational-jdbc"},
                           polaris_bootstrap_realms_raw="POLARIS", polaris_admin_username="root",
                           polaris_admin_password="root-s3cr3t", polaris_conf_dir="/etc/polaris/conf",
                           polaris_java_home="/usr/lib/jvm/java-21", polaris_user="polaris", user_group="hadoop",
                           polaris_pid_dir="/var/run/polaris")
    calls = []
    polaris.Execute = lambda command, **kwargs: calls.append(("Execute", command))
    polaris.File = lambda path, **kwargs: calls.append(("File", path, kwargs.get("content"), kwargs.get("action")))
    polaris.Directory = lambda *args, **kwargs: None
    polaris.format = lambda template: template.replace("{polaris_home}", "/usr/odp/current/polaris-server")
    polaris.run_admin_bootstrap()

    written, command, deleted = calls
    self.assertNotIn("root-s3cr3t", command[1])
    self.assertIn("bootstrap -r POLARIS --credentials-file /var/run/polaris/bootstrap-credentials.json", command[1])
    self.assertEqual("[PROTECTED]", repr(written[2]))
    self.assertEqual({"POLARIS": {"client-id": "root", "client-secret": "root-s3cr3t"}}, json.loads(str(written[2])))
    self.assertEqual(("File", "/var/run/polaris/bootstrap-credentials.json", None, "delete"), deleted)

  def test_the_logger_hides_a_password_string(self):
    from resource_management.core.utils import PasswordString
    self.assertEqual("[PROTECTED]", repr(PasswordString("s3-secret")))
    self.assertEqual("s3-secret", str(PasswordString("s3-secret")))


class TestPolarisOzoneSecretValidation(unittest.TestCase):
  advisor = load_polaris_advisor()

  def errors(self, service_names, secret):
    services = {"services": [{"StackServices": {"service_name": name}, "components": []} for name in service_names],
                "configurations": {"polaris-env": {"properties": {"polaris_ozone_principal_secret": secret}}}}
    items = self.advisor.PolarisValidator().validatePolarisEnvProperties(
      services["configurations"]["polaris-env"]["properties"], {}, services["configurations"], services, {"items": []})
    return [item["config-name"] for item in items if item["config-name"] == "polaris_ozone_principal_secret"]

  def test_an_empty_secret_is_left_to_ambari(self):
    # Ambari generates it when the service is installed (OZONE_S3_SECRET).
    self.assertEqual([], self.errors(["POLARIS", "OZONE"], ""))
    self.assertEqual([], self.errors(["POLARIS", "OZONE"], "s3-secret"))
    self.assertEqual([], self.errors(["POLARIS"], ""))

  def test_the_former_default_secret_is_flagged(self):
    self.assertEqual(["polaris_ozone_principal_secret"], self.errors(["POLARIS", "OZONE"], "ozone-engine-secret"))


if __name__ == "__main__":
  unittest.main()
