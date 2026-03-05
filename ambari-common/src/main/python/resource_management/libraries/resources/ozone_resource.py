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

Ambari Agent
"""

_all__ = ["OzoneResource"]

from resource_management.core.base import Resource, ForcedListArgument, ResourceArgument, BooleanArgument


class OzoneResource(Resource):
  """
  Ambari resource wrapper for Ozone CLI operations.

  Supported actions:
    - run: execute a custom Ozone CLI sub-command.
    - generate_s3_credentials:
        * secret_key is set -> run `ozone s3 setsecret`.
        * secret_key is empty -> run `ozone s3 getsecret`.

  Kerberos can be enabled for both actions via:
    security_enabled, kinit_path_local, keytab, principal_name.
  """
  action = ForcedListArgument(default="run")
  command = ResourceArgument(default=lambda obj: obj.name)
  tries = ResourceArgument(default=1)
  try_sleep = ResourceArgument(default=0)
  user = ResourceArgument()
  logoutput = ResourceArgument()
  bin_dir = ResourceArgument(default=[])
  environment = ResourceArgument(default={})

  conf_dir = ResourceArgument()
  ozone_cmd = ResourceArgument(default="ozone")

  security_enabled = BooleanArgument(default=False)
  principal_name = ResourceArgument()
  keytab = ResourceArgument()
  kinit_path_local = ResourceArgument()

  access_id = ResourceArgument()
  secret_key = ResourceArgument()
  om_service_id = ResourceArgument()

  actions = Resource.actions + ["run", "generate_s3_credentials"]
