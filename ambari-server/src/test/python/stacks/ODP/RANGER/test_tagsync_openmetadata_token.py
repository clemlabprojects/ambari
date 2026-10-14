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

import ast
import importlib.util
import os
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from resource_management.core.environment import Environment
from resource_management.core.logger import Logger
from resource_management.libraries.functions.default import default
from resource_management.libraries.script.script import Script


STACKS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP"
SCRIPTS = STACKS / "1.0/services/RANGER/package/scripts"
SPEC = importlib.util.spec_from_file_location("tagsync_setup_ranger_xml", SCRIPTS / "setup_ranger_xml.py")
SETUP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SETUP)

TOKEN_KEY = "ranger.tagsync.source.openmetadatarest.token"
KEYSTORE_KEY = "ranger.tagsync.source.openmetadatarest.keystore.filename"
TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJpbmdlc3Rpb24tYm90In0.c2lnbmF0dXJl"
JCEKS = "/usr/odp/current/ranger-tagsync/conf/openmetadata.jceks"


class TestTagsyncOpenmetadataToken(unittest.TestCase):
  def params(self, site):
    return SimpleNamespace(
      config={"configurations": {"ranger-tagsync-site": site},
              "configurationAttributes": {"ranger-tagsync-site": {"password": {TOKEN_KEY: "true"}}}},
      java_home="/jdk", tagsync_cred_lib="/usr/odp/current/ranger-tagsync/lib/*",
      unix_user="ranger", unix_group="ranger", openmetadata_tagsync_jceks_path=JCEKS)

  def render(self, site):
    """The ranger-tagsync-site properties rendered for `site`, and the commands run to get them."""
    params = self.params(site)
    with Environment(test_mode=True) as env, patch.dict(sys.modules, {"params": params}), \
         patch.object(SETUP, "Execute") as execute, patch.object(SETUP, "File"):
      env.set_params(params)
      rendered = SETUP.tagsync_site_without_openmetadata_token()
    return rendered, execute

  def test_token_moves_into_credential_store(self):
    site = {"ranger.tagsync.source.openmetadatarest": "true", TOKEN_KEY: TOKEN,
            "ranger.tagsync.source.openmetadatarest.endpoint": "https://om.example.com/"}
    rendered, execute = self.render(site)

    execute.assert_called_once()
    command = execute.call_args.args[0]
    self.assertEqual(("/jdk/bin/java", "-cp", "/usr/odp/current/ranger-tagsync/lib/*",
                      "org.apache.ranger.credentialapi.buildks", "create", TOKEN_KEY, "-value"), command[:7])
    self.assertEqual(TOKEN, str(command[7]))
    self.assertNotIn(TOKEN, repr(command))
    self.assertEqual(("-provider", "jceks://file" + JCEKS), command[8:])

    # Blank, so that TagSync falls back to the store: a placeholder would be taken as the token.
    self.assertEqual("", rendered[TOKEN_KEY])
    self.assertEqual(JCEKS, rendered[KEYSTORE_KEY])
    self.assertNotIn(TOKEN, rendered.values())
    self.assertEqual("https://om.example.com/", rendered["ranger.tagsync.source.openmetadatarest.endpoint"])
    self.assertEqual(TOKEN, site[TOKEN_KEY], "the command's configuration must not be modified")

  def test_without_token_renders_site_as_configured(self):
    for site in ({"ranger.tagsync.source.atlas": "true"},
                 {"ranger.tagsync.source.atlas": "true", TOKEN_KEY: ""},
                 {TOKEN_KEY: "  ", KEYSTORE_KEY: "/etc/ranger/tagsync/operator.jceks"}):
      with self.subTest(site=site):
        rendered, execute = self.render(site)
        execute.assert_not_called()
        self.assertEqual(site, rendered)

  def test_tagsync_site_is_rendered_without_token_and_owner_only(self):
    params = self.params({"ranger.tagsync.source.openmetadatarest": "true", TOKEN_KEY: TOKEN})
    params.__dict__.update(
      ranger_tagsync_home="/usr/odp/current/ranger-tagsync", ranger_home="/usr/odp/current/ranger-admin",
      ranger_tagsync_conf="/usr/odp/current/ranger-tagsync/conf", ranger_pid_dir="/var/run/ranger",
      user_group="hadoop", tagsync_log_dir="/var/log/ranger/tagsync",
      stack_supports_ranger_tagsync_ssl_xml_support=False, tagsync_application_properties={},
      stack_supports_ranger_log4j=True, tagsync_log4j="", tagsync_services_file="/services.sh",
      ranger_env_content="")
    with Environment(test_mode=True) as env, patch.dict(sys.modules, {"params": params}), \
         patch.object(SETUP, "Execute"), patch.object(SETUP, "File"), patch.object(SETUP, "Directory"), \
         patch.object(SETUP, "PropertiesFile"), patch.object(SETUP, "create_core_site_xml"), \
         patch.object(SETUP, "XmlConfig") as xml_config:
      env.set_params(params)
      SETUP.setup_tagsync()

    calls = {call.args[0]: call.kwargs for call in xml_config.call_args_list}
    site = calls["ranger-tagsync-site.xml"]
    self.assertEqual(0o640, site["mode"])
    self.assertEqual("", site["configurations"][TOKEN_KEY])
    self.assertEqual(JCEKS, site["configurations"][KEYSTORE_KEY])

  def jceks_path(self, site):
    config = {"configurations": {"ranger-tagsync-site": site}}
    tree = ast.parse((SCRIPTS / "params.py").read_text())
    tree.body = [node for node in tree.body if isinstance(node, ast.Assign) and any(
      isinstance(target, ast.Name) and target.id == "openmetadata_tagsync_jceks_path" for target in node.targets)]
    namespace = dict(default=default, os=os, ranger_tagsync_conf="/usr/odp/current/ranger-tagsync/conf")
    with patch.object(Script, "get_config", return_value=config), patch.object(Logger, "logger", Mock()):
      exec(compile(tree, str(SCRIPTS / "params.py"), "exec"), namespace)
    return namespace["openmetadata_tagsync_jceks_path"]

  def test_credential_store_path_defaults_to_tagsync_conf(self):
    self.assertEqual(JCEKS, self.jceks_path({}))
    self.assertEqual(JCEKS, self.jceks_path({KEYSTORE_KEY: ""}))
    self.assertEqual("/etc/ranger/tagsync/om.jceks", self.jceks_path({KEYSTORE_KEY: "/etc/ranger/tagsync/om.jceks"}))


if __name__ == "__main__":
  unittest.main()
