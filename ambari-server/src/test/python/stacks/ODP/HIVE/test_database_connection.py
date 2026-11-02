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
from pathlib import Path
import unittest


STACKS = Path(__file__).resolve().parents[5] / "main/resources/stacks"
SERVICE = STACKS / "ODP/1.0/services/HIVE"
SPEC = importlib.util.spec_from_file_location("odp_hive_database_advisor", SERVICE / "service_advisor.py")
ADVISOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ADVISOR)


class TestHiveDatabaseConnection(unittest.TestCase):

  def recommend(self, components, url="jdbc:mysql://localhost/hive"):
    services = {
      "services": [{"StackServices": {"service_name": "HIVE"}, "components": [
        {"StackServiceComponents": {"component_name": name, "hostnames": hostnames}}
        for name, hostnames in components.items()]}],
      "configurations": {
        "hive-env": {"properties": {"hive_database": "Existing PostgreSQL Database", "hive_database_type": "mysql"}},
        "hive-site": {"properties": {"ambari.hive.db.schema.name": "hive", "javax.jdo.option.ConnectionURL": url,
                                     "javax.jdo.option.ConnectionDriverName": "com.mysql.jdbc.Driver"}},
      },
      "changed-configurations": [{"type": "hive-env", "name": "hive_database", "old_value": "New MySQL Database"}],
    }
    hosts = {"items": [{"Hosts": {"host_name": host}} for hostnames in components.values() for host in hostnames]}
    configurations = {}
    ADVISOR.HiveRecommender().recommendHiveDatabaseConnection(configurations, services, hosts)
    return configurations["hive-site"]["properties"]

  def test_the_url_follows_the_database_type_on_the_hiveserver2_host(self):
    site = self.recommend({"HIVE_SERVER": ["hs2.example.com"], "HIVE_METASTORE": ["hms.example.com"]})
    self.assertEqual("jdbc:postgresql://hs2.example.com:5432/hive", site["javax.jdo.option.ConnectionURL"])
    self.assertEqual("org.postgresql.Driver", site["javax.jdo.option.ConnectionDriverName"])

  def test_the_url_follows_the_database_type_without_hiveserver2(self):
    site = self.recommend({"HIVE_METASTORE": ["hms.example.com"]})
    self.assertEqual("jdbc:postgresql://hms.example.com:5432/hive", site["javax.jdo.option.ConnectionURL"])

  def test_test_connection_runs_from_the_metastore_hosts(self):
    for theme in ("theme.json", "database.json"):
      text = (SERVICE / "themes" / theme).read_text()
      json.loads(text)
      self.assertIn('"db.connection.source.host" : "hive-site/hive_metastore_hosts"', text)
      self.assertNotIn("hive_server_hosts", text)


if __name__ == "__main__":
  unittest.main()
