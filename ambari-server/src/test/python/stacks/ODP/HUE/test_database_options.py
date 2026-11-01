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
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET


STACKS = Path(__file__).resolve().parents[5] / "main/resources/stacks"
SERVICE = STACKS / "ODP/1.2/services/HUE"


def load(name, path):
  spec = importlib.util.spec_from_file_location(name, path)
  module = importlib.util.module_from_spec(spec)
  spec.loader.exec_module(module)
  return module


HUE_INI = load("hue_ini", SERVICE / "package/scripts/hue_ini.py")
ADVISOR = load("hue_options_advisor", SERVICE / "service_advisor.py")
OLD_TEMPLATE = "[desktop]\n[[database]]\nengine={{hue_db_engine}}\npassword_script={{hue_runtime_database_password_script}}\n[[smtp]]\nhost=localhost\n"


def template():
  return [p for p in ET.parse(SERVICE / "configuration/hue-ini-template.xml").getroot().findall("property")
          if p.findtext("name") == "content"][0].findtext("value")


class TestHueDatabaseOptions(unittest.TestCase):

  def test_old_templates_get_the_options_line(self):
    content = HUE_INI.with_database_options(OLD_TEMPLATE)
    database = content.split("[[database]]")[1].split("[[smtp]]")[0]
    self.assertIn("options='{{hue_db_options}}'", database)

  def test_an_existing_options_line_is_kept(self):
    manual = OLD_TEMPLATE.replace("[[smtp]]", "options='{\"sslmode\": \"require\"}'\n[[smtp]]")
    self.assertEqual(manual, HUE_INI.with_database_options(manual))

  def test_the_stack_template_renders_the_options(self):
    content = template()
    self.assertEqual(content, HUE_INI.with_database_options(content))
    package = Path(__file__).resolve().parents[7] / "ambari-common/src/main/python/ambari_jinja2/ambari_jinja2"
    spec = importlib.util.spec_from_file_location("ambari_jinja2", package / "__init__.py",
                                                  submodule_search_locations=[str(package)])
    jinja = importlib.util.module_from_spec(spec)
    sys.modules["ambari_jinja2"] = jinja
    spec.loader.exec_module(jinja)
    Environment = jinja.Environment
    options = '{"sslmode": "verify-full", "sslrootcert": "/etc/security/dbssl/db-ca.crt"}'
    rendered = Environment().from_string(content).render(hue_db_options=options)
    database = rendered.split("[[database]]")[1].split("[[")[0]
    self.assertIn("options='" + options + "'", database)
    self.assertNotIn("options=", Environment().from_string(content).render(hue_db_options="").split("[[database]]")[1].split("[[")[0])

  def test_options_must_be_a_json_object(self):
    validator = ADVISOR.HueValidator()
    def errors(value):
      return [i["config-name"] for i in validator.validateHueIniConfConfigurations({"hue_db_options": value}, {}, {}, {}, {})]
    self.assertEqual([], errors('{"sslmode": "verify-full"}'))
    self.assertEqual([], errors(""))
    self.assertEqual(["hue_db_options"], errors("sslmode=verify-full"))
    self.assertEqual(["hue_db_options"], errors('["sslmode"]'))


if __name__ == "__main__":
  unittest.main()
