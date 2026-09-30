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

from resource_management.core.resources.system import File
from resource_management.core.source import InlineTemplate, Template


# Append to the saved environment template, not just its stack default: upgraded
# clusters keep the old zookeeper-env/content in Ambari's database.
SERVER_LOGBACK_ENV = """
# Ambari selects daemon logging without changing the client classpath default.
# Respect an explicit administrator override and tolerate repeated sourcing.
case "${SERVER_JVMFLAGS:-} ${JVMFLAGS:-}" in
  *-Dlogback.configurationFile=*) ;;
  *) export SERVER_JVMFLAGS="${SERVER_JVMFLAGS:-} -Dlogback.configurationFile={{config_dir}}/logback-server.xml" ;;
esac
"""


def configure_logging(params):
  """Render UI-managed logging, retaining legacy behavior for Log4j stacks.

  Missing config types are expected on existing clusters before new defaults
  have been added to Ambari. Packaged templates provide the same safe defaults.
  The server file is written before the environment selects it; the default
  client configuration never needs access to the daemon's log directory.
  """
  env_template = params.zk_env_sh_template
  if params.logback_support:
    server_content = (
      InlineTemplate(params.zookeeper_logback_server_content)
      if params.zookeeper_logback_server_content is not None
      else Template("zookeeper-logback-server.xml.j2")
    )
    File(os.path.join(params.config_dir, "logback-server.xml"),
         content=server_content,
         owner=params.zk_user,
         group=params.user_group,
         mode=0o644)
    env_template += "\n" + SERVER_LOGBACK_ENV

  File(os.path.join(params.config_dir, "zookeeper-env.sh"),
       content=InlineTemplate(env_template),
       owner=params.zk_user,
       group=params.user_group)

  if params.logback_support:
    client_content = (
      InlineTemplate(params.zookeeper_logback_content)
      if params.zookeeper_logback_content is not None
      else Template("zookeeper-logback.xml.j2")
    )
    File(os.path.join(params.config_dir, "logback.xml"),
         content=client_content,
         owner=params.zk_user,
         group=params.user_group,
         mode=0o644)
