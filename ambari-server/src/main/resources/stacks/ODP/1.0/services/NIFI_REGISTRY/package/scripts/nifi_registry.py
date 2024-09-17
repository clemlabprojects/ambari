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

import sys, nifi_toolkit_util_common, os, pwd, grp, signal, time, glob, socket
from resource_management import *
from resource_management.core import sudo
from resource_management.libraries.functions import conf_select
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.resources.modify_properties_file import ModifyPropertiesFile
from resource_management.libraries.functions import StackFeature
from resource_management.libraries.functions.constants import Direction
from resource_management.core.exceptions import Fail
from setup_ranger_nifi_registry import setup_ranger_nifi_registry

import config_utils

reload(sys)
sys.setdefaultencoding('utf8')

class Master(Script):
    def get_component_name(self):
        stack_name = default("/clusterLevelParams/stack_name", None)
        if stack_name == "HDP":
            return None
        return "nifi-registry"

    def pre_upgrade_restart(self, env, upgrade_type=None):
        Logger.info("Executing Stack Upgrade pre-restart")
        import params
        env.set_params(params)

        if params.version and check_stack_feature(StackFeature.ROLLING_UPGRADE, format_stack_version(params.version)):
            stack_select.select("nifi-registry", params.version)
        if params.version and check_stack_feature(StackFeature.CONFIG_VERSIONING, params.version):
            conf_select.select(params.stack_name, "nifi-registry", params.version)

    def post_upgrade_restart(self, env, upgrade_type=None):
        pass

    def install(self, env):
        import params

        self.install_packages(env)

        # params.nifi_registry_dir,
        Directory([params.nifi_registry_log_dir],
                  owner=params.nifi_registry_user,
                  group=params.nifi_registry_group,
                  create_parents=True,
                  recursive_ownership=True,
                  cd_access='a'
                  )

        nifi_toolkit_util_common.copy_toolkit_scripts(params.toolkit_files_dir, params.toolkit_tmp_dir, params.nifi_registry_user, params.nifi_registry_group, upgrade_type=None, service=nifi_toolkit_util_common.NIFI_REGISTRY)
        Execute('touch ' +  params.nifi_registry_log_file, user=params.nifi_registry_user)


    def configure(self, env, isInstall=False, is_starting = False):
        import params
        import status_params
        env.set_params(params)
        env.set_params(status_params)

        #create the log, pid, conf dirs if not already present
        nifi_registry_dirs = [status_params.nifi_registry_pid_dir,
                              params.nifi_registry_internal_dir,
                              params.nifi_registry_internal_config_dir,
                              params.nifi_registry_database_dir,
                              params.nifi_registry_config_dir,
                              params.bin_dir,
                              params.lib_dir,
                              params.docs_dir]

        Directory(nifi_registry_dirs,
                  owner=params.nifi_registry_user,
                  group=params.nifi_registry_group,
                  create_parents=True,
                  recursive_ownership=True,
                  cd_access='a')


        #write configurations
        self.write_configurations(params, is_starting)


    def stop(self, env, upgrade_type=None):
        import params
        import status_params
        env.set_params(params)
        env.set_params(status_params)

        # this method will be called during an upgrade before start/configure get to setup all the permissions so we need to do it here too
        Directory([params.bin_dir],
                  owner=params.nifi_registry_user,
                  group=params.nifi_registry_group,
                  create_parents=True,
                  recursive_ownership=True,
                  cd_access='a'
                  )

        env_content=InlineTemplate(params.nifi_registry_env_content)
        File(format("{params.bin_dir}/nifi-registry-env.sh"), content=env_content, owner=params.nifi_registry_user, group=params.nifi_registry_group, mode=0o755)

        Execute ('export JAVA_HOME='+params.jdk64_home+';'+params.bin_dir+'/nifi-registry.sh stop >> ' + params.nifi_registry_log_file, user=params.nifi_registry_user)
        if os.path.isfile(status_params.nifi_registry_pid_file):
            sudo.unlink(status_params.nifi_registry_pid_file)

    def start(self, env, upgrade_type=None):
        import params
        import status_params

        nifi_toolkit_util_common.copy_toolkit_scripts(params.toolkit_files_dir, params.toolkit_tmp_dir, params.nifi_registry_user, params.nifi_registry_group, upgrade_type=None, service=nifi_toolkit_util_common.NIFI_REGISTRY)
        self.configure(env, is_starting = True)
        setup_ranger_nifi_registry(upgrade_type=None)

        Execute ('export JAVA_HOME='+params.jdk64_home+';'+params.bin_dir+'/nifi-registry.sh start >> ' + params.nifi_registry_log_file, user=params.nifi_registry_user)
        #If nifi pid file not created yet, wait a bit
        if not os.path.isfile(status_params.nifi_registry_pid_dir+'/nifi-registry.pid'):
            Execute ('sleep 5')

    def status(self, env):
        import status_params
        check_process_status(status_params.nifi_registry_pid_file)

    def setup_tls_toolkit_upgrade(self,env):
        import params
        env.set_params(params)

        upgrade_stack = stack_select._get_upgrade_stack()
        if upgrade_stack is None:
            raise Fail('Unable to determine the stack and stack version')

        if params.upgrade_direction == Direction.UPGRADE and params.nifi_registry_ssl_enabled and params.nifi_ca_host:
            version_file = params.nifi_registry_config_dir + '/config_version'
            client_json_file = params.nifi_registry_config_dir+ '/nifi-certificate-authority-client.json'

            if not sudo.path_isfile(version_file):
                Logger.info(format('Create config version file if it does not exist'))
                nifi_toolkit_util_common.save_config_version(params.config, version_file, 'ssl', params.nifi_registry_user, params.nifi_registry_group)

            if sudo.path_isfile(client_json_file):
                Logger.info(format('Remove client json file'))
                sudo.unlink(client_json_file)

    def write_configurations(self, params, is_starting):

        if os.path.isfile(params.nifi_registry_config_dir + '/bootstrap.conf'):
            bootstrap_current_conf = nifi_toolkit_util_common.convert_properties_to_dict(params.nifi_registry_config_dir + '/bootstrap.conf')
            master_key = bootstrap_current_conf['nifi.registry.bootstrap.sensitive.key'] if 'nifi.registry.bootstrap.sensitive.key' in bootstrap_current_conf else None
        else:
            master_key = None

        if os.path.isfile(params.nifi_registry_config_dir + '/nifi-registry.properties'):
            nifi_registry_current_properties = nifi_toolkit_util_common.convert_properties_to_dict(params.nifi_registry_config_dir + '/nifi-registry.properties')
            if 'nifi.registry.sensitive.props.key' in nifi_registry_current_properties and nifi_registry_current_properties['nifi.registry.sensitive.props.key']:
                params.nifi_registry_properties['nifi.registry.sensitive.props.key'] = nifi_registry_current_properties['nifi.registry.sensitive.props.key']
            if 'nifi.registry.sensitive.props.key.protected' in nifi_registry_current_properties and nifi_registry_current_properties['nifi.registry.sensitive.props.key.protected']:
                params.nifi_registry_properties['nifi.registry.sensitive.props.key.protected'] = nifi_registry_current_properties['nifi.registry.sensitive.props.key.protected']
        else:
            nifi_registry_current_properties = params.nifi_registry_properties
            params.nifi_toolkit_tls_regenerate = True

        #resolve and populate required security values and hashes
        params.nifi_registry_properties = nifi_toolkit_util_common.update_nifi_ssl_properties(params.nifi_registry_properties, params.nifi_registry_truststore,
                                                                                                params.nifi_registry_truststoreType, params.nifi_registry_truststorePasswd, params.nifi_registry_keystore,
                                                                                                params.nifi_registry_keystoreType, params.nifi_registry_keystorePasswd, params.nifi_registry_keyPasswd, nifi_toolkit_util_common.NIFI_REGISTRY)

        #determine whether new keystore/truststore should be regenerated
        run_tls = (params.nifi_ca_host and params.nifi_registry_ambari_ssl_enabled) and (params.nifi_toolkit_tls_regenerate or nifi_toolkit_util_common.generate_keystore_truststore(nifi_registry_current_properties, params.nifi_registry_properties, master_key,  nifi_toolkit_util_common.NIFI_REGISTRY))

        if run_tls:
            nifi_toolkit_util_common.move_keystore_truststore(nifi_registry_current_properties,  nifi_toolkit_util_common.NIFI_REGISTRY)
            params.nifi_registry_properties = nifi_toolkit_util_common.create_keystore_truststore(
              params.nifi_registry_properties,
              is_starting,
              params.nifi_toolkit_java_options,
              params.nifi_registry_config_dir,
              params.nifi_registry_user,
              params.nifi_registry_group,
              nifi_toolkit_util_common.NIFI_REGISTRY
            )
        elif not params.nifi_registry_ssl_enabled and (not params.nifi_registry_keystore or not params.nifi_registry_truststore):
            params.nifi_registry_properties = nifi_toolkit_util_common.clean_toolkit_client_files(nifi_registry_current_properties, params.nifi_registry_properties,  nifi_toolkit_util_common.NIFI_REGISTRY)
        elif params.nifi_registry_ssl_enabled and not run_tls and os.path.isfile(params.nifi_registry_config_dir + '/nifi-registry.properties'):
            params.nifi_registry_properties = nifi_toolkit_util_common.populate_ssl_properties(nifi_toolkit_util_common.convert_properties_to_dict(params.nifi_registry_config_dir + '/nifi-registry.properties'),params.nifi_registry_properties,params,  nifi_toolkit_util_common.NIFI_REGISTRY)

        self.write_files(params)

        nifi_toolkit_util_common.encrypt_sensitive_properties(
          params.nifi_registry_config_dir,
          params.jdk64_home,
          params.nifi_toolkit_java_options,
          params.nifi_registry_user,
          master_key,
          params.nifi_registry_security_encrypt_configuration_password,
          is_starting,
          params.toolkit_tmp_dir,
          params.stack_version_buildnum,
          nifi_toolkit_util_common.NIFI_REGISTRY
        )

        #Apply Hashed Ambari parameters by retrieving new master key and hashing required parameters for Ambari
        bootstrap_current_conf = nifi_toolkit_util_common.convert_properties_to_dict(format("{params.nifi_registry_bootstrap_file}"))
        master_key = bootstrap_current_conf['nifi.registry.bootstrap.sensitive.key'] if 'nifi.registry.bootstrap.sensitive.key' in bootstrap_current_conf else None
        if master_key:
            nifi_registry_hashed_params = nifi_toolkit_util_common.update_nifi_ambari_hash_properties(params.nifi_registry_truststorePasswd, params.nifi_registry_keystorePasswd,
                                                                                               params.nifi_registry_keyPasswd, master_key, nifi_toolkit_util_common.NIFI_REGISTRY)
            ModifyPropertiesFile(format("{params.nifi_registry_config_dir}/nifi-registry.properties"),
                                 properties = nifi_registry_hashed_params,
                                 owner = params.nifi_registry_user)
        else:
            raise Fail("Unable to persist ambari hashes due to no master key! Please validate this was written to bootstrap.conf file.")


    def write_files(self, params):

        #write out nifi-registry.properties
        PropertiesFile(params.nifi_registry_config_dir + '/nifi-registry.properties',
                       properties = params.nifi_registry_properties,
                       mode = 0o600,
                       owner = params.nifi_registry_user,
                       group = params.nifi_registry_group)

        #write out boostrap.conf
        bootstrap_content=InlineTemplate(params.nifi_registry_boostrap_content)

        File(format("{params.nifi_registry_bootstrap_file}"),
             content=bootstrap_content,
             owner=params.nifi_registry_user,
             group=params.nifi_registry_group,
             mode=0o600)

        #write out logback.xml
        logback_content=InlineTemplate(params.nifi_registry_logback_content)

        File(format("{params.nifi_registry_config_dir}/logback.xml"),
             content=logback_content,
             owner=params.nifi_registry_user,
             group=params.nifi_registry_group,
             mode=0o400)

        #write out authorizers file

        authorizers_content=config_utils.append_xml_content(params.nifi_registry_authorizers_content, params.nifi_registry_authorizers_dict)

        File(format("{params.nifi_registry_config_dir}/authorizers.xml"),
             content=authorizers_content,
             owner=params.nifi_registry_user,
             group=params.nifi_registry_group,
             mode=0o600)

        #write out identity-providers.xml
        identity_providers_content=config_utils.append_xml_content(params.nifi_registry_identity_providers_content, params.nifi_registry_identity_providers_dict)

        File(format("{params.nifi_registry_config_dir}/identity-providers.xml"),
             content=identity_providers_content,
             owner=params.nifi_registry_user,
             group=params.nifi_registry_group,
             mode=0o600)

        #write out providers file
        providers_content=config_utils.append_xml_content(params.nifi_registry_providers_content, params.nifi_registry_providers_dict)

        File(format("{params.nifi_registry_config_dir}/providers.xml"),
             content=providers_content,
             owner=params.nifi_registry_user,
             group=params.nifi_registry_group,
             mode=0o400)

        #write out nifi-env in bin as 0755 (see BUG-61769)
        env_content=InlineTemplate(params.nifi_registry_env_content)

        File(format("{params.bin_dir}/nifi-registry-env.sh"),
             content=env_content,
             owner=params.nifi_registry_user,
             group=params.nifi_registry_group,
             mode=0o755)


if __name__ == "__main__":
    Master().execute()
