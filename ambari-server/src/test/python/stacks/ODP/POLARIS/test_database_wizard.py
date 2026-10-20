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

import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


SERVICE = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.3/services/POLARIS"


class TestPolarisDatabaseWizard(unittest.TestCase):
  def test_credentials_theme_is_registered(self):
    service = ET.parse(SERVICE / "metainfo.xml").getroot().find("services/service")
    self.assertIn("credentials.json", [x.findtext("fileName") for x in service.findall("themes/theme")])
    config = json.loads((SERVICE / "themes/credentials.json").read_text())["configuration"]
    self.assertEqual("credentials", config["layouts"][0]["tabs"][0]["name"])
    self.assertEqual({"polaris-env/polaris_admin_username", "polaris-env/polaris_admin_password",
                      "polaris-db-properties/db_user", "polaris-db-properties/db_password"},
                     {x["config"] for x in config["placement"]["configs"]})

  def test_credential_rows_pair_a_username_with_a_password(self):
    config = json.loads((SERVICE / "themes/credentials.json").read_text())["configuration"]
    rows = {}
    for entry in config["placement"]["configs"]:
      site, name = entry["config"].split("/")
      properties = ET.parse(SERVICE / "configuration" / (site + ".xml")).getroot()
      prop = next(p for p in properties.findall("property") if p.findtext("name") == name)
      rows.setdefault(entry["subsection-name"], []).append(prop.findtext("value-attributes/type") == "password")
    self.assertEqual(2, len(rows))
    for values in rows.values():
      self.assertEqual([False, True], sorted(values))

  def test_database_checks_use_assigned_hosts_and_correct_credentials(self):
    for name in ("database.json", "theme_version_1.json"):
      config = json.loads((SERVICE / "themes" / name).read_text())["configuration"]
      widgets = {x["config"]: x["widget"] for x in config["widgets"]}
      for suffix, user, password, url in (
          ("test_db_connection", "db_user", "db_password", "polaris-application-properties/quarkus.datasource.jdbc.url"),
          ("test_root_db_connection", "db_root_user", "db_root_password", "polaris-db-properties/polaris_privelege_user_jdbc_url")):
        widget = widgets["polaris-db-properties/" + suffix]
        self.assertEqual("test-db-connection", widget["type"])
        required = widget["required-properties"]
        self.assertEqual("polaris-site/polaris_server_hosts", required["db.connection.source.host"])
        self.assertEqual("polaris-db-properties/" + user, required["db.connection.user"])
        self.assertEqual("polaris-db-properties/" + password, required["db.connection.password"])
        self.assertEqual(url, required["jdbc.driver.url"])
        placement = next(x for x in config["placement"]["configs"] if x["config"].endswith("/" + suffix))
        self.assertTrue(placement["property_value_attributes"]["ui_only_property"])


if __name__ == "__main__":
  unittest.main()
