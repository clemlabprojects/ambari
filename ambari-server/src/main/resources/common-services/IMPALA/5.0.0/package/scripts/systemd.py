#!/usr/bin/env ambari-python-wrap
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
from subprocess import Popen, PIPE

from resource_management.core.exceptions import ComponentIsNotRunning
from resource_management.core import sudo

def check_systemd_process_status(service):
    """Query systemd to see if a service is running."""
    command = ["systemctl", "show", "--property", "MainPID", service]
    process = Popen(command, stdout=PIPE, stderr=PIPE)
    stdout, stderr = process.communicate()
    stdout = stdout.decode()

    if len(stdout) == 0:
        raise ComponentIsNotRunning()
    
    try:
        pid = stdout.split("=")[1]
        pid = int(pid)

    except Exception:
        raise ComponentIsNotRunning()

    if pid == 0:
        raise ComponentIsNotRunning()

    if stderr.strip() != b"":
        raise ComponentIsNotRunning()

    if "failure" in stdout.lower() or "exited" in stdout.lower():
        raise ComponentIsNotRunning()
    
    try:
        # Kill will not actually kill the process
        # From the doc:
        # If sig is 0, then no signal is sent, but error checking is still
        # performed; this can be used to check for the existence of a
        # process ID or process group ID.
        sudo.kill(pid, 0)

    except OSError:
        raise ComponentIsNotRunning()
