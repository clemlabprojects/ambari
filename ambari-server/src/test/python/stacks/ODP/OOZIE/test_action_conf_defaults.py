"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""

from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

from ambari_jinja2 import Environment, FileSystemLoader


SERVICE = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.0/services/OOZIE"
TEMPLATES = SERVICE / "package/templates"


class TestOozieActionConfDefaults(unittest.TestCase):
  """The Ambari-managed action-conf/default.xml resolves ${odp.version} for Oozie actions and
  localizes the MapReduce framework archive for the YARN launcher ApplicationMaster."""

  def render(self, **params):
    env = Environment(loader=FileSystemLoader(str(TEMPLATES)))
    return env.get_template("action-conf-default.xml.j2").render(**params)

  def properties(self, rendered):
    root = ET.fromstring(rendered)
    self.assertEqual("configuration", root.tag)
    return {p.find("name").text: p.find("value").text for p in root.findall("property")}

  def test_stack_version_and_framework_archive(self):
    rendered = self.render(oozie_stack_version_property="odp.version", oozie_stack_version="1.3.2.0-36",
                           oozie_launcher_framework_archive="/odp/apps/${odp.version}/mapreduce/mapreduce.tar.gz#mr-framework")
    props = self.properties(rendered)
    self.assertEqual("1.3.2.0-36", props["odp.version"])
    self.assertEqual("1.3.2.0-36", props["oozie.launcher.odp.version"])
    self.assertEqual("/odp/apps/${odp.version}/mapreduce/mapreduce.tar.gz#mr-framework",
                     props["oozie.launcher.mapreduce.job.cache.archives"])
    self.assertEqual(3, len(props))
    # the explanation names the placeholder exactly as mapred-site spells it
    self.assertIn("${odp.version}", rendered)
    self.assertNotIn("{{", rendered)

  def test_without_framework_path_only_the_version_is_published(self):
    props = self.properties(self.render(oozie_stack_version_property="odp.version",
                                        oozie_stack_version="1.2.4.0-115",
                                        oozie_launcher_framework_archive=None))
    self.assertEqual({"odp.version": "1.2.4.0-115", "oozie.launcher.odp.version": "1.2.4.0-115"}, props)

  def test_server_configure_writes_the_file_and_params_define_its_inputs(self):
    oozie_py = (SERVICE / "package/scripts/oozie.py").read_text()
    self.assertIn('Template("action-conf-default.xml.j2")', oozie_py)
    self.assertIn('action-conf/default.xml', oozie_py)
    params = (SERVICE / "package/scripts/params_linux.py").read_text()
    for name in ("oozie_stack_version_property", "oozie_stack_version", "oozie_launcher_framework_archive"):
      self.assertTrue(re.search(r"^%s\s*=" % re.escape(name), params, re.M), name)
    self.assertIn('default("/configurations/mapred-site/mapreduce.application.framework.path", None)', params)
    self.assertIn('get_component_version_from_symlink(stack_name, "oozie-server")', params)


if __name__ == "__main__":
  unittest.main()
