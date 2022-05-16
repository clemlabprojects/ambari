#!/usr/bin/env python
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

from resource_management.libraries.functions.format import format
from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.default import default

config = Script.get_config()

flink_user = config['configurations']['flink-env']['flink_user']
flink_group = config['configurations']['flink-env']['flink_group']
user_group = config['configurations']['cluster-env']['user_group']


flink_pid_dir = config['configurations']['flink-env']['flink_pid_dir']
flink_history_server_pid_file = format("{flink_pid_dir}/flink-{flink_user}-historyserver.pid")
stack_name = default("/clusterLevelParams/stack_name", None)

if "livy2-env" in config['configurations']:
  livy2_user = config['configurations']['livy2-env']['livy2_user']
  livy2_group = config['configurations']['livy2-env']['livy2_group']
  livy2_pid_dir = config['configurations']['livy2-env']['livy2_pid_dir']
  livy2_server_pid_file = format("{livy2_pid_dir}/livy-{livy2_user}-server.pid")