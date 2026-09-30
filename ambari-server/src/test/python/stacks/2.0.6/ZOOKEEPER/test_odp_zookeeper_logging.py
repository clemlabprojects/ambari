#!/usr/bin/env python3
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

import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from resource_management.core.environment import Environment


SERVICE_DIR = (Path(__file__).resolve().parents[5] / "main" / "resources" /
               "stacks" / "ODP" / "1.0" / "services" / "ZOOKEEPER")
PACKAGE_DIR = SERVICE_DIR / "package"


def load_script(name):
  spec = importlib.util.spec_from_file_location(
    "odp_" + name, str(PACKAGE_DIR / "scripts" / (name + ".py")))
  module = importlib.util.module_from_spec(spec)
  spec.loader.exec_module(module)
  return module


logging_config = load_script("zookeeper_logging")


class TestOdpZookeeperLogging(unittest.TestCase):
  def setUp(self):
    self.params = SimpleNamespace(
      config_dir="/etc/zookeeper/1.2.4.0-115/0",
      zk_user="zookeeper",
      user_group="hadoop",
      logback_support=True,
      zookeeper_logback_content=None,
      zookeeper_logback_server_content=None,
      zookeeper_log_level="INFO",
      zk_log_dir="/var/log/zookeeper",
      zookeeper_filename="zookeeper-zookeeper-server-master11.example.com.log",
      zookeeper_log_max_backup_size=10,
      zookeeper_log_number_of_backup_files=10,
      zk_env_sh_template=(
        "export JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk\n"
        "export SERVER_JVMFLAGS='-Xmx1024m -Djava.security.auth.login.config=/etc/zk-server-jaas.conf'\n"
        "export CLIENT_JVMFLAGS='-Djava.security.auth.login.config=/etc/zk-client-jaas.conf'\n"
      ),
    )

  def render(self):
    with Environment(basedir=str(PACKAGE_DIR), test_mode=True) as env:
      env.set_params(self.params)
      with patch.object(logging_config, "File") as files:
        logging_config.configure_logging(self.params)
        return [
          (os.path.basename(args[0]), kwargs["content"].get_content(), kwargs)
          for args, kwargs in files.call_args_list
        ]

  def rendered_files(self):
    return {name: content for name, content, _ in self.render()}

  def shell_flags(self, env_script, repetitions=1):
    # Exercise the actual appended shell fragment, including repeat sourcing,
    # without invoking Java, changing system configuration or acquiring tickets.
    script = "set -eu\n" + (env_script + "\n") * repetitions + (
      "printf '%s\\n' \"${SERVER_JVMFLAGS:-}\" \"${CLIENT_JVMFLAGS:-}\" \"${JVMFLAGS:-}\"\n")
    environ = {key: value for key, value in os.environ.items()
               if key not in ("SERVER_JVMFLAGS", "CLIENT_JVMFLAGS", "JVMFLAGS")}
    return subprocess.check_output(
      ["bash"], input=script.encode("utf-8"), env=environ).decode("utf-8").splitlines()

  def test_existing_cluster_without_new_config_types(self):
    files = self.render()
    self.assertEqual(
      ["logback-server.xml", "zookeeper-env.sh", "logback.xml"],
      [name for name, _, _ in files])
    for name, _, attributes in files:
      self.assertEqual("zookeeper", attributes["owner"])
      self.assertEqual("hadoop", attributes["group"])
      if name.endswith(".xml"):
        self.assertEqual(0o644, attributes["mode"])
    rendered = self.rendered_files()
    client = ET.fromstring(rendered["logback.xml"])
    server = ET.fromstring(rendered["logback-server.xml"])
    self.assertEqual(["CONSOLE"],
                     [item.get("ref") for item in client.findall("./root/appender-ref")])
    self.assertEqual([], client.findall(".//File"))
    self.assertEqual(["CONSOLE", "ROLLINGFILE"],
                     [item.get("ref") for item in server.findall("./root/appender-ref")])
    self.assertEqual("/var/log/zookeeper/" + self.params.zookeeper_filename,
                     server.findtext("./appender[@name='ROLLINGFILE']/File"))
    self.assertEqual("10MB", server.findtext(".//MaxFileSize"))

  def test_saved_environment_and_kerberos_flags_are_preserved(self):
    rendered = self.rendered_files()
    self.assertTrue(rendered["zookeeper-env.sh"].startswith(self.params.zk_env_sh_template))
    server, client, common = self.shell_flags(rendered["zookeeper-env.sh"])
    self.assertIn("-Xmx1024m", server)
    self.assertIn("-Djava.security.auth.login.config=/etc/zk-server-jaas.conf", server)
    self.assertIn("-Dlogback.configurationFile=" + self.params.config_dir +
                  "/logback-server.xml", server)
    self.assertEqual("-Djava.security.auth.login.config=/etc/zk-client-jaas.conf", client)
    self.assertEqual("", common)

  def test_rendering_and_repeat_sourcing_are_idempotent(self):
    self.params.zk_env_sh_template = 'export SERVER_JVMFLAGS="${SERVER_JVMFLAGS:-}"\n'
    self.assertEqual(self.rendered_files(), self.rendered_files())
    server, _, _ = self.shell_flags(self.rendered_files()["zookeeper-env.sh"], repetitions=3)
    self.assertEqual(1, server.count("-Dlogback.configurationFile="))

  def test_explicit_server_logback_override_is_preserved(self):
    self.params.zk_env_sh_template += (
      'export SERVER_JVMFLAGS="$SERVER_JVMFLAGS -Dlogback.configurationFile=/custom/server.xml"\n')
    server, _, _ = self.shell_flags(self.rendered_files()["zookeeper-env.sh"])
    self.assertEqual(1, server.count("-Dlogback.configurationFile="))
    self.assertIn("/custom/server.xml", server)
    self.assertNotIn("logback-server.xml", server)

  def test_explicit_common_logback_override_is_preserved(self):
    self.params.zk_env_sh_template += 'export JVMFLAGS="-Dlogback.configurationFile=/custom/common.xml"\n'
    server, _, common = self.shell_flags(self.rendered_files()["zookeeper-env.sh"])
    self.assertNotIn("-Dlogback.configurationFile=", server)
    self.assertEqual("-Dlogback.configurationFile=/custom/common.xml", common)

  def test_client_override_does_not_select_server_logging(self):
    self.params.zk_env_sh_template += (
      'export CLIENT_JVMFLAGS="$CLIENT_JVMFLAGS -Dlogback.configurationFile=/custom/client.xml"\n')
    server, client, _ = self.shell_flags(self.rendered_files()["zookeeper-env.sh"])
    self.assertIn("/logback-server.xml", server)
    self.assertIn("/custom/client.xml", client)
    self.assertNotIn("/logback-server.xml", client)

  def test_missing_jvm_variables_are_supported(self):
    self.params.zk_env_sh_template = "export JAVA_HOME=/custom/jdk\n"
    server, client, common = self.shell_flags(self.rendered_files()["zookeeper-env.sh"])
    self.assertIn("/logback-server.xml", server)
    self.assertEqual("", client)
    self.assertEqual("", common)

  def test_log4j_stack_is_unchanged(self):
    self.params.logback_support = False
    del self.params.zookeeper_logback_content
    del self.params.zookeeper_logback_server_content
    files = self.render()
    self.assertEqual(["zookeeper-env.sh"], [name for name, _, _ in files])
    self.assertEqual(self.params.zk_env_sh_template.rstrip(), files[0][1].rstrip())
    self.assertNotIn("logback", files[0][1])

  def test_ui_templates_are_rendered_instead_of_packaged_defaults(self):
    self.params.zookeeper_logback_content = (
      '<configuration><root level="WARN"/><!-- {{hostname}} --></configuration>')
    self.params.zookeeper_logback_server_content = (
      '<configuration><root level="DEBUG"/><!-- {{zk_log_dir}} --></configuration>')
    self.params.hostname = "master11.example.com"
    rendered = self.rendered_files()
    self.assertIn("master11.example.com", rendered["logback.xml"])
    self.assertEqual("WARN", ET.fromstring(rendered["logback.xml"]).find("root").get("level"))
    self.assertIn("/var/log/zookeeper", rendered["logback-server.xml"])
    self.assertEqual("DEBUG", ET.fromstring(rendered["logback-server.xml"]).find("root").get("level"))

  def test_each_missing_template_falls_back_independently(self):
    self.params.zookeeper_logback_content = '<configuration><root level="WARN"/></configuration>'
    rendered = self.rendered_files()
    self.assertEqual("WARN", ET.fromstring(rendered["logback.xml"]).find("root").get("level"))
    self.assertIn("ROLLINGFILE", rendered["logback-server.xml"])
    self.params.zookeeper_logback_content = None
    self.params.zookeeper_logback_server_content = '<configuration><root level="DEBUG"/></configuration>'
    rendered = self.rendered_files()
    self.assertNotIn("ROLLINGFILE", rendered["logback.xml"])
    self.assertEqual("DEBUG", ET.fromstring(rendered["logback-server.xml"]).find("root").get("level"))

  def test_ui_defaults_match_upgrade_fallbacks(self):
    fallback = self.rendered_files()
    for config_type, attribute in [
        ("zookeeper-logback", "zookeeper_logback_content"),
        ("zookeeper-logback-server", "zookeeper_logback_server_content")]:
      root = ET.parse(str(SERVICE_DIR / "configuration" / (config_type + ".xml"))).getroot()
      content = root.find("./property[name='content']")
      self.assertEqual("content", content.findtext("./value-attributes/type"))
      self.assertEqual("true", content.find("on-ambari-upgrade").get("add"))
      self.assertEqual("false", content.find("on-ambari-upgrade").get("update"))
      setattr(self.params, attribute, content.findtext("value").strip())
    configured = self.rendered_files()
    for name in ("logback.xml", "logback-server.xml"):
      self.assertEqual(fallback[name].strip(), configured[name].strip())

  def test_legacy_ui_level_and_rotation_settings_are_honored(self):
    self.params.zookeeper_log_level = "DEBUG"
    self.params.zk_log_dir = "/data/zookeeper/logs"
    self.params.zookeeper_log_max_backup_size = 64
    self.params.zookeeper_log_number_of_backup_files = 7
    rendered = self.rendered_files()
    server = ET.fromstring(rendered["logback-server.xml"])
    self.assertEqual("DEBUG", server.find("root").get("level"))
    self.assertEqual("64MB", server.findtext(".//MaxFileSize"))
    self.assertEqual("7", server.findtext(".//maxIndex"))
    self.assertTrue(server.findtext(".//File").startswith("/data/zookeeper/logs/"))

  def test_config_types_are_registered_for_ui_and_client_export(self):
    service = ET.parse(str(SERVICE_DIR / "metainfo.xml")).getroot().find("./services/service")
    dependencies = [element.text for element in service.findall("./configuration-dependencies/config-type")]
    files = service.findall("./components/component[name='ZOOKEEPER_CLIENT']/configFiles/configFile")
    for config_type, filename in [
        ("zookeeper-logback", "logback.xml"), ("zookeeper-logback-server", "logback-server.xml")]:
      self.assertIn(config_type, dependencies)
      self.assertTrue(any(item.findtext("dictionaryName") == config_type and
                          item.findtext("fileName") == filename for item in files))

  @unittest.skipUnless(os.environ.get("ODP_TEST_LOGBACK_CLASSPATH"),
                       "Set ODP_TEST_LOGBACK_CLASSPATH and use JDK 11+ for the runtime probe")
  def test_real_logback_client_and_server_rotation(self):
    java_home = os.environ.get("JAVA_HOME")
    java = os.path.join(java_home, "bin", "java") if java_home else "java"
    probe = str(Path(__file__).with_name("LogbackProbe.java"))
    with tempfile.TemporaryDirectory(prefix="odp-zookeeper-logback-") as directory:
      self.params.zk_log_dir = os.path.join(directory, "daemon-logs")
      self.params.zookeeper_log_max_backup_size = 1
      files = self.rendered_files()
      for filename, server in (("logback.xml", False), ("logback-server.xml", True)):
        path = Path(directory) / filename
        path.write_text(files[filename], encoding="utf-8")
        command = [java, "-cp", os.environ["ODP_TEST_LOGBACK_CLASSPATH"], probe, str(path)]
        if server:
          command.append(os.path.join(self.params.zk_log_dir, self.params.zookeeper_filename))
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=90)
        self.assertEqual(0, result.returncode, result.stdout[-4000:])
        self.assertIn("LOGBACK_PROBE_OK", result.stdout)
        if not server:
          self.assertFalse(os.path.exists(self.params.zk_log_dir))

  def test_unix_configure_uses_logging_renderer_on_server_and_client(self):
    with patch.dict(sys.modules, {"zookeeper_logging": logging_config}):
      zookeeper = load_script("zookeeper")
    self.params.zk_pid_dir = "/var/run/zookeeper"
    self.params.zk_data_dir = "/data/zookeeper"
    self.params.log4j_props = None
    self.params.security_enabled = False
    self.params.zookeeper_hosts = ["master11.example.com"]
    self.params.hostname = "master11.example.com"
    with patch.dict(sys.modules, {"params": self.params}), \
         patch.object(zookeeper, "Directory"), \
         patch.object(zookeeper, "File"), \
         patch.object(zookeeper, "configFile"), \
         patch.object(zookeeper, "generate_logfeeder_input_config"), \
         patch.object(zookeeper, "Template"), \
         patch.object(zookeeper, "configure_logging") as configure_logging:
      for component in ("client", "server"):
        with self.subTest(component=component):
          zookeeper.zookeeper(type=component)
          configure_logging.assert_called_with(self.params)
      self.assertEqual(2, configure_logging.call_count)


if __name__ == "__main__":
  unittest.main()
