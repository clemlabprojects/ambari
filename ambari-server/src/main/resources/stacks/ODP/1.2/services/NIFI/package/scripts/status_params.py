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

from resource_management import *
import sys, os, glob, re

config = Script.get_config()

nifi_pid_dir=config['configurations']['nifi-env']['nifi_pid_dir']
#nifi_master_pid_file=nifi_pid_dir + '/nifi-master.pid'
nifi_node_pid_file=nifi_pid_dir + '/nifi.pid'
nifi_ca_pid_file=nifi_pid_dir + '/nifi-ca.pid'
nifi_user = config['configurations']['nifi-env']['nifi_user']
nifi_bin_dir = os.path.join(Script.get_stack_root(), 'current', 'nifi', 'bin')

def nifi_bootstrap_major_version():
  """Major version of the installed NiFi bootstrap (lib/bootstrap/nifi-bootstrap-<version>.jar), None when not found"""
  for jar in glob.glob(os.path.join(Script.get_stack_root(), 'current', 'nifi', 'lib', 'bootstrap', 'nifi-bootstrap-*.jar')):
    match = re.match(r'nifi-bootstrap-(\d+)\.', os.path.basename(jar))
    if match:
      return int(match.group(1))
  return None

# The NiFi 1 bootstrap (RunNiFi) writes nifi.pid; the NiFi 2 bootstrap writes no pid file: it finds the application as
# its own child process and reports it with "nifi.sh status"
nifi_bootstrap_reports_status = (nifi_bootstrap_major_version() or 1) >= 2
