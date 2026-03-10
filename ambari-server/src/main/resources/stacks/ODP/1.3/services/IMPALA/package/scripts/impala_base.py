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

from resource_management.libraries.script.script import Script
from resource_management.core.logger import Logger
from resource_management.core import sudo
from resource_management.core.shell import as_sudo
from resource_management.core.resources.system import Execute
from resource_management.core.resources.system import Directory, File
from resource_management.core.source import InlineTemplate, Template, StaticFile
from resource_management.libraries.resources.xml_config import XmlConfig
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.get_config import get_config
from ambari_commons.credential_store_helper import create_password_in_credential_store
from resource_management.core.exceptions import Fail
from resource_management.libraries.functions.check_process_status import check_process_status

from setup_ranger_impala import setup_ranger_impala
import os
import subprocess


class ImpalaBase(Script):
    # impala_packages = [
    #     'impala-server',
    #     'impala-catalog',
    #     'impala-state-store',
    #     'impala-shell']
    # Call setup.sh to install the service

    def installImpala(self, env):
        # Install packages listed in metainfo.xml
        self.install_packages(env)
        # if self.impala_packages is not None and len(self.impala_packages):
        #     for pack in self.impala_packages:
        #         Package(pack)
        import params
        env.set_params(params)

        scriptDir = params.files_dir
        #libnativeTaska = None
        #libnativeTaskso = None
        #libZstdso = None
        #for lib in os.listdir(scriptDir):
        #   if lib.startswith('libnativetask') and lib.endswith(".a"):
        #        libnativeTaska = lib
        #File(
        #    "/usr/lib/impala/lib/"+libnativeTaska,
        #    content=StaticFile(os.path.join(scriptDir,libnativeTaska)), mode=0o644)
        #    if lib.startswith('libnativetask.so') and lib.endswith(".0"):
        #        libnativeTaskso = lib
        #File(
        #    "/usr/lib/impala/lib/"+libnativeTaskso,
        #    content=StaticFile(os.path.join(scriptDir,libnativeTaskso)), mode=0o644)
        #    if lib.startswith('libzstd.so') and lib.endswith(".0"):
        #        libZstdso = lib
        #File(
        #    "/usr/lib/impala/lib/"+libZstdso,
        #    content=StaticFile(os.path.join(scriptDir,libZstdso)), mode=0o644)
        # init lib (optional - disabled by default for ODP packaging layout)
        if params.impala_run_init_lib:
            File(format("{tmp_dir}/impala_init_lib.sh"),
                 content=Template('init_lib.sh.j2'), mode=0o700)
            cmd = as_sudo(["bash", format("{tmp_dir}/impala_init_lib.sh")])
            Execute(cmd, logoutput=True)
        
        # Path to the bigtop-detect-java-home script
        detect_java_home_script = '/usr/lib/bigtop-utils/bigtop-detect-javahome'
        # Command to source the script and echo JAVA_HOME
        command = 'source {0} && echo $JAVA_HOME'.format(detect_java_home_script)
        # Run the command in a shell and capture the output
        result = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True, executable="/bin/bash")
        stdout, stderr = result.communicate()
        # Extract JAVA_HOME from the output
        java_home = stdout.decode('utf-8').strip() if hasattr(stdout, 'decode') else stdout.strip()
        # Set JAVA_HOME environment variable
        if java_home:
            os.environ['JAVA_HOME'] = java_home
            # Print the value to verify
            print("JAVA_HOME is set to: {0}".format(java_home))
            # Write to /etc/default/bigtop-utils
            File("/etc/default/bigtop-utils",
                 content="export JAVA_HOME=" + java_home)
        else:
            print("JAVA_HOME could not be detected.")

    def configureImpala(self, env, name=None, upgrade_type=None):
        import params
        env.set_params(params)
        # if params.security_enabled:
        #     cmd = format("{service_packagedir}/scripts/ktuntil_config.sh")
        #     Execute('echo "Running ' + cmd + '" as root')
        #     Execute(cmd, ignore_failures=True)

        File("/etc/default/impala",
             content=InlineTemplate(
                 "# Managed by Ambari.\n"
                 "# Native runtime config is rendered to /etc/impala/conf/impala-env.sh and *_flags files.\n"
             ),
             mode=0o644
        )
        File("/etc/default/impala-daemon",
             content=InlineTemplate(
                 "# Managed by Ambari.\n"
                 "# impala.sh reads /etc/impala/conf/impalad_flags directly.\n"
                 "DAEMON_FLAGS=\"\"\n"
             ),
             mode=0o644
        )
        File("/etc/default/impala-catalog-service",
             content=InlineTemplate(
                 "# Managed by Ambari.\n"
                 "# impala.sh reads /etc/impala/conf/catalogd_flags directly.\n"
                 "DAEMON_FLAGS=\"\"\n"
             ),
             mode=0o644
        )
        File("/etc/default/impala-state-store",
             content=InlineTemplate(
                 "# Managed by Ambari.\n"
                 "# impala.sh reads /etc/impala/conf/statestored_flags directly.\n"
                 "DAEMON_FLAGS=\"\"\n"
             ),
             mode=0o644
        )
        File(params.impala_env_sh,
             owner=params.impala_user,
             group=params.impala_group,
             content=Template('impala-env.sh.j2', resolved_java_home=params.java64_home),
             mode=0o644
        )
        File(params.catalogd_flags_path,
             owner=params.impala_user,
             group=params.impala_group,
             content=Template('catalogd_flags.j2', realm_name=params.realm_name),
             mode=0o644
        )
        File(params.statestored_flags_path,
             owner=params.impala_user,
             group=params.impala_group,
             content=Template('statestored_flags.j2', realm_name=params.realm_name),
             mode=0o644
        )
        File(params.impalad_flags_path,
             owner=params.impala_user,
             group=params.impala_group,
             content=Template('impalad_flags.j2', realm_name=params.realm_name),
             mode=0o644
        )

        #create log4j.properties in conf dir
        File(os.path.join(params.impala_conf_dir, "log4j.properties"),
             owner=params.impala_user,
             group=params.impala_group,
             content=InlineTemplate(params.impala_log4j_properties),
             mode=0o644
             )
        # Update fair_scheduler and llama-site xml on enabling admission control flag
        File(os.path.join(params.impala_conf_dir, "fair-scheduler.xml"),
             owner=params.impala_user,
             group=params.impala_group,
             content=InlineTemplate(params.fair_scheduler_content),
             mode=0o644)

        File(os.path.join(params.impala_conf_dir, "llama-site.xml"),
             owner=params.impala_user,
             group=params.impala_group,
             content=InlineTemplate(params.llama_site_content),
             mode=0o644)
        for directory in format("{impala_scratch_dir}").split(","):
            Directory(format(directory), mode=0o777)

        File(os.path.join(params.impala_conf_dir, "redaction-rules.json"),
             owner=params.impala_user,
             group=params.impala_group,
             content=Template('redaction-rules.json.j2'),
             mode=0o700)

        Directory(params.local_library_dir,
                  owner=params.impala_user,
                  group=params.impala_group,
                  cd_access='a')

        if params.enable_audit_event_log:
            Directory(params.audit_event_log_dir,
                      owner=params.impala_user,
                      group=params.impala_group,
                      cd_access='a')

        # Add hive-site.xml to impala conf
        XmlConfig("hive-site.xml",
              conf_dir = params.impala_conf_dir,
              configurations = params.hive_site_config,
              configuration_attributes = params.config['configurationAttributes']['hive-site'],
              owner = params.impala_user,
              group = params.impala_group,
              mode = 0o644)

        # Ensure Impala JVM resolves Hadoop/HDFS settings from its own conf dir.
        XmlConfig("core-site.xml",
              conf_dir = params.impala_conf_dir,
              configurations = params.core_site_config,
              configuration_attributes = params.config['configurationAttributes'].get('core-site', {}),
              owner = params.impala_user,
              group = params.impala_group,
              mode = 0o644,
              xml_include_file = params.mount_table_xml_inclusion_file_full_path)

        # Render hdfs-site.xml only when HDFS service/config is present.
        if params.has_namenode and params.hdfs_site_config:
            XmlConfig("hdfs-site.xml",
                  conf_dir = params.impala_conf_dir,
                  configurations = params.hdfs_site_config,
                  configuration_attributes = params.config['configurationAttributes'].get('hdfs-site', {}),
                  owner = params.impala_user,
                  group = params.impala_group,
                  mode = 0o644)

        # Add hive-ranger confs to impala_conf_dir on enabling ranger
        if params.enable_ranger :
            # Properties to update
            properties_to_update = [
                'xasecure.audit.destination.solr.batch.filespool.dir',
                'xasecure.audit.destination.hdfs.batch.filespool.dir'
            ]
            for prop in properties_to_update:
                if prop in params.audit_config:
                    original_path = params.audit_config[prop]
                    updated_path = original_path.replace('hive', 'impala_audit')
                    params.audit_config[prop] = updated_path

                    # Ensure directory exists with correct ownership
                    if not os.path.exists(updated_path):
                        Directory(updated_path,
                                  owner=params.impala_user,
                                  group=params.impala_group,
                                  create_parents = True,
                                  mode=0o755)

            if params.setup_client_option:
                Directory(params.hive_conf_dir,
                          owner=params.hive_user,
                          group=params.user_group,
                          create_parents = True,
                          mode=0o755
                          )
                hivemetastore_site_config = get_config("hivemetastore-site")
                if hivemetastore_site_config:
                    XmlConfig("hivemetastore-site.xml",
                              conf_dir=params.impala_conf_dir,
                              configurations=params.config['configurations']['hivemetastore-site'],
                              configuration_attributes=params.config['configurationAttributes']['hivemetastore-site'],
                              owner=params.hive_user,
                              group=params.user_group,
                              mode=0o644)
                else:
                    print("Hive Metastore site not present")
                XmlConfig("hive-site.xml",
                          conf_dir = params.hive_conf_dir,
                          configurations = params.hive_site_config,
                          configuration_attributes = params.config['configurationAttributes']['hive-site'],
                          owner = params.hive_user,
                          group = params.user_group,
                          mode = 0o644)
                XmlConfig("hiveserver2-site.xml",
                          conf_dir=params.impala_conf_dir,
                          configurations=params.config['configurations']['hiveserver2-site'],
                          configuration_attributes=params.config['configurationAttributes']['hiveserver2-site'],
                          owner=params.hive_user,
                          group=params.user_group,
                          mode=0o644)

                XmlConfig("core-site.xml",
                          conf_dir=params.hadoop_conf_dir,
                          configurations=params.config['configurations']['core-site'],
                          configuration_attributes=params.config['configurationAttributes']['core-site'],
                          owner=params.hdfs_user,
                          group=params.user_group,
                          mode=0o644,
                          xml_include_file=params.mount_table_xml_inclusion_file_full_path
                          )
                XmlConfig("hdfs-site.xml",
                          conf_dir=params.hadoop_conf_dir,
                          configurations=params.config['configurations']['hdfs-site'],
                          configuration_attributes=params.config['configurationAttributes']['hdfs-site'],
                          mode=0o644,
                          owner=params.hdfs_user,
                          group=params.user_group
                          )
            if name !="client":
                setup_ranger_impala(upgrade_type=None)
        else:
            print("Ranger is Disabled")

        if params.enable_ldap_auth:
            if not params.jdk_location:
                raise Fail("ambariLevelParams/jdk_location is required for JCEKS-backed Impala LDAP credentials.")

            Directory(params.impala_credential_lib_dir,
                      owner=params.impala_user,
                      group=params.impala_group,
                      create_parents=True,
                      mode=0o755)
            Directory(os.path.dirname(params.impala_credential_store_file),
                      owner=params.impala_user,
                      group=params.impala_group,
                      create_parents=True,
                      mode=0o750)

            if params.impala_ldap_bind_password and params.impala_ldap_bind_password.strip():
                create_password_in_credential_store(
                    params.impala_credential_alias,
                    params.impala_credential_provider_path,
                    params.impala_credential_classpath,
                    params.java64_home,
                    params.jdk_location,
                    params.impala_ldap_bind_password,
                )
                File(params.impala_credential_store_file,
                     owner=params.impala_user,
                     group=params.impala_group,
                     only_if=format("test -e {impala_credential_store_file}"),
                     mode=0o640)
                dot_jceks_crc_file = os.path.join(
                    os.path.dirname(params.impala_credential_store_file),
                    ".{0}.crc".format(os.path.basename(params.impala_credential_store_file))
                )
                File(dot_jceks_crc_file,
                     owner=params.impala_user,
                     group=params.impala_group,
                     only_if=format("test -e {dot_jceks_crc_file}"),
                     mode=0o640)

            # Render a local helper script so ldap_bind_password_cmd stays runtime-compatible.
            File(os.path.join(params.impala_conf_dir, "init_ldap_creds.sh"),
                 owner=params.impala_user,
                 group=params.impala_group,
                 content=Template('init_ldap_creds.sh.j2'),
                 mode=0o700)


        # Workaround for Kudu - See ODP-2131 for details
        if os.path.isfile("/var/lib/impala/kudu-client-1.12.0.jar"):
            sudo.unlink("/var/lib/impala/kudu-client-1.12.0.jar")

    def _impala_service_meta(self, service_name):
        import params
        if service_name == "impala-daemon":
            return {
                "svc": "impalad",
                "pid": "/var/run/impala/impalad.pid",
                "out": "impalad.out",
                "err": "impalad.err",
                "impala_home": "{}".format(params.impala_home_daemon)
            }
        if service_name == "impala-catalog-service":
            return {
                "svc": "catalogd",
                "pid": "/var/run/impala/catalogd.pid",
                "out": "catalogd.out",
                "err": "catalogd.err",
                "impala_home": "{}".format(params.impala_home_catalog)
            }
        if service_name == "impala-state-store":
            return {
                "svc": "statestored",
                "pid": "/var/run/impala/statestored.pid",
                "out": "statestored.out",
                "err": "statestored.err",
                "impala_home": "{}".format(params.impala_home_statestore)
            }
        raise Fail("Unknown Impala service: {0}".format(service_name))

    def _source_java_home(self, source_file):
        if not source_file or not os.path.isfile(source_file):
            return None
        command = "source {0} >/dev/null 2>&1 && printf '%s' \"$JAVA_HOME\"".format(source_file)
        result = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            shell=True,
            executable="/bin/bash",
        )
        stdout, _ = result.communicate()
        if result.returncode != 0:
            return None
        java_home = stdout.decode('utf-8').strip() if hasattr(stdout, 'decode') else stdout.strip()
        return os.path.realpath(java_home) if java_home else None

    def _java_home_has_lib(self, java_home, lib_name):
        java_home = os.path.realpath(java_home) if java_home else None
        if not java_home or not os.path.isdir(java_home):
            return False
        for root, _, files in os.walk(java_home):
            if lib_name in files:
                return True
        return False

    def _is_valid_java_home(self, java_home):
        return self._java_home_has_lib(java_home, "libjvm.so") and self._java_home_has_lib(java_home, "libjsig.so")

    def _resolve_java_home(self):
        import params

        candidates = [
            params.java_home,
            getattr(params, "java64_home", None),
            params.ambari_java_home,
            self._source_java_home("/etc/default/bigtop-utils"),
            self._source_java_home("/usr/lib/bigtop-utils/bigtop-detect-javahome"),
            self._source_java_home("/usr/libexec/bigtop-detect-javahome"),
        ]

        tried = []
        for candidate in candidates:
            if not candidate:
                continue
            candidate = os.path.realpath(candidate.strip())
            if not candidate or candidate in tried:
                continue
            tried.append(candidate)
            if self._is_valid_java_home(candidate):
                return candidate

        raise Fail(
            "Unable to resolve a valid JAVA_HOME for Impala. Tried: {0}".format(
                ", ".join(tried) if tried else "<none>"
            )
        )

    def _impala_runtime_env(self, service_name):
        import params

        meta = self._impala_service_meta(service_name)
        env = os.environ.copy()
        env["JAVA_HOME"] = self._resolve_java_home()
        env["%s_PIDFILE" % meta["svc"].upper()] = meta["pid"]
        env["%s_OUTFILE" % meta["svc"].upper()] = os.path.join(params.impala_log_dir, meta["out"])
        env["%s_ERRFILE" % meta["svc"].upper()] = os.path.join(params.impala_log_dir, meta["err"])
        if params.has_hadoop_home:
            env["HADOOP_HOME"] = params.hadoop_home
        if params.has_hadoop_conf_dir:
            env["HADOOP_CONF_DIR"] = params.hadoop_conf_dir
        return env

    def _impala_cmd(self, action, service_name, extra_args=None):
        meta = self._impala_service_meta(service_name)
        args = ""
        if action == "start" and extra_args:
            args = " {0}".format(extra_args)
        return "{0}/bin/impala.sh {1} {2}{3}".format(
            meta["impala_home"], action, meta["svc"], args
        )

    def start_impala_service(self, service_name, extra_args=None):
        import params
        meta = self._impala_service_meta(service_name)
        print("Starting service: {}".format(service_name))

        Directory("/var/run/impala",
                  owner=params.impala_user,
                  group=params.impala_group,
                  create_parents=True)
        Directory(params.impala_log_dir,
                  owner=params.impala_user,
                  group=params.impala_group,
                  create_parents=True)

        env = self._impala_runtime_env(service_name)
        cmd = self._impala_cmd("start", service_name, extra_args=extra_args)
        Execute(cmd, environment=env, user=params.impala_user, logoutput=True)

    def stop_impala_service(self, service_name):
        import params
        meta = self._impala_service_meta(service_name)
        print("Stopping service: {}".format(service_name))

        env = self._impala_runtime_env(service_name)
        cmd = self._impala_cmd("stop", service_name)
        Execute(cmd, environment=env, user=params.impala_user, logoutput=True)

    def status_impala_service(self, service_name):
        meta = self._impala_service_meta(service_name)
        check_process_status(meta["pid"])

    def failover_impala_service(self, service_name, force_flag, ha_enabled, ha_hosts, component_name):
        import params

        if not ha_enabled or len(ha_hosts) != 2:
            raise Fail("{0} failover requires exactly 2 installed hosts in HA mode.".format(component_name))

        Logger.info("Running one-shot failover for {0} on host {1}".format(component_name, params.current_host_name))

        try:
            self.status_impala_service(service_name)
        except Exception:
            Logger.info("{0} is not running on this host; starting it with {1}".format(service_name, force_flag))
        else:
            self.stop_impala_service(service_name)

        self.start_impala_service(service_name, extra_args=force_flag)
