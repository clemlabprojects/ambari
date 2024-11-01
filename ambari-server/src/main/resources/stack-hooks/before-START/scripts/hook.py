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
from rack_awareness import create_topology_script_and_mapping
from shared_initialization import setup_hadoop, setup_configs, create_javahome_symlink, setup_unlimited_key_jce_policy, \
  Hook
from custom_extensions import setup_extensions
from resource_management.libraries.functions import stack_select

class BeforeStartHook(Hook):

  def hook(self, env):
    import params

    self.run_custom_hook('before-ANY')
    env.set_params(params)

    setup_hadoop()
    setup_configs()
    create_javahome_symlink()
    create_topology_script_and_mapping()
    setup_unlimited_key_jce_policy()
    # fix hadoop-hdfs-native and hadoop-native libraries breaking isolation on ODP < 1.2.2.0-138
    if params.upgrade_type is None and params.stack_name == "ODP" and params.version:
      if params.is_namenode_master:
        stack_select.select('hadoop-hdfs-namenode',params.version)
      if params.is_journalnode:
        stack_select.select('hadoop-hdfs-journalnode',params.version)

    if params.stack_supports_hadoop_custom_extensions:
      setup_extensions()


if __name__ == "__main__":
  BeforeStartHook().execute()
