#!/usr/bin/python3
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
from resource_management.libraries.functions import format
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions import get_kinit_path
from resource_management.libraries.script.script import Script
from ambari_commons import OSCheck
from resource_management.libraries.functions.version import format_stack_version
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions import StackFeature


config = Script.get_config()
stack_root = Script.get_stack_root()
stack_version_unformatted = config['clusterLevelParams']['stack_version']
stack_version_formatted = format_stack_version(stack_version_unformatted)

hue_conf_dir = '/etc/hue/conf'
if stack_version_formatted and check_stack_feature(StackFeature.ROLLING_UPGRADE, stack_version_formatted):
  hue_conf_dir = format('{stack_root}/current/hue-server/conf')
hue_pid_dir = config['configurations']['hue-env']['hue_pid_dir']
hue_pid_file = format("{hue_pid_dir}/hue.pid")

security_enabled = config['configurations']['cluster-env']['security_enabled']
if security_enabled:
    hue_keytab_path = config['configurations']['hue-env']['hue_keytab_path']
    hue_principal_name = config['configurations']['hue-env']['hue_principal_name']
else:
    hue_keytab_path = None
    hue_principal_name = None

hostname = config['agentLevelParams']['hostname'].lower()
hue_user = default("/configurations/hue-env/hue_user", "hue")
kinit_path_local = get_kinit_path(default('/configurations/kerberos-env/executable_search_paths', None))
temp_dir = Script.get_tmp_dir()
  
stack_name = default("/clusterLevelParams/stack_name", None)