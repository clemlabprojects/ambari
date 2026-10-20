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
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


STACKS = Path(__file__).resolve().parents[5] / "main/resources/stacks"
SERVICE = STACKS / "ODP/1.2/services/HUE"
SPEC = importlib.util.spec_from_file_location("hue_database_advisor", SERVICE / "service_advisor.py")
ADVISOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ADVISOR)


class TestHueDatabaseWizard(unittest.TestCase):
  def recommendations(self, flavor="POSTGRES", host="database.example.com", port="5544"):
    ini = {"hue_db_host": host, "hue_db_name": "hue_test"}
    if port is not None:
      ini["hue_db_port"] = port
    services = {
      "services": [{"StackServices": {"service_name": "HUE"}, "components": [
        {"StackServiceComponents": {"component_name": "HUE_SERVER", "hostnames": ["hue.example.com"]}}
      ]}],
      "configurations": {"hue-env": {"properties": {"hue_database": flavor}},
                         "hue-ini-conf": {"properties": ini}}
    }
    result = {}
    ADVISOR.HueRecommender().recommendHueConfigurationsFromODP12(result, {}, services, {})
    self.assertNotIn("hue_database", result["hue-env"]["properties"])
    self.assertEqual(flavor, services["configurations"]["hue-env"]["properties"]["hue_database"])
    return result

  def test_postgres_uses_database_host_name_and_port(self):
    result = self.recommendations()
    self.assertEqual("jdbc:postgresql://database.example.com:5544/hue_test",
                     result["hue-ini-conf"]["properties"]["hue.jpa.jdbc.url"])
    self.assertEqual("org.postgresql.Driver", result["hue-ini-conf"]["properties"]["hue.jpa.jdbc.driver"])
    self.assertEqual("jdbc:postgresql://database.example.com:5544/postgres",
                     result["hue-env"]["properties"]["hue_privelege_user_jdbc_url"])

  def test_mysql_is_still_supported(self):
    props = self.recommendations(flavor="MYSQL", port="3307")["hue-ini-conf"]["properties"]
    self.assertEqual("jdbc:mysql://database.example.com:3307/hue_test", props["hue.jpa.jdbc.url"])
    self.assertEqual("com.mysql.jdbc.Driver", props["hue.jpa.jdbc.driver"])

  def test_oracle_is_still_supported(self):
    props = self.recommendations(flavor="ORACLE", port="1522")["hue-ini-conf"]["properties"]
    self.assertEqual("jdbc:oracle:thin:@//database.example.com:1522/hue_test", props["hue.jpa.jdbc.url"])
    self.assertEqual("oracle.jdbc.driver.OracleDriver", props["hue.jpa.jdbc.driver"])

  def test_embedded_port_is_not_duplicated(self):
    props = self.recommendations(host="database.example.com:5545")["hue-ini-conf"]["properties"]
    self.assertEqual("jdbc:postgresql://database.example.com:5545/hue_test", props["hue.jpa.jdbc.url"])

  def test_legacy_configs_without_port_keep_default_ports(self):
    for flavor, port in (("POSTGRES", "5432"), ("MYSQL", "3306"), ("ORACLE", "1521")):
      with self.subTest(flavor=flavor):
        props = self.recommendations(flavor=flavor, port=None)["hue-ini-conf"]["properties"]
        self.assertIn("database.example.com:" + port, props["hue.jpa.jdbc.url"])

  def test_jdbc_dependency_metadata(self):
    root = ET.parse(SERVICE / "configuration/hue-ini-conf.xml").getroot()
    props = {p.findtext("name"): p for p in root.findall("property")}
    dependencies = lambda p: {(x.findtext("type"), x.findtext("name"))
                              for x in p.findall("depends-on/property")}
    self.assertEqual({("hue-env", "hue_database"), ("hue-ini-conf", "hue_db_host"),
                      ("hue-ini-conf", "hue_db_port"), ("hue-ini-conf", "hue_db_name")},
                     dependencies(props["hue.jpa.jdbc.url"]))
    self.assertEqual({("hue-env", "hue_database")}, dependencies(props["hue.jpa.jdbc.driver"]))
    env = ET.parse(SERVICE / "configuration/hue-env.xml").getroot()
    root_url = next(p for p in env.findall("property") if p.findtext("name") == "hue_privelege_user_jdbc_url")
    self.assertIn(("hue-ini-conf", "hue_db_host"), dependencies(root_url))
    self.assertIn(("hue-ini-conf", "hue_db_port"), dependencies(root_url))

  def test_both_themes_use_the_real_hue_properties(self):
    props = {p.stem: {x.findtext("name") for x in ET.parse(p).getroot().findall("property")}
             for p in (SERVICE / "configuration").glob("*.xml")}
    for theme in ("database.json", "theme_version_1.json"):
      configuration = json.loads((SERVICE / "themes" / theme).read_text())["configuration"]
      placements = configuration["placement"]["configs"]
      self.assertIn("hue-ini-conf/hue_db_port", [x["config"] for x in placements])
      for entry in placements + configuration["widgets"]:
        site, name = entry["config"].split("/")
        if not name.startswith("test_"):
          self.assertIn(name, props[site], entry["config"])


if __name__ == "__main__":
  unittest.main()
