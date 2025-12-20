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

import os
import sys
from resource_management.libraries.script.script import Script
from resource_management.libraries.resources.execute_hadoop import ExecuteHadoop
from resource_management.libraries.functions import conf_select
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.core.resources.system import Execute, File
from resource_management.core.source import StaticFile
from ambari_commons import OSConst
from ambari_commons.os_family_impl import OsFamilyImpl
from resource_management.core.logger import Logger


class MapReduce2ServiceCheck(Script):
  def service_check(self, env):
    pass


@OsFamilyImpl(os_family=OSConst.WINSRV_FAMILY)
class MapReduce2ServiceCheckWindows(MapReduce2ServiceCheck):
  def service_check(self, env):
    import params

    env.set_params(params)

    component_type = 'hs'
    if params.hadoop_ssl_enabled:
      component_address = params.hs_webui_address
    else:
      component_address = params.hs_webui_address

    validateStatusFileName = "validateYarnComponentStatusWindows.py"
    validateStatusFilePath = os.path.join(os.path.dirname(params.hadoop_home), "temp", validateStatusFileName)
    python_executable = sys.executable
    validateStatusCmd = "{0} {1} {2} -p {3} -s {4}".format(
      python_executable, validateStatusFilePath, component_type, component_address, params.hadoop_ssl_enabled)

    if params.security_enabled:
      kinit_cmd = "{0} -kt {1} {2};".format(params.kinit_path_local, params.smoke_user_keytab, params.smokeuser)
      smoke_cmd = kinit_cmd + validateStatusCmd
    else:
      smoke_cmd = validateStatusCmd

    File(validateStatusFilePath,
         content=StaticFile(validateStatusFileName)
    )

    Execute(smoke_cmd,
            tries=3,
            try_sleep=5,
            logoutput=True
    )


@OsFamilyImpl(os_family=OsFamilyImpl.DEFAULT)
class MapReduce2ServiceCheckDefault(MapReduce2ServiceCheck):
  def service_check(self, env):
    import params
    env.set_params(params)

    # use conf by default
    conf_dir = params.hadoop_conf_dir
    jar_path = format("{hadoop_mapred2_jar_location}/{hadoopMapredExamplesJarName}")

    if params.is_upgrade_running and ('rolling' in params.upgrade_type.lower()):
      stack_root = Script.get_stack_root()
      config_path = os.path.join(stack_root, "current/hadoop-client/conf")
      conf_dir = config_path
      Logger.info("Using default hadoop conf dir '" + config_path + "'.")
      path = format('/usr/sbin:/sbin:/usr/local/bin:/bin:/usr/bin')
      jar_path = format("{hadoop_mapr_home}/{hadoopMapredExamplesJarName}")
    else:
      os.environ['PATH'] = params.mapreduce_check_execute_path + os.pathsep + os.environ['PATH']
      path = format('{params.mapreduce_check_execute_path}:/usr/sbin:/sbin:/usr/local/bin:/bin:/usr/bin')

    input_file = format("/user/{smokeuser}/mapredsmokeinput")
    output_file = format("/user/{smokeuser}/mapredsmokeoutput")

    test_cmd = format("fs -test -e {output_file}")
    run_wordcount_job = format("jar {jar_path} wordcount {input_file} {output_file}")

    params.HdfsResource(format("/user/{smokeuser}"),
                      type="directory",
                      action="create_on_execute",
                      owner=params.smokeuser,
                      mode=params.smoke_hdfs_user_mode,
    )
    params.HdfsResource(output_file,
                        action = "delete_on_execute",
                        type = "directory",
                        dfs_type = params.dfs_type,
    )

    test_file = params.mapred2_service_check_test_file
    if not os.path.isfile(test_file):
      try:
        Execute(format("dd if=/dev/urandom of={test_file} count=1 bs=1024"))
      except:
        try:
          Execute(format("rm {test_file}")) #clean up
        except:
          pass
        test_file = "/etc/passwd"

    params.HdfsResource(input_file,
                        action = "create_on_execute",
                        type = "file",
                        source = test_file,
                        dfs_type = params.dfs_type,
    )
    params.HdfsResource(None, action="execute")

    # initialize the ticket
    if params.security_enabled:
      kinit_cmd = format("{kinit_path_local} -kt {smoke_user_keytab} {smokeuser_principal};")
      Execute(kinit_cmd, user=params.smokeuser)

    # set 'PATH' env using os
    # AMBARI-293 (clemlab): Update YARN, MAPREDUCE, TEZ service check to use default config dir during rolling upgrades
    # need to introduce Rolling Upgrade check to avoid issues during Upgrade 
    # since ODP 1.3 stack version with Hadoop 3.4+ handle JDK 17 differently
    ExecuteHadoop(run_wordcount_job,
                  tries=1,
                  try_sleep=5,
                  user=params.smokeuser,
                  bin_dir=path,
                  conf_dir=conf_dir,
                  logoutput=True,
                  environment={'PATH': path}
                  )

    # the ticket may have expired, so re-initialize
    if params.security_enabled:
      kinit_cmd = format("{kinit_path_local} -kt {smoke_user_keytab} {smokeuser_principal};")
      Execute(kinit_cmd, user=params.smokeuser)

    ExecuteHadoop(test_cmd,
                  user=params.smokeuser,
                  bin_dir=path,
                  conf_dir=conf_dir)


if __name__ == "__main__":
  MapReduce2ServiceCheck().execute()
