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

from resource_management.libraries.script.script import Script
from resource_management.core.resources.system import Execute, File
from resource_management.libraries.functions.format import format
from resource_management.core.source import StaticFile
import sys
import os
from ambari_commons import OSConst
from ambari_commons.os_family_impl import OsFamilyFuncImpl, OsFamilyImpl


class HueServiceCheck(Script):
  def service_check(self, env):
    pass

@OsFamilyImpl(os_family=OsFamilyImpl.DEFAULT)
class HueServiceCheckDefault(HueServiceCheck):
  def service_check(self, env):
    import params
    env.set_params(params)

    validateHueFileName = "validateHueStatus.py"
    validateHueFilePath = format("{tmp_dir}/{validateHueFileName}")
    python_executable = sys.executable
    validateStatusCmd = format("{python_executable} {validateHueFilePath} -p {hue_host_port} -n {hue_host_name}")
    if params.security_enabled:
      kinit_cmd = format("{kinit_path_local} -kt {smoke_user_keytab} {smokeuser_principal};")
      smoke_cmd = format("{kinit_cmd} {validateStatusCmd}")
    else:
      smoke_cmd = validateStatusCmd

    print("Test connectivity to Hue server")

    File(validateHueFilePath,
         content=StaticFile(validateHueFileName),
         mode=0o755
    )

    Execute(smoke_cmd,
            tries=3,
            try_sleep=5,
            path='/usr/sbin:/sbin:/usr/local/bin:/bin:/usr/bin',
            user=params.smokeuser,
            timeout=5,
            logoutput=True
    )


if __name__ == "__main__":
    HueServiceCheck().execute()