#!/usr/bin/python2
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
import collections
import os

from resource_management.libraries.functions.version import format_stack_version
from resource_management.libraries.resources.properties_file import PropertiesFile
from resource_management.libraries.resources.template_config import TemplateConfig
from resource_management.core.resources.system import Directory, Execute, File, Link
from resource_management.core.source import StaticFile, Template, InlineTemplate, DownloadSource
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions import format
from resource_management.libraries.functions.generate_logfeeder_input_config import generate_logfeeder_input_config
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions import StackFeature
from resource_management.libraries.functions import Direction
from resource_management.libraries.functions.get_user_call_output import get_user_call_output
from resource_management.core.exceptions import ExecutionFailed
from resource_management.core.logger import Logger
from resource_management.core.exceptions import Fail
from resource_management.libraries.functions.show_logs import show_logs
from resource_management.libraries.functions.setup_atlas_hook import setup_atlas_jar_symlinks

import urllib2, time, json

def streamline(env, upgrade_type=None):
    import params
    ensure_base_directories()
    #Logger.info(format("Effective stack version: {effective_version}"))

    File(format("{conf_dir}/streamline-env.sh"),
          owner=params.streamline_user,
          content=InlineTemplate(params.streamline_env_sh_template)
     )

    # On some OS this folder could be not exists, so we will create it before pushing there files
    Directory(params.limits_conf_dir,
              create_parents = True,
              owner='root',
              group='root'
    )

    Directory([params.jar_storage],
            owner=params.streamline_user,
            group=params.user_group,
            create_parents = True,
            cd_access="a",
            mode=0755,
    )

    # this is hard-coded as we are not accepting
    # the registry local-jars
    # should be removed from future releases
    Directory("/tmp/schema-registry/local-jars",
        owner=params.streamline_user,
        group=params.user_group,
        create_parents = True,
        cd_access="a",
        mode=0755)

    Directory([params.topology_test_results],
            owner=params.streamline_user,
            group=params.user_group,
            create_parents = True,
            cd_access="a",
            mode=0755,
    )

    File(os.path.join(params.limits_conf_dir, 'streamline.conf'),
         owner='root',
         group='root',
         mode=0644,
         content=Template("streamline.conf.j2")
    )


    File(format("{conf_dir}/streamline.yaml"),
         content=Template("streamline.yaml.j2"),
         owner=params.streamline_user,
         group=params.user_group,
         mode=0600
    )

    generate_logfeeder_input_config('streamline', Template("input.config-streamline.json.j2", extra_imports=[default]))

    if params.security_enabled:
        if params.streamline_jaas_conf_template:
            File(format("{conf_dir}/streamline_jaas.conf"),
                 owner=params.streamline_user,
                 content=InlineTemplate(params.streamline_jaas_conf_template))
        else:
            TemplateConfig(format("{conf_dir}/streamline_jaas.conf"),
                         owner=params.streamline_user)

    if not os.path.islink(params.streamline_managed_log_dir):
      Link(params.streamline_managed_log_dir,
           to=params.streamline_log_dir)


    download_database_connector_if_needed()


def ensure_base_directories():
  import params
  import status_params
  Directory([params.streamline_log_dir, status_params.streamline_pid_dir, params.conf_dir, params.streamline_agent_dir, params.streamline_bootstrap_dir, params.streamline_libs],
            mode=0755,
            cd_access='a',
            owner=params.streamline_user,
            group=params.user_group,
            create_parents = True,
            recursive_ownership = True,
            )

  if params.enable_atlas_hook:
    storm_extlib_dir = os.path.join(params.storm_client_home, "extlib")
    setup_atlas_jar_symlinks("storm", storm_extlib_dir)


def download_database_connector_if_needed():
  """
  Downloads the database connector to use when connecting to the metadata storage
  """
  import params
  if params.streamline_storage_type != 'mysql' and params.streamline_storage_type != 'oracle':
      # In any other case than oracle and mysql, e.g. postgres, just return.
      return

  if params.jdbc_driver_jar == None:
      if "mysql" in params.streamline_storage_type:
          Logger.error("Failed to find mysql-java-connector jar. Make sure you followed the steps to register mysql driver")
          Logger.info("Users should register the mysql java driver jar.")
          Logger.info("yum install mysql-connector-java*")
          Logger.info("sudo ambari-server setup --jdbc-db=mysql --jdbc-driver=/usr/share/java/mysql-connector-java.jar")
          raise Fail('Unable to establish jdbc connection to your ' + params.streamline_storage_type + ' instance.')
      if "oracle" in params.streamline_storage_type:
          Logger.error("Failed to find ojdbc jar. Please download and make sure you followed the steps to register oracle jdbc driver")
          Logger.info("Users should register the oracle ojdbc driver jar.")
          Logger.info("Create a symlink e.g. ln -s /usr/share/java/ojdbc6.jar /usr/share/java/ojdbc.jar")
          Logger.info("sudo ambari-server setup --jdbc-db=oracle --jdbc-driver=/usr/share/java/ojdbc.jar")
          raise Fail('Unable to establish jdbc connection to your ' + params.streamline_storage_type + ' instance.')

  File(params.check_db_connection_jar,
       content = DownloadSource(format("{jdk_location}/{check_db_connection_jar_name}")))

  target_jar_with_directory = params.connector_download_dir + os.path.sep + params.jdbc_driver_jar
  target_jar_bootstrap_dir = params.connector_bootstrap_download_dir + os.path.sep + params.jdbc_driver_jar

  if not os.path.exists(target_jar_with_directory):
      File(params.downloaded_custom_connector,
           content=DownloadSource(params.connector_curl_source))

      Execute(('cp', '--remove-destination', params.downloaded_custom_connector, target_jar_with_directory),
              path=["/bin", "/usr/bin/"],
              sudo=True)

      File(target_jar_with_directory, owner="root",
           group=params.user_group)

  if not os.path.exists(target_jar_bootstrap_dir):
      File(params.downloaded_custom_connector,
         content=DownloadSource(params.connector_curl_source))

      Execute(('cp', '--remove-destination', params.downloaded_custom_connector, target_jar_bootstrap_dir),
              path=["/bin", "/usr/bin/"],
              sudo=True)

      File(target_jar_with_directory, owner="root",
           group=params.user_group)


def wait_until_server_starts():
    import params
    if params.streamline_ssl_enabled:
      streamline_api = format("https://{params.hostname}:{params.streamline_ssl_port}/api/v1/config/streamline")
    else:
      streamline_api = format("http://{params.hostname}:{params.streamline_port}/api/v1/config/streamline")
    Logger.info(streamline_api)
    max_retries = 6
    success = False
    curl_connection_timeout = '5'
    for num in range(0, max_retries):
      try:
        Logger.info(format("Making http requests to {streamline_api}"))

        if (params.security_enabled) and (not params.streamline_sso_enabled):
          get_app_info_cmd = "curl --negotiate -u : -ks --location-trusted --connect-timeout " + curl_connection_timeout + " " + streamline_api
          return_code, stdout, _ = get_user_call_output(get_app_info_cmd, user=params.streamline_user, path='/usr/sbin:/sbin:/usr/local/bin:/bin:/usr/bin',)
          try:
            json_response = json.loads(stdout)
            success = True
            Logger.info(format("Successfully made a API request to SAM. {stdout}"))
            break
          except Exception as e:
            Logger.error(format("Response from SAM API was not a valid JSON. Response: {stdout}"))
        else:
          response = urllib2.urlopen(streamline_api)
          api_response = response.read()
          response_code = response.getcode()
          Logger.info(format("SAM response http status {response}"))
          if response.getcode() != 200:
            Logger.error(format("Failed to fetch response for {streamline_api}"))
            show_logs(params.streamline_log_dir, params.streamline_user)
            raise
          else:
            success = True
            Logger.info(format("Successfully made a API request to SAM. {api_response}"))
            break
      except (urllib2.URLError, ExecutionFailed) as e:
        Logger.error(format("Failed to make API request to SAM server at {streamline_api},retrying.. {num} out of {max_retries}"))
        time.sleep(num * 5) #exponential back-off
        continue

    if success != True:
      Logger.error(format("Failed to make API request to  SAM server at {streamline_api} after {max_retries}"))
      raise
