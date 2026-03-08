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
import os

from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.format import format
from resource_management.core.resources.system import Execute, File
from resource_management.core.source import StaticFile
from resource_management.core.source import Template
from resource_management.core.logger import Logger
import functions
from ambari_commons import OSCheck, OSConst
from ambari_commons.os_family_impl import OsFamilyImpl


class OzoneServiceCheck(Script):
  pass


@OsFamilyImpl(os_family=OsFamilyImpl.DEFAULT)
class OzoneServiceCheckDefault(OzoneServiceCheck):
  def service_check(self, env):
    import params
    env.set_params(params)

    File( format("{exec_tmp_dir}/ozone-smoke-init.sh"),
      mode = 0o755,
      content = Template('ozone-smoke-init.sh.j2')
    )

    File( format("{exec_tmp_dir}/ozone-smoke-verify.sh"),
      content = StaticFile("ozone-smoke-verify.sh"),
      mode = 0o755
    )

    File( format("{exec_tmp_dir}/ozone-smoke-cleanup.sh"),
      content = StaticFile("ozone-smoke-cleanup.sh"),
      mode = 0o755
    )
  

    if params.security_enabled:    
      smokeuser_kinit_cmd = format("{kinit_path_local} -kt {smoke_user_keytab} {smokeuser_principal}") 
      
      Execute( smokeuser_kinit_cmd,
        user = params.smoke_test_user,
        logoutput = True
      )

    service_check_conf_dir = params.ozone_base_conf_dir
    service_check_conf_candidates = [params.ozone_base_conf_dir]
    for role_conf in params.ROLE_NAME_MAP_CONF.values():
      service_check_conf_candidates.append(os.path.join(params.ozone_base_conf_dir, role_conf))

    for conf_dir in service_check_conf_candidates:
      if conf_dir and os.path.exists(os.path.join(conf_dir, "ozone-site.xml")):
        service_check_conf_dir = conf_dir
        break

    Logger.info("Using Ozone service-check configuration directory: {0}".format(service_check_conf_dir))

    servicecheckcmd = format("{exec_tmp_dir}/ozone-smoke-init.sh {service_check_conf_dir}")
    smokeverifycmd = format("{exec_tmp_dir}/ozone-smoke-verify.sh {service_check_conf_dir} ozonesmokefile.txt ozone {om_service_id}")
    cleanupCmd = format("{exec_tmp_dir}/ozone-smoke-cleanup.sh {service_check_conf_dir} {om_service_id}")
    Execute(format("{servicecheckcmd} && {smokeverifycmd} && {cleanupCmd}"),
      tries     = 6,
      try_sleep = 5,
      user = params.smoke_test_user,
      environment={
        "HADOOP_CONF_DIR": service_check_conf_dir
      },
      logoutput = True
    )

if __name__ == "__main__":
  OzoneServiceCheck().execute()
