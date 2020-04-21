'''
Copyright (c) 2011-2018, Hortonworks Inc.  All rights reserved.
Except as expressly permitted in a written agreement between you
or your company and Hortonworks, Inc, any use, reproduction,
modification,
redistribution, sharing, lending or other exploitation
of all or any part of the contents of this file is strictly prohibited.
'''
from resource_management.libraries.script.script import Script
from resource_management.core.exceptions import Fail
from resource_management.libraries.resources.xml_config import XmlConfig
from resource_management.core.source import InlineTemplate, Template
from resource_management.core.exceptions import ComponentIsNotRunning
from resource_management.core.logger import Logger
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions import format
from resource_management.libraries.functions.generate_logfeeder_input_config import generate_logfeeder_input_config
from resource_management.libraries.functions import check_process_status
from resource_management.core.resources.system import File, Execute, Directory
from resource_management.core.resources.accounts import User
from resource_management.libraries.functions.default import default
import os
import os.path
import string
import random
import ConfigParser
import subprocess
import getpass
import re
import utils
import time
import sys

DEFAULT_USER_PREFIX='default:'
class Activity(Script):

    ##################### Directly exposed functions through Ambari #####################
    def __init__(self, component=None):
        self.component = component

    def reinstall(self, env):
        self.uninstall(env)
        self.install(env)

    def install(self, env):
        import params
        env.set_params(params)

        self.install_packages()

        if self.component is 'analyzer':
            self.install_activity_analyzer()
        elif self.component is 'explorer':
            self.install_activity_explorer()

        self.deploy_component_specific_config(env)


    # Configuration setting based on configs specified in ambari
    def configure(self, env, upgrade_type=None):
        self.deploy_component_specific_config(env)


    # Start all SmartSense services
    def start(self, env, upgrade_type=None):
        # Before start deploy the latest configs
        self.deploy_component_specific_config(env)
        if self.component is 'analyzer':
            self.start_activity_analyzer()
        elif self.component is 'explorer':
            self.start_activity_explorer()

    # Stop all SmartSense services
    def stop(self, env, upgrade_type=None):
        if self.component is 'explorer':
            self.stop_activity_explorer()
        elif self.component is 'analyzer':
            self.stop_activity_analyzer()


    def status(self, env):
        import params

        cmd = '/usr/sbin/hst activity status '
        if self.component is 'explorer':
            cmd = '/usr/sbin/hst activity-explorer status '
        elif self.component is 'analyzer':
            cmd = '/usr/sbin/hst activity-analyzer status '

        exit_code, output, error = self.execute_command(cmd, failOnError=False)
        if (exit_code == 0 or exit_code == None):
            pass
        else:
            raise ComponentIsNotRunning()

    def delete(self, env):
        self.uninstall(env)

    def uninstall(self, env):
        if self.component is 'analyzer':
            self.uninstall_activity_analyzer()
        elif self.component is 'explorer':
            self.uninstall_activity_explorer()


    def start_activity_analyzer(self):
        cmd = '/usr/sbin/hst activity-analyzer start '
        exit_code, output, error = self.execute_command(cmd)

    def stop_activity_analyzer(self):
        cmd = '/usr/sbin/hst activity-analyzer stop '
        exit_code, output, error = self.execute_command(cmd)

    def start_activity_explorer(self):
        import params
        cmd = 'export JAVA_HOME=' + params.java_home + '; /usr/sbin/hst activity-explorer start '
        exit_code, output, error = self.execute_command(cmd)

    def stop_activity_explorer(self):
        cmd = '/usr/sbin/hst activity-explorer stop '
        exit_code, output, error = self.execute_command(cmd)

    def install_packages(self):
        import params
        import time
        distname, version = utils.get_os()
        major_version = version.split(".")[0]

        if distname.startswith('ubuntu') or distname.startswith('debian'):
            cmd = "{sudo} dpkg-query -l  | grep  'ii\s*smartsense-*' || {sudo} apt-get -o Dpkg::Options::=--force-confdef --allow-unauthenticated --assume-yes install smartsense-hst || {sudo} dpkg -i " + os.path.join(params.service_package_folder, "files" , "deb", "*.deb")
        elif distname.startswith('sles') or distname.startswith('suse'):
            cmd = "{sudo} rpm -qa | grep smartsense- || {sudo} zypper install --auto-agree-with-licenses --no-confirm smartsense-hst || {sudo} rpm -i " + os.path.join(params.service_package_folder, "files" , "rpm", "*.rpm")
        else:
            cmd = "{sudo} rpm -qa | grep smartsense- || {sudo} yum -y install smartsense-hst || {sudo} rpm -i " + os.path.join(params.service_package_folder, "files" , "rpm", "*.rpm")
        print("installing using command: " + cmd)

        attempts = 0
        while attempts < 10:
            attempts += 1
            try:
                exit_code, output, error = self.execute_command(cmd)
                if (exit_code == 0):
                    break
            except Exception, e:
                print "Failed to install during attempt " + str (attempts)
                print e
            if (attempts < 15):
                print "Waiting 10 seconds for next retry"
                time.sleep(5)

    def install_activity_explorer(self):
        import params
        user, group = self.get_owner_user_and_group()
        cmd = "{sudo} /usr/sbin/hst activity-explorer setup " + user + ":" + group
        print("Deploying activity explorer")
        exit_code, output, error = self.execute_command(cmd)

    def install_activity_analyzer(self):
        import params
        import grp

        if 'ignore_groupsusers_create' in params.config['configurations']['cluster-env']:
            print ('ignore_groupsusers_create', params.config['configurations']['cluster-env']['ignore_groupsusers_create'] )

        if 'ignore_groupsusers_create' in params.config['configurations']['cluster-env'] and ( params.config['configurations']['cluster-env']['ignore_groupsusers_create'] == True or str(params.config['configurations']['cluster-env']['ignore_groupsusers_create']).lower() == 'true' ):
            print("Skipping activity analyzer user creation as specified by ambari config 'ignore_groupsusers_create'. Please make sure following user is created on NameNode.")
            print("User Id: " + params.config['configurations']['activity-conf']['global.activity.analyzer.user'] + "; group : " + params.config['configurations']['cluster-env']['user_group'] )
        else:
            if 'hadoop-env' in params.config['configurations'] and 'hdfs_user' in params.config['configurations']['hadoop-env']:
                try:
                    g  = grp.getgrnam(params.config['configurations']['hadoop-env']['hdfs_user'])
                    User(params.config['configurations']['activity-conf']['global.activity.analyzer.user'],
                      gid = params.config['configurations']['cluster-env']['user_group'],
                      groups = params.config['configurations']['hadoop-env']['hdfs_user']
                     )
                    print ("Created user with additional group " + str(params.config['configurations']['hadoop-env']['hdfs_user']))
                except KeyError:
                    User(params.config['configurations']['activity-conf']['global.activity.analyzer.user'],
                      gid = params.config['configurations']['cluster-env']['user_group']
                     )
                    print ("Created user without additional group " + str(params.config['configurations']['hadoop-env']['hdfs_user']))
            else:
                User(params.config['configurations']['activity-conf']['global.activity.analyzer.user'],
                  gid = params.config['configurations']['cluster-env']['user_group']
                 )
                print ("Created user without hdfs group " )

        user, group = self.get_owner_user_and_group()
        init_d_dir = "/etc/rc.d/init.d"
        distname, version = utils.get_os()
        if distname.startswith('sles') or distname.startswith('suse'):
            init_d_dir = "/etc/init.d"
        cmd = "{sudo} /usr/sbin/hst activity-analyzer setup " + user + ":" + group + " '" + init_d_dir + "'"
        print("Deploying activity analyzer")
        exit_code, output, error = self.execute_command(cmd)




    def uninstall_activity_explorer(self):
        import params
        cmd = "{sudo} " + params.hst_install_home + "/bin/uninstall-activity-explorer.sh "
        print("Removing activity explorer")
        exit_code, output, error = self.execute_command(cmd, failOnError=False)

    def uninstall_activity_analyzer(self):
        import params
        cmd = "{sudo} " + params.hst_install_home + "/bin/uninstall-activity-analyzer.sh "
        print("Removing activity analyzer")
        exit_code, output, error = self.execute_command(cmd, failOnError=False)

    def deploy_component_specific_config(self, env):
        import params
        env.set_params(params)

        # Before changing configs make sure it has right permissions to read
        self.set_permissions(getpass.getuser())

        # Additionally if it is a analyzer also deploy analyzer configs
        if self.component is 'explorer':
            self.configure_activity_explorer(env)
        elif self.component is 'analyzer':
            conf_file_name = params.activity_analyzer_conf_file_name
            conf_params = params.activity_analyzer_config
            self.deploy_ini_config(conf_file_name, conf_params)

            # Write env
            user, group = self.get_owner_user_and_group()
            if ('activity-env' in params.config['configurations'] and 'activity-env-content' in params.config['configurations']['activity-env']):
                # write out activity-env.sh
                activity_env_content = InlineTemplate(params.config['configurations']['activity-env']['activity-env-content'])
                File(params.activity_conf_dir + "/activity-env.sh", content=activity_env_content,
                     owner=user, group=group)


        # Write log4j
        log4j_props = None
        if ('activity-log4j' in params.config['configurations']) and ('activity-log4j-content' in params.config['configurations']['activity-log4j']):
            log4j_props = InlineTemplate(params.config['configurations']['activity-log4j']['activity-log4j-content'])
        if (log4j_props != None):
            File(format(params.activity_log4j_conf_file),
            mode=0644,
            content=log4j_props
            )
        # generate logfeeder patterns:
        generate_logfeeder_input_config('smartsense', Template("input.config-smartsense.json.j2", extra_imports=[default]))

        # If this is not AMS collector, make sure this host has necessary AMS configs
        if (params.is_ams_hbase_distributed == True and  self.am_i_metric_collector() == False ):
            print("This host is not running metrics collector so explicitly deploying ams-hbase-conf that is needed by " + self.component)

            self.execute_command("{sudo} mkdir -p " + params.ams_hbase_conf_dir)
            self.execute_command("{sudo} chmod -R 755 " + params.ams_hbase_conf_dir + "/..")
            self.execute_command("{sudo} chown -R " + params.ams_user + ":" + params.ams_user_group + " " + params.ams_hbase_conf_dir + "/..")
            if ('configurations' in params.config and 'hdfs-site' in  params.config['configurations']):
                XmlConfig("hdfs-site.xml",
                    conf_dir=params.ams_hbase_conf_dir,
                    configurations=params.config['configurations']['hdfs-site'],
                    configuration_attributes=params.config['configuration_attributes']['hdfs-site'],
                    owner=params.ams_user,
                    group=params.ams_user_group,
                    mode=0644
                    )

            if ('configurations' in params.config and 'core-site' in  params.config['configurations']):
                XmlConfig("core-site.xml",
                    conf_dir=params.ams_hbase_conf_dir,
                    configurations=params.config['configurations']['core-site'],
                    configuration_attributes=params.config['configuration_attributes']['core-site'],
                    owner=params.ams_user,
                    group=params.ams_user_group,
                    mode=0644
                )

            if ('configurations' in params.config and 'ams-hbase-site' in  params.config['configurations']):

                merged_ams_hbase_site = {}
                if ('ams-hbase-site' in params.config['configurations']):
                    merged_ams_hbase_site.update(params.config['configurations']['ams-hbase-site'])
                if (params.is_ams_hbase_secured == True and 'ams-hbase-security-site' in params.config['configurations']) :
                    merged_ams_hbase_site.update(params.config['configurations']['ams-hbase-security-site'])

                # Add phoenix client side overrides
                merged_ams_hbase_site['phoenix.query.maxGlobalMemoryPercentage'] = str(params.ams_phoenix_max_global_mem_percent)
                merged_ams_hbase_site['phoenix.spool.directory'] = params.ams_phoenix_client_spool_dir
                merged_ams_hbase_site['hbase.zookeeper.quorum'] = params.phoenix_zk_quorum
                merged_ams_hbase_site['hbase.zookeeper.property.clientPort'] = params.phoenix_zk_port
                merged_ams_hbase_site['zookeeper.znode.parent'] = params.phoenix_zk_node
                merged_ams_hbase_site['hbase.tmp.dir'] = '/var/lib/smartsense/activity-analyzer/tmp/phoenix'


                XmlConfig("hbase-site.xml",
                    conf_dir=params.ams_hbase_conf_dir,
                    configurations=merged_ams_hbase_site,
                    configuration_attributes=params.config['configuration_attributes']['ams-hbase-site'],
                    owner=params.ams_user,
                    group=params.ams_user_group,
                    mode=0644
                )
        # After changing configs make sure it has right permissions
        self.set_permissions()


    def configure_activity_explorer(self, env):
        import params
        import string
        import random
        env.set_params(params)

        if ('configurations' in params.config):
            user, group = self.get_owner_user_and_group()
            if ('activity-zeppelin-site' in params.config['configurations']):
                zeppelin_site_configs = params.config['configurations']['activity-zeppelin-site'].copy()
                if ( 'zeppelin.server.kerberos.principal' in zeppelin_site_configs ):
                    zeppelin_site_configs['zeppelin.server.kerberos.principal'] = zeppelin_site_configs['zeppelin.server.kerberos.principal'].replace('_HOST', params.hostname.lower())
                # write out zeppelin-site.xml
                XmlConfig("zeppelin-site.xml",
                          conf_dir=params.activity_conf_dir,
                          configurations=zeppelin_site_configs,
                          owner=user,
                          group=group
                          )
            if ('activity-zeppelin-env' in params.config['configurations'] and 'activity-zeppelin-env-content' in params.config['configurations']['activity-zeppelin-env']):
                # write out zeppelin-env.sh
                zeppelin_env_content = InlineTemplate(params.config['configurations']['activity-zeppelin-env']['activity-zeppelin-env-content'])
                File(params.activity_conf_dir + "/zeppelin-env.sh", content=zeppelin_env_content,
                     owner=user, group=group)

            if ('activity-zeppelin-interpreter' in params.config['configurations'] and 'activity-zeppelin-interpreter-content' in params.config['configurations']['activity-zeppelin-interpreter']):
                # write out interpreter.json
                zeppelin_interpreter_content = InlineTemplate(params.config['configurations']['activity-zeppelin-interpreter']['activity-zeppelin-interpreter-content'])
                File(params.activity_conf_dir + "/interpreter.json", content=zeppelin_interpreter_content,
                     owner=user, group=group)
                #zeppelin_interpreter_content.get_content()

            if ('activity-zeppelin-shiro' in params.config['configurations']):
                # write out shiro.ini
                shiro_configs = dict(params.config['configurations']['activity-zeppelin-shiro'])
                if 'activity-zeppelin-site' in params.config['configurations'] and 'zeppelin.anonymous.allowed' in params.config['configurations']['activity-zeppelin-site'] and params.config['configurations']['activity-zeppelin-site']['zeppelin.anonymous.allowed'] == True:
                    shiro_configs['urls./**'] =  'anon'
                else:
                    shiro_configs['urls./**'] = 'authc'
                # hash the passwords
                for k, v in shiro_configs.iteritems():
                    if ( k.startswith ('users.') and v is not None):
                      tokens =  v.split(',')
                      # encrypt if not already encrypted
                      if (not tokens[0].startswith("$shiro1$")):
                          salt = ''.join(random.choice(string.ascii_uppercase + string.digits + string.ascii_lowercase) for _ in range(16))
                          tokens[0] = self.encrypt_password(tokens[0], salt, 198482)
                          shiro_configs[k] = ','.join( str(token) for token in tokens)

                self.deploy_ini_config(params.activity_conf_dir + "/shiro.ini", shiro_configs, '640')

    def sha512(self, password, iterations):
        import hashlib
        for i in range(iterations):
            password = hashlib.sha512(password).digest()
        return password

    def encrypt_password(self, password, salt, iterations):
        import base64
        hashed_password = self.sha512(salt + password, iterations)
        return "$".join(["$shiro1", 'SHA-512', str(iterations), base64.b64encode(salt), base64.b64encode(hashed_password)])

    def deploy_ini_config(self, conf_file_name, conf_params, file_mode='644'):
        import params
        import os

        config_file_path = os.path.join(params.activity_conf_dir, conf_file_name)

        # Derive java home from ambari and set it
        java_home = params.java_home
        # print("java_home="+str(java_home))

        config = ConfigParser.ConfigParser()
        config.optionxform = str
        config.read(config_file_path)

        if config_file_path.endswith("activity.ini") :
            if (not config.has_section('java')):
                config.add_section('java')
            config.set('java', 'home', java_home)

            if (not config.has_section('global')):
                config.add_section('global')
            config.set('global', 'run.as.user', params.run_as_user)

            if params.activity_analyzer_jdbc_url != None:
                if (not config.has_section('phoenix')):
                    config.add_section('phoenix')
                config.set('phoenix', 'activity.analyzer.jdbc.url', params.activity_analyzer_jdbc_url)

            if params.ams_jdbc_url != None:
                if (not config.has_section('ams')):
                    config.add_section('ams')
                config.set('ams', 'jdbc.url', params.ams_jdbc_url)

            if (not config.has_section('customer')):
                config.add_section('customer')
            config.set('customer', 'smartsense.id', params.hst_server_config['customer.smartsense.id'])

            if (not config.has_section('cluster')):
                config.add_section('cluster')
            config.set('cluster', 'name', params.config['clusterName'])

        if conf_params != None:
            for k, v in conf_params.iteritems():
                # print("Setting server config " + str(k) + '=' + str(v))
                key = k.split(".", 1)
                if (len(key) != 2):
                    sys.exit("Invalid property " + k + ". Configuration property should be named in the form <section name>.<property name>")
                if (str(v).lower() == "null") and config.has_option(key[0], key[1]):
                    config.remove_option(key[0], key[1])
                else:
                    if (not config.has_section(key[0])):
                        config.add_section (key[0])
                    config.set(key[0], key[1], v)

        print("Writing configs to : " + str(config_file_path))

        # First write to temp file and then rename to avoid partial written files.
        with open(config_file_path + ".tmp", 'w') as config_file:
            sorted_config = self.sort_config(config)
            sorted_config.write(config_file)
        os.rename(config_file_path + ".tmp", config_file_path)
        cmd = "{sudo} chmod " + file_mode + " " + config_file_path
        exit_code, output, error = self.execute_command(cmd, failOnError=True)

    def resolve_and_set_config(self, source_config, target_config, section, key):
        if ( ( target_config.has_section(section) and target_config.has_option( section, key ) ) or  not source_config.has_section(section) or not source_config.has_option( section, key ) )  :
            # do nothing if either config already exists in target config or is not available in source config
            return
        variable_nameing_pattern="\$([a-zA-Z0-9\-\_]+)"
        value = source_config.get(section, key)
        if '$' in value:
            variables = re.findall(variable_nameing_pattern, value)
            for variable in variables:
               self.resolve_and_set_config( source_config, target_config, section, variable)
            self.set_config( target_config, section, key, value)
        else:
            self.set_config( target_config, section, key, value)

    def set_config(self, config, section, key, value ):
        if ( not config.has_section(section) ):
            config.add_section(section)
        config.set(section,key,value)

    def sort_config(self, config):
        sorted_config = ConfigParser.ConfigParser()
        sorted_config.optionxform = str
        for section in sorted(config.sections()):
            for key in sorted(dict(config.items(section)).keys()):
                self.resolve_and_set_config( config, sorted_config, section, key)
        return sorted_config

    def set_permissions(self, run_as_user=None):
        import params
        dir_list = []
        user, group = self.get_owner_user_and_group(run_as_user)

        if self.component is 'explorer':
            dir_list = params.activity_explorer_owned_dirs
            exit_code, output, error = self.execute_command("{sudo} chown '" + user + "':'" + group + "' " + params.activity_explorer_keytab, printOutput=False, failOnError=False)
        elif self.component is 'analyzer':
            dir_list = params.activity_analyzer_owned_dirs
            analyzer_config = ConfigParser.ConfigParser()
            analyzer_config.optionxform = str
            analyzer_config.read(os.path.join(params.activity_conf_dir, params.activity_analyzer_conf_file_name))
            exit_code, output, error = self.execute_command("{sudo} chown '" + user + "':'" + group + "' " + params.activity_analyzer_keytab, printOutput=False, failOnError=False)
            if 'activity-conf' in params.config['configurations'] and 'global.activity.analyzer.user.keytab' in params.config['configurations']['activity-conf']:
                exit_code, output, error = self.execute_command("{sudo} chown '" + user + "':'" + group + "' " + params.config['configurations']['activity-conf']['global.activity.analyzer.user.keytab'], failOnError=False)
            if 'activity-conf' in params.config['configurations'] and 'mr_job.activity.analyzer.user.keytab' in params.config['configurations']['activity-conf']:
                exit_code, output, error = self.execute_command("{sudo} chown '" + user + "':'" + group + "' " + params.config['configurations']['activity-conf']['mr_job.activity.analyzer.user.keytab'], failOnError=False)
            if 'activity-conf' in params.config['configurations'] and 'tez_job.activity.analyzer.user.keytab' in params.config['configurations']['activity-conf']:
                exit_code, output, error = self.execute_command("{sudo} chown '" + user + "':'" + group + "' " + params.config['configurations']['activity-conf']['tez_job.activity.analyzer.user.keytab'], failOnError=False)
            if 'activity-conf' in params.config['configurations'] and 'yarn_app.activity.analyzer.user.keytab' in params.config['configurations']['activity-conf']:
                exit_code, output, error = self.execute_command("{sudo} chown '" + user + "':'" + group + "' " + params.config['configurations']['activity-conf']['yarn_app.activity.analyzer.user.keytab'], failOnError=False)

        for dir in dir_list :
            if (":" in dir):
                dir_path, permissions = dir.split(":")
            else:
                dir_path = dir
                permissions = None
            cmd = "{sudo} mkdir -p " + dir_path
            exit_code, output, error = self.execute_command(cmd, failOnError=False)

            cmd = "{sudo} chown -R '" + user + "':'" + group + "' " + dir_path
            exit_code, output, error = self.execute_command(cmd, failOnError=False)

            if(permissions):
                print("setting permissions: " +permissions)
                cmd = "{sudo} chmod " + permissions + " " + dir_path
                exit_code, output, error = self.execute_command(cmd, failOnError=False)

    def get_owner_user_and_group(self, run_as_user=None):
        import params
        if not run_as_user:
            run_as_user = self.remove_prefix(params.run_as_user, DEFAULT_USER_PREFIX)
        if self.component is 'analyzer':
            if (not run_as_user):
                analyzer_conf_file_path = os.path.join(params.activity_conf_dir, params.activity_analyzer_conf_file_name)
                if os.path.isfile(analyzer_conf_file_path):
                    analyzer_config = ConfigParser.ConfigParser()
                    analyzer_config.optionxform = str
                    analyzer_config.read(analyzer_conf_file_path)
                    if (analyzer_config.has_section('global') and analyzer_config.has_option('global', 'run.as.user')):
                        run_as_user = self.remove_prefix(analyzer_config.get('global', 'run.as.user'), DEFAULT_USER_PREFIX)

        if self.component is 'explorer':
            if (not run_as_user):
                explorer_env_conf_file_path = os.path.join(params.activity_conf_dir, params.activity_explorer_zeppelin_env_file_name)
                if os.path.isfile(explorer_env_conf_file_path):
                    run_as_user_line=''
                    run_as_user=''
                    with open(explorer_env_conf_file_path) as f:
                        for line in f:
                            if "RUN_AS_USER" in line:
                                run_as_user_line = line.replace('export', '').strip().rstrip('\n')
                                print run_as_user_line
                                tmp_run_as_user=run_as_user_line.split('=')[1]
                                print tmp_run_as_user
                                run_as_user = self.remove_prefix(tmp_run_as_user, DEFAULT_USER_PREFIX)
                                break

        if (run_as_user == None or run_as_user.strip() == ""):
            run_as_user = getpass.getuser()
        group = run_as_user
        id_cmd = 'id -gn ' + run_as_user
        p = subprocess.Popen([id_cmd], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, shell=True)
        output, error = p.communicate()
        if (p.returncode == 0 or p.returncode == None) :
            group = output.strip()
        else:
            group = output.user()
        return (run_as_user, group)

    def remove_prefix(self, input_str, prefix):
        if input_str.startswith(prefix):
          return input_str[len(prefix):]
        return input_str

    def get_sudo_command(self):
        if os.geteuid() == 0:
            return ""
        else:
            return "sudo"

    def execute_command(self, command, background=False, failOnError=True, debug=False, printOutput=True):
        normalized_command = command.replace("{sudo}", self.get_sudo_command())
        if (debug == True):
            print ("Command to be executed:" + command)
            print ("Normalized command:" + normalized_command)

        if (background == True):
            p = subprocess.Popen(normalized_command, shell=True)
            # Wait for process to spawn
            time.sleep(3)
            return (0, None, None)

        p = subprocess.Popen(normalized_command, stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE, shell=True)
        stdout, stderr = p.communicate()
        code = p.wait()
        if (debug == True or printOutput == True):
            print("Command: " + str(normalized_command))
            print("Exit code: " + str(code))
            print("Std Out: " + ("None" if stdout == None or len(str(stdout).strip()) == 0 else str(stdout)))
            print("Std Err: " + ("None" if stderr == None or len(str(stderr).strip()) == 0 else str(stderr)))
        if (failOnError == True and code != 0):
            sys.stderr.write("Failed to execute command: " + str(normalized_command) + "; Exit code: " + str(code) + "; stdout: " + str(stdout) + "; stderr: " + str(stderr))
            sys.stderr.flush()
            sys.exit(1)
        return (code, stdout, stderr)

    def am_i_metric_collector(self):
        import params
        current_host = params.hostname
        if current_host in params.config['clusterHostInfo']['metrics_collector_hosts']:
            return True
        else:
            return False

    def pre_upgrade_restart(self, env, upgrade_type=None):
        import params

        # NO Rolling upgrade for SmartSense yet
        #env.set_params(params)
        #if params.version and check_stack_feature(StackFeature.ROLLING_UPGRADE, params.version):
        #  Logger.info("Executing Spark Thrift Server Stack Upgrade pre-restart")
        #  conf_select.select(params.stack_name, "smartsense-activity", params.version)
        #  stack_select.select("smartsense-activity-analyzer", params.version)
        #  stack_select.select("smartsense-activity-explorer", params.version)

if __name__ == "__main__":
    Activity().execute()
