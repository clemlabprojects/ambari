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
from pathlib import Path
import sys
from types import ModuleType
import unittest
from unittest.mock import patch

from resource_management.core.environment import Environment


SCRIPTS = Path(__file__).resolve().parents[5] / "main/resources/stacks/ODP/1.0/services/HIVE/package/scripts"


class TestHiveWarehouseAclsForImpala(unittest.TestCase):

  def setUp(self):
    sys.path.insert(0, str(SCRIPTS))
    self.params = ModuleType("params")
    for name, value in {"hive_hdfs_user_dir": "/user/hive", "hive_user": "hive", "hive_hdfs_user_mode": 0o755,
                        "whs_dir_protocol": "", "default_fs": "hdfs://nn:8020", "fs_root": "hdfs://nn:8020",
                        "hive_metastore_warehouse_external_dir": "/warehouse/tablespace/external/hive",
                        "hive_metastore_warehouse_dir": "/warehouse/tablespace/managed/hive",
                        "user_group": "hadoop", "security_enabled": False, "hdfs_user": "hdfs",
                        "hive_impala_user": "impala"}.items():
      setattr(self.params, name, value)
    self.params.HdfsResource = lambda *args, **kwargs: None
    sys.modules["params"] = self.params
    spec = importlib.util.spec_from_file_location("odp_hive_script_acls", SCRIPTS / "hive.py")
    self.hive = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(self.hive)

  def tearDown(self):
    sys.modules.pop("params", None)
    sys.path.remove(str(SCRIPTS))

  def acl_commands(self):
    commands = []
    acls = lambda command, user: (0, "true", "")
    with Environment("/") as env, patch.object(self.hive, "get_user_call_output", acls), \
         patch.object(self.hive, "Execute", lambda command, **kwargs: commands.append(command)):
      env.set_params(self.params)
      self.hive.create_hive_hdfs_dirs()
    return [c for c in commands if "setfacl" in c]

  def test_impala_gets_the_hive_warehouse_directories(self):
    commands = self.acl_commands()
    for directory in ("/warehouse/tablespace/external/hive", "/warehouse/tablespace/managed/hive"):
      self.assertIn("hdfs dfs -setfacl -m user:impala:rwx,default:user:impala:rwx " + directory, commands)

  def test_only_hive_without_impala(self):
    self.params.hive_impala_user = None
    self.assertEqual(2, len(self.acl_commands()))


if __name__ == "__main__":
  unittest.main()
