# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Regression checks for the ODP Atlas logging upgrade descriptors."""

import os
import unittest
import xml.etree.ElementTree as ET


STACKS = os.path.abspath(os.path.join(
    os.path.dirname(__file__), "../../main/resources/stacks/ODP"))
MIGRATION = "odp_1_3_1_0_atlas_logback_metadata_opts_migration"
DEFAULT = "odp_1_3_1_0_atlas_logback_metadata_opts_default"
LOG4J = "-Dlog4j.configuration=atlas-log4j.xml"
LOGBACK = "-Dlogback.configurationFile=atlas-logback.xml"


class TestAtlasLoggingUpgrade(unittest.TestCase):
    def parse(self, path):
        return ET.parse(os.path.join(STACKS, path)).getroot()

    def test_definitions_exist_in_both_source_stack_packs(self):
        definitions = []
        for stack in ("1.2", "1.3"):
            root = self.parse(stack + "/upgrades/config-upgrade.xml")
            component = root.find(
                ".//service[@name='ATLAS']/component[@name='ATLAS_SERVER']")
            migration = component.find("changes/definition[@id='%s']" % MIGRATION)
            default = component.find("changes/definition[@id='%s']" % DEFAULT)
            self.assertEqual("atlas-env", migration.findtext("type"))
            self.assertEqual("atlas-env", default.findtext("type"))
            replacement = migration.find("replace")
            self.assertEqual({"key": "metadata_opts", "find": LOG4J,
                              "replace-with": LOGBACK}, replacement.attrib)
            setting = default.find("set")
            self.assertEqual({"key": "metadata_opts", "value": LOGBACK,
                              "if-key": "metadata_opts", "if-key-state": "absent"},
                             setting.attrib)
            definitions.append((replacement.attrib, setting.attrib))
        self.assertEqual(definitions[0], definitions[1])

    def test_replacement_preserves_other_options_and_is_idempotent(self):
        for stack in ("1.2", "1.3"):
            root = self.parse(stack + "/upgrades/config-upgrade.xml")
            rule = root.find(".//definition[@id='%s']/replace" % MIGRATION)
            old = "-Dcustom.before=true " + LOG4J + " -Dcustom.after=true"
            expected = "-Dcustom.before=true " + LOGBACK + " -Dcustom.after=true"
            migrated = old.replace(rule.get("find"), rule.get("replace-with"))
            self.assertEqual(expected, migrated)
            self.assertEqual(migrated, migrated.replace(
                rule.get("find"), rule.get("replace-with")))
            for custom in ("", "-Dcustom=true",
                           "-Dlogback.configurationFile=/custom/atlas.xml",
                           "-Dlog4j.configuration=/custom/atlas.xml"):
                self.assertEqual(custom, custom.replace(
                    rule.get("find"), rule.get("replace-with")))

    def test_express_upgrade_migrates_before_atlas_restart(self):
        for stack in ("1.2", "1.3"):
            root = self.parse(stack + "/upgrades/nonrolling-upgrade-odp-1.3.xml")
            groups = root.findall("order/group")
            restart_index = next(i for i, group in enumerate(groups)
                                 if group.get("name") == "ATLAS")
            for identifier in (MIGRATION, DEFAULT):
                matches = [(i, group, stage) for i, group in enumerate(groups)
                           for stage in group.findall("execute-stage")
                           if stage.find("task[@id='%s']" % identifier) is not None]
                self.assertEqual(1, len(matches))
                index, group, stage = matches[0]
                self.assertLess(index, restart_index)
                self.assertEqual("UPGRADE", group.findtext("direction"))
                self.assertEqual("ATLAS", stage.get("service"))
                self.assertEqual("ATLAS_SERVER", stage.get("component"))

    def test_rolling_upgrade_migrates_in_atlas_pre_upgrade_only(self):
        for stack in ("1.2", "1.3"):
            root = self.parse(stack + "/upgrades/upgrade-odp-1.3.xml")
            component = root.find(
                ".//service[@name='ATLAS']/component[@name='ATLAS_SERVER']")
            for identifier in (MIGRATION, DEFAULT):
                self.assertIsNotNone(component.find(
                    "pre-upgrade/task[@id='%s']" % identifier))
                self.assertEqual(1, len(root.findall(".//task[@id='%s']" % identifier)))
                self.assertIsNone(component.find(
                    "pre-downgrade/task[@id='%s']" % identifier))

    def test_older_target_upgrade_packs_do_not_run_logback_migration(self):
        directory = os.path.join(STACKS, "1.2/upgrades")
        for filename in os.listdir(directory):
            if filename.endswith(".xml") and "odp-1.3" not in filename:
                root = self.parse("1.2/upgrades/" + filename)
                self.assertIsNone(root.find(".//task[@id='%s']" % MIGRATION))
                self.assertIsNone(root.find(".//task[@id='%s']" % DEFAULT))

    def test_fresh_install_default_is_scoped_to_odp_1_3(self):
        def default(stack):
            root = self.parse(stack + "/services/ATLAS/configuration/atlas-env.xml")
            return root.find("property[name='metadata_opts']")

        self.assertEqual(LOG4J, default("1.0").findtext("value"))
        self.assertEqual(LOGBACK, default("1.3").findtext("value"))
        self.assertEqual("false", default("1.3").find("on-ambari-upgrade").get("add"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
