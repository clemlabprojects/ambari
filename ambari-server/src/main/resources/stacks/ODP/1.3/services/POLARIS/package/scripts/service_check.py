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
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.core.resources.system import Execute
from resource_management.libraries.functions.format import format
from resource_management.libraries.script.script import Script


class PolarisServiceCheck(Script):
  def service_check(self, env):
    import params
    env.set_params(params)

    if not params.polaris_hosts:
      raise Fail("No POLARIS_SERVER hosts found to run service check.")

    failures = 0
    for host in params.polaris_hosts:
      url = format("{polaris_protocol}://{host}:{polaris_port}/")
      cmd = format("curl -k -s -o /dev/null {url}")
      try:
        Execute(cmd, user=params.smoke_test_user, tries=5, try_sleep=10)
      except Exception as err:
        failures += 1
        Logger.error("Polaris service check failed for host {0} with error {1}".format(host, err))

    if failures == len(params.polaris_hosts):
      raise Fail("All instances of POLARIS_SERVER are down.")


if __name__ == "__main__":
  PolarisServiceCheck().execute()
