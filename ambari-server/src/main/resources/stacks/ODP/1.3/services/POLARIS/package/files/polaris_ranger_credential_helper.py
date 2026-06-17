#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Drop-in replacement for Ranger's stock ranger_credential_helper.py, used by
# Ambari's setup_ranger_plugin_keystore() to populate the JCEKS referenced by
# ranger-policymgr-ssl.xml.  The stock helper invokes
# org.apache.ranger.credentialapi.buildks, which ships only inside a Ranger
# plugin install dir (credentialbuilder jar).  Apache Polaris 1.5.0 uses Ranger's
# built-in (authz-embedded) authorizer and ships no Ranger plugin install dir, so
# buildks is not on its classpath.  This helper produces the identical Hadoop
# CredentialProvider JCEKS via org.apache.hadoop.security.alias.CredentialShell,
# which is always present on a Polaris host (Hadoop is a runtime dependency).
#
# CLI is intentionally identical to the stock helper so it is a transparent
# substitution via cred_setup_prefix_override:
#   <helper> -l <classpath> -f <jceks-path> -k <alias> -v <value> -c 1
#
# Notes:
#  * CredentialShell stores/looks up aliases lower-cased; buildks does the same
#    (both go through Hadoop's AbstractJavaKeyStoreProvider), and Ranger reads the
#    alias through that same API, so the camelCase aliases (sslKeyStore /
#    sslTrustStore) resolve correctly.
#  * CredentialShell 'create' errors if the alias already exists; Ambari re-runs
#    configure() on every START, so 'create' is implemented as delete-then-create
#    to stay idempotent (mirrors buildks' silent overwrite).

import os
import sys
from optparse import OptionParser
from subprocess import Popen, PIPE

CREDENTIAL_SHELL_CLASS = "org.apache.hadoop.security.alias.CredentialShell"

java_home = os.getenv("JAVA_HOME")
if not java_home:
  sys.stderr.write("ERROR: JAVA_HOME environment property was not defined, exit.\n")
  sys.exit(1)
JAVA_BIN = os.path.join(java_home, "bin", "java")


def _run(cmd):
  proc = Popen(cmd, stdin=PIPE, stdout=PIPE, stderr=PIPE)
  out, err = proc.communicate()
  return proc.returncode, out.decode("utf-8", "replace"), err.decode("utf-8", "replace")


def call_keystore(libpath, filepath, alias, value="", getorcreate="get"):
  final_lib_path = libpath.replace("\\", "/").replace("//", "/")
  final_provider = "jceks://file/" + filepath.replace("\\", "/").replace("//", "/")

  base = [JAVA_BIN, "-cp", final_lib_path, CREDENTIAL_SHELL_CLASS]

  if getorcreate == "create":
    # Idempotent overwrite: best-effort delete (ignore failure when the alias or
    # provider does not exist yet), then create.
    _run(base + ["delete", alias, "-provider", final_provider, "-f"])
    rc, out, err = _run(base + ["create", alias, "-value", value, "-provider", final_provider])
    if rc != 0:
      sys.stderr.write("ERROR creating alias '{0}' (rc={1}): {2}\n".format(alias, rc, err or out))
      sys.exit(rc)
    print("Alias {0} created successfully!".format(alias))
  elif getorcreate == "get":
    rc, out, err = _run(base + ["list", "-provider", final_provider])
    if rc != 0:
      sys.stderr.write("ERROR listing provider (rc={0}): {1}\n".format(rc, err or out))
      sys.exit(rc)
    print(out)
  else:
    sys.stderr.write("Invalid Arguments!!\n")
    sys.exit(1)


def main():
  parser = OptionParser()
  parser.add_option("-l", "--libpath", dest="library_path", help="Classpath holding the Hadoop CredentialProvider classes")
  parser.add_option("-f", "--file", dest="jceks_file_path", help="Path to the jceks file to use")
  parser.add_option("-k", "--key", dest="key", help="Alias to use")
  parser.add_option("-v", "--value", dest="value", help="Value to store")
  parser.add_option("-c", "--create", dest="create", help="Create/overwrite the alias when set")

  (options, _) = parser.parse_args()
  getorcreate = "create" if options.create else "get"
  call_keystore(options.library_path, options.jceks_file_path, options.key, options.value, getorcreate)


if __name__ == "__main__":
  main()
