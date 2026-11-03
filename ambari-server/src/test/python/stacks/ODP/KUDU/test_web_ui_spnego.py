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

import json
from pathlib import Path
import unittest

from ambari_jinja2 import Environment, FileSystemLoader

SERVICES = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.3/services"


def render(service, template, **values):
  env = Environment(loader=FileSystemLoader(str(SERVICES / service / "package/templates")))
  return env.get_template(template).render(**values)


def identities(service, component):
  descriptor = json.loads((SERVICES / service / "kerberos.json").read_text())
  for entry in descriptor["services"][0]["components"]:
    if entry["name"] == component:
      return entry["identities"]


class TestKuduWebUiSpnego(unittest.TestCase):

  def flags(self, role, security_enabled, additional="", require_spnego=True):
    values = dict(security_enabled=security_enabled, kudu_webserver_require_spnego=require_spnego,
                  kudu_principal_name="kudu/_HOST@EXAMPLE.COM",
                  kudu_keytab="/etc/security/keytabs/kudu.service.keytab", kudu_enable_tls=False,
                  kudu_ranger_authz_enabled=False)
    values[role + "_additional_flags"] = additional
    return render("KUDU", role + ".gflagfile.j2", **values).splitlines()

  def test_the_web_ui_requires_kerberos_on_a_kerberized_cluster(self):
    for role in ("master", "tserver"):
      self.assertIn("--webserver_require_spnego=true", self.flags(role, True))
      self.assertNotIn("--webserver_require_spnego=true", self.flags(role, False))
      self.assertNotIn("--webserver_require_spnego=true",
                       self.flags(role, True, additional="--webserver_require_spnego=false"))
      # the operator can turn it off on a Kerberized cluster
      self.assertNotIn("--webserver_require_spnego=true", self.flags(role, True, require_spnego=False))

  def test_the_keytab_holds_the_http_principal(self):
    for component in ("KUDU_MASTER", "KUDU_TSERVER"):
      http = [i for i in identities("KUDU", component) if i.get("principal", {}).get("value", "").startswith("HTTP/")]
      self.assertEqual(["${keytab_dir}/kudu.service.keytab"], [i["keytab"]["file"] for i in http])


class TestImpalaWebUiSpnego(unittest.TestCase):

  def test_every_role_requires_kerberos_on_a_kerberized_cluster(self):
    for template in ("impalad_flags.j2", "catalogd_flags.j2", "statestored_flags.j2"):
      text = (SERVICES / "IMPALA/package/templates" / template).read_text()
      security_block = text[text.index("{% if security_enabled %}"):]
      security_block = security_block[:security_block.index("-webserver_require_spnego=true") + 60]
      self.assertIn("-keytab_file", security_block, template)
      # the operator can turn it off on a Kerberized cluster
      self.assertIn("{% if impala_webserver_require_spnego %}\n-webserver_require_spnego=true\n{% endif %}", security_block,
                    template)

  def test_every_role_has_the_http_principal(self):
    for component in ("IMPALA_STATE_STORE", "IMPALA_CATALOG_SERVICE"):
      self.assertIn("/IMPALA/IMPALA_DAEMON/impala_http", [i.get("reference") for i in identities("IMPALA", component)])

  def test_health_alerts_authenticate(self):
    # the agent runs kinit as the smoke user, which reads the shared SPNEGO keytab (not the Impala keytab)
    descriptor = json.loads((SERVICES / "IMPALA/kerberos.json").read_text())
    spnego = [i for i in descriptor["services"][0]["identities"] if i.get("reference") == "/spnego"]
    self.assertEqual("impala-env/impala_spnego_keytab", spnego[0]["keytab"]["configuration"])
    alerts = json.loads((SERVICES / "IMPALA/alerts.json").read_text())["IMPALA"]
    web = [a for component in alerts.values() for a in component if a["source"]["type"] == "WEB"]
    self.assertEqual(3, len(web))
    for alert in web:
      # SPNEGO needs the host name: the agent replaces 0.0.0.0 with it (HTTP/localhost has no principal)
      self.assertTrue(alert["source"]["uri"]["http"].startswith("0.0.0.0:"))
      self.assertEqual("{{impala-env/impala_spnego_principal}}", alert["source"]["uri"]["kerberos_principal"])
      self.assertEqual("{{impala-env/impala_spnego_keytab}}", alert["source"]["uri"]["kerberos_keytab"])


if __name__ == "__main__":
  unittest.main()
