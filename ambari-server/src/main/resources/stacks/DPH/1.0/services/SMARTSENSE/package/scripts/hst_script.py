'''
Copyright (c) 2011-2018, Hortonworks Inc.  All rights reserved.
Except as expressly permitted in a written agreement between you
or your company and Hortonworks, Inc, any use, reproduction,
modification,
redistribution, sharing, lending or other exploitation
of all or any part of the contents of this file is strictly prohibited.
'''

from distutils.version import LooseVersion, StrictVersion
from resource_management.core.exceptions import Fail
from resource_management.core.source import Template
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.generate_logfeeder_input_config import generate_logfeeder_input_config
from resource_management import *
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
import json

DEFAULT_USER_PREFIX='default:'
MAX_RETRY_ATTEMPTS=10
WAIT_INTERVAL_SECS=5

class HSTScript(Script):

    ##################### Directly exposed functions through Ambari #####################
    def __init__(self, component=None):
        self.component = component

    def reinstall(self, env):
        self.install(env)

    def install(self, env):
        import params
        env.set_params(params)
        self.install_packages()
        # packages must be deployed before deploying configs
        self.deploy_component_specific_config(env)
        if self.component is 'agent':
            # self.run_agent_setup()
            print("No specific install steps for agent")
        elif self.component is 'server':
            self.run_server_setup()
            # Find ambari host and install HST view
        # At this time, we dont know which hosts has ambari server running.
        # So Issue view installation for all hosts, only ambari host will pick it up
        self.install_smartsense_view()
        self.create_activity_user()


    # Configuration setting based on configs specified in ambari
    def configure(self, env, upgrade_type=None):
        self.deploy_component_specific_config(env)


    # Start all SmartSense services
    def start(self, env, upgrade_type=None):
        # Before start deploy the latest configs
        self.deploy_component_specific_config(env)
        if self.component is 'server':
            self.start_hst_server()
        elif self.component is 'agent':
            # Agent does not have any start command but register it through setup
            self.run_agent_setup()

    # Stop all SmartSense services
    def stop(self, env, upgrade_type=None):
        if self.component is 'server':
            self.stop_hst_server()
        elif self.component is 'agent':
            try:
                self.unregister(env)
            except:
                print ("Could not gracefully stop as server seems to be down. Stopping anyways.")
                pass

    def status(self, env):
        import params
        if self.component is 'server':
            # use built-in method to check status using pidfile
            print ("Pid file: " + str(params.hst_pid_file))
            check_process_status(params.hst_pid_file)
        elif self.component is 'agent':
            # Check if agent is registered. If registered, then status is alive anything else is dead
            status_cmd = "{sudo} /usr/sbin/hst agent-status"
            exit_code, output, error = self.execute_command(status_cmd)
            if (exit_code == 0 or exit_code == None) and output.strip().lower() == 'registered':
                pass
            else:
                raise ComponentIsNotRunning()


    # Exposed through Ambari service action named "Register"
    def register(self, env):
        if self.component is 'agent':
            self.run_agent_setup()
        # Server does not have register command

    # Exposed through Ambari service action named "Unregister"
    def unregister(self, env):
        if self.component is 'agent':
            cmd = "{sudo} /usr/sbin/hst unregister-agent"
            exit_code, output, error = self.execute_command(cmd)

    # Exposed through Ambari service action named "Refresh_Schedule"
    def refresh_schedule(self, env):
        if self.component is 'agent':
            cmd = "{sudo} /usr/sbin/hst refresh-schedule"
            exit_code, output, error = self.execute_command(cmd)

    # Exposed through Ambari service action named "Capture"
    def capture(self, env):
        import params
        # Only agent has capture command
        if self.component is 'agent':
            config = Script.get_config()
            cmd_params = config['commandParams']
            service = None
            if cmd_params and 'service' in cmd_params:
                service = cmd_params['service']

            case_number = 0
            if cmd_params and 'caseNumber' in cmd_params:
                case_number = cmd_params['caseNumber']

            starttime = None
            if cmd_params and 'startTime' in cmd_params:
                starttime = cmd_params['startTime']

            endtime = None
            if cmd_params and 'endTime' in cmd_params:
                endtime = cmd_params['endTime']

            timezone = None
            if cmd_params and 'timeZone' in cmd_params:
                timezone = cmd_params['timeZone']

            metrics_granularity = None
            if cmd_params and 'metricsGranularity' in cmd_params and cmd_params['metricsGranularity']:
                metrics_granularity = cmd_params['metricsGranularity']

            host = params.hostname
            hosts = []
            if cmd_params and 'hosts' in cmd_params:
                hosts = cmd_params['hosts']

            diagnostics_services_cmd = ''
            if host in hosts and service:
                diagnostic_services = self.find_services_to_collect(service)
                if diagnostic_services:
                    diagnostics_services_cmd = '-d "%s"' % ','.join(diagnostic_services)
                    print("diagnostic_services_cmd: " + diagnostics_services_cmd)

            app_services_cmd = ''
            if cmd_params and 'applicationId' in cmd_params and 'applicationServices' in cmd_params:
                application_services = cmd_params['applicationServices']
                if type(application_services) != list:
                  application_services = str(application_services).split(',')
                application_id = cmd_params['applicationId']
                if application_id and application_services:
                    app_services_cmd = '-a "%s" %s' % (','.join(application_services), application_id)
                    print("app_services_cmd: " + app_services_cmd)

            bundle_key_cmd = ''
            if cmd_params and 'bundle' in cmd_params and cmd_params['bundle']:
                bundle_key_cmd = '-b ' + str(cmd_params['bundle'])
                print "associated bundle key: ", cmd_params['bundle']

            args = {
              "metrics_collection_granularity": metrics_granularity,
              "starttime": starttime,
              "endtime": endtime,
              "timezone": timezone
            }
            extraArgs = ''
            for k, v in args.iteritems():
              if not v is None and v != 'None':
                extraArgs = '%s -C "%s=%s"' % (extraArgs, k, str(v))
            cmd = '{sudo} /usr/sbin/hst capture %s %s %s -c "%s" %s &' % (diagnostics_services_cmd,
                app_services_cmd, bundle_key_cmd, str(case_number), extraArgs)
            try:
                exit_code, output, error = self.execute_command(cmd, background=True)
            except Exception, e:
                message = "Error executing HST capture command.\n" + str(e)
                print message
                sys.exit(message)
            if exit_code != 0:
                message = "Error executing HST capture command. Exited with code %d" % exit_code
                print message
                sys.exit(message)


    def run_server_setup(self):
        cmd = " {sudo} /usr/sbin/hst setup -q --nostart --nocheck"
        print("Setting up HST server")
        exit_code, output, error = self.execute_command(cmd)


    def run_agent_setup(self):
        cmd = "{sudo} /usr/sbin/hst setup-agent -q &"
        try:
            exit_code, output, error = self.execute_command(cmd, background=True)
        except Exception, e:
            message = "Error starting HST agent \n" + str(e)
            print message
            sys.exit(message)

    def start_hst_server(self):
        cmd = ' {sudo} /usr/sbin/hst start '
        exit_code, output, error = self.execute_command(cmd, debug=True)

    def stop_hst_server(self):
        import params
        cmd = ' {sudo} /usr/sbin/hst stop '
        exit_code, output, error = self.execute_command(cmd)
        # delete the pid file
        exit_code, output, error = self.execute_command('rm -f "' + params.hst_pid_file + '"', failOnError=False)

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
        while attempts < MAX_RETRY_ATTEMPTS:
            attempts += 1
            try:
                exit_code, output, error = self.execute_command(cmd)
                if (exit_code == 0):
                    break
            except Exception, e:
                print "Failed to install during attempt " + str (attempts)
                print e
            if (attempts < MAX_RETRY_ATTEMPTS):
                print "Waiting "+str(WAIT_INTERVAL_SECS)+" seconds for next retry"
                time.sleep(WAIT_INTERVAL_SECS)

    def uninstall_hst_packages(self):
        import params
        distname, version = utils.get_os()
        removal_command = ""
        for package in params.smartsense_packages:
            if distname.startswith('ubuntu') or distname.startswith('debian'):
                removal_command = '{sudo} ! dpkg-query -l  | grep  "ii\s*' + package + '" || {sudo} apt-get -y remove ' + package + ' || {sudo} dpkg -r ' + package
            elif distname.startswith('sles') or distname.startswith('suse'):
                removal_command = "{sudo} ! rpm -qa | grep " + package + " || {sudo} zypper remove --no-confirm " + package + " || {sudo} rpm -e " + package
            else:
                removal_command = "{sudo} ! rpm -qa | grep " + package + " || {sudo} yum -y erase " + package + " || {sudo} rpm -e " + package
            print("Uninstalling using command: " + removal_command)
            exit_code, output, error = self.execute_command(removal_command)

    def upgrade_packages(self):
        import params
        import time
        distname, version = utils.get_os()
        major_version = version.split(".")[0]

        if distname.startswith('ubuntu') or distname.startswith('debian'):
            cmd = "{sudo} apt-get -o Dpkg::Options::=--force-confdef --allow-unauthenticated --assume-yes install smartsense-hst"
        elif distname.startswith('sles') or distname.startswith('suse'):
            cmd = "{sudo} zypper up --auto-agree-with-licenses --no-confirm smartsense-hst"
        else:
            cmd = "{sudo} yum -y upgrade smartsense-hst"
        print("upgrading using command: " + cmd)

        attempts = 0
        while attempts < MAX_RETRY_ATTEMPTS:
            attempts += 1
            try:
                exit_code, output, error = self.execute_command(cmd)
                if (exit_code == 0):
                    break
            except Exception, e:
                print "Failed to install during attempt " + str (attempts)
                print e
            if (attempts < MAX_RETRY_ATTEMPTS):
                print "Waiting "+str(WAIT_INTERVAL_SECS)+" seconds for next retry"
                time.sleep(WAIT_INTERVAL_SECS)

    def deploy_component_specific_config(self, env):
        import params

        # Before making any config changes validate if the versions match
        self.validate_versions(False)
        # Before changing configs make sure it has right permissions to read
        self.set_permissions(getpass.getuser())
        # Always deploy agent configs as all hosts are expected to have agent configs.
        conf_file_name = params.hst_agent_conf_file_name
        conf_params = params.hst_agent_config
        self.deploy_config(env, conf_file_name, conf_params)

        # Additionally if it is a server also deploy server configs
        if self.component is 'server':
            conf_file_name = params.hst_server_conf_file_name
            conf_params = params.hst_server_config
            # server.run.as.user cannot be added in deploy_config as we do not want this in hst-agent.ini
            conf_params.update({'server.run.as.user':params.run_as_user})
            self.deploy_config(env, conf_file_name, conf_params)

        # After changing configs make sure it has right permissions
        self.set_permissions()
        self.create_activity_user()

    def deploy_config(self, env, conf_file_name, conf_params):
        import params
        import os
        env.set_params(params)

        config_file_path = os.path.join(params.hst_conf_dir, conf_file_name)

        # Derive java home from ambari and set it
        java_home = params.java_home
        # print("java_home="+str(java_home))

        # Derive cluster name from ambari and set it
        cluster_name = params.config['clusterName']
        # print("cluster_name="+str(cluster_name))

        # Derive if cluster is secured
        security_enabled = str(params.config['configurations']['cluster-env']['security_enabled']).lower()
        # print("security_enabled="+str(security_enabled))

        config = ConfigParser.RawConfigParser()
        config.read(config_file_path)
        if (not config.has_section('server')):
            config.add_section('server')
        config.set('server', 'hostname', params.hst_server_host)

        if (not config.has_section('java')):
            config.add_section('java')
        config.set('java', 'home', java_home)

        if (not config.has_section('cluster')):
            config.add_section('cluster')
        config.set('cluster', 'name', cluster_name)
        config.set('cluster', 'secured', security_enabled)
        config.set('cluster', 'product_name', params.product_name)
        config.set('cluster', 'product_version', params.product_version)
        config.set('cluster', 'stack_root', params.stack_root)

        thisUser, configuredUser = self.get_server_configured_users(config)

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
            config.write(config_file)
        os.rename(config_file_path + ".tmp", config_file_path)
        cmd = "{sudo} chmod 644 " + config_file_path
        exit_code, output, error = self.execute_command(cmd, failOnError=True)

        fileOwner = self.remove_prefix(params.run_as_user, DEFAULT_USER_PREFIX)
        print("fileOwner for config files:" +fileOwner)

        # # Write anonymization rules
        # No need to write to tmp as whole content is in Ambari and partial written files will be auto fixed on retry
        anonymization_rules = None
        if 'anonymization-rules' in params.config['configurations'] and 'anonymization-rules-content' in params.config['configurations']['anonymization-rules']:
            anonymization_rules = InlineTemplate(params.config['configurations']['anonymization-rules']['anonymization-rules-content'])
            if self.validate_json_content(anonymization_rules.get_content()) == False:
                sys.exit("Anonymization rules content is not well-formed. Needs to be fixed to complete the deployment or to apply the changes.")
        if (anonymization_rules != None):
            File(format(params.anonymization_rules_json),
            owner=fileOwner,
            mode=0644,
            content=anonymization_rules
            )

        # # Write product info
        product_info = None
        if 'product-info' in params.config['configurations'] and 'product-info-content' in params.config['configurations']['product-info']:
            flexSubscriptionId = params.config['configurations']['hst-server-conf']['customer.flex.subscription.id'] \
                if params.config['configurations']['hst-server-conf'] else ''
            stackName = ''
            if 'clusterLevelParams' in params.config and 'stack_name' in params.config['clusterLevelParams']:
                stackName = params.config['clusterLevelParams']['stack_name']
            elif 'hostLevelParams' in params.config and 'stack_name' in params.config['hostLevelParams']:
                stackName = params.config['hostLevelParams']['stack_name']

            stackVersion = ''
            if 'clusterLevelParams' in params.config and 'stack_version' in params.config['clusterLevelParams']:
                stackVersion = params.config['clusterLevelParams']['stack_version']
            elif 'hostLevelParams' in params.config and 'stack_version' in params.config['hostLevelParams']:
                stackVersion = params.config['hostLevelParams']['stack_version']

            product_info = InlineTemplate(params.config['configurations']['product-info']['product-info-content'], [],
                clusterName=cluster_name, flexSubscriptionId=flexSubscriptionId, stackName=params.product_name, stackVersion=params.product_version)
            if self.validate_json_content(product_info.get_content()) == False:
                sys.exit("Product info content is not well-formed. Needs to be fixed to complete the deployment or to apply the changes.")
        if (product_info != None):
            File(format(params.product_info_json),
            owner=fileOwner,
            mode=0644,
            content=product_info
            )

        # # Write log4j
        # No need to write to tmp as whole content is in Ambari and partial written files will be auto fixed on retry
        log4j_props = None
        if ('hst-log4j' in params.config['configurations']) and ('hst-log4j-content' in params.config['configurations']['hst-log4j']):
            log4j_props = InlineTemplate(params.config['configurations']['hst-log4j']['hst-log4j-content'])
        if (log4j_props != None):
            File(format(params.log4j_conf_file),
            owner=fileOwner,
            mode=0644,
            content=log4j_props
            )
        # generate logfeeder patterns:
        generate_logfeeder_input_config('smartsense', Template("input.config-smartsense.json.j2", extra_imports=[default]))

    def find_services_to_collect(self, diagnostic_services):
        import params

        print("diagnostic_services: " + diagnostic_services)

        supported_services = self.find_supported_services()

        requested_services = []
        if diagnostic_services.lower() == 'all':
            requested_services = supported_services
        else:
            supported_services = [ x.lower() for x in supported_services ]
            requested_services = [ x for x in diagnostic_services.split(',') if x.lower() in supported_services ]
        return requested_services

    def find_supported_services(self):
        import params
        cmd = "{sudo} /usr/sbin/hst list-services -k"
        exit_code, output, error = self.execute_command(cmd, failOnError=False)
        if exit_code == 0:
            return set(re.findall(r"[\w']+", output.lower()))
        else:
            return ['accumulo', 'ambari', 'ams', 'atlas', 'cloudbreak', 'falcon', 'hbase', 'hdfs', 'hive', 'kafka', 'knox', 'logsearch', 'mr', 'oozie', 'pig', 'ranger', 'smartsense', 'spark', 'sqoop', 'storm', 'tez', 'yarn', 'zeppelin', 'zk']


    def create_activity_user(self):
        import params
        import grp

        if self.am_i_namenode() == True:
            print("This is found to be namenode. Creating smartsense activity user.")

            ignore_groupsusers_create = False
            sysprep_skip_create_users_and_groups = False
            if 'sysprep_skip_create_users_and_groups' in params.config['configurations']['cluster-env']:
                sysprep_skip_create_users_and_groups = params.config['configurations']['cluster-env']['sysprep_skip_create_users_and_groups']
                print ('sysprep_skip_create_users_and_groups', sysprep_skip_create_users_and_groups)

            if 'ignore_groupsusers_create' in params.config['configurations']['cluster-env']:
                ignore_groupsusers_create = params.config['configurations']['cluster-env']['ignore_groupsusers_create']
                print ('ignore_groupsusers_create', ignore_groupsusers_create)

            if sysprep_skip_create_users_and_groups and ( sysprep_skip_create_users_and_groups == True or str(sysprep_skip_create_users_and_groups).lower() == 'true'):
                print("Skipping activity analyzer user creation as specified by ambari config 'sysprep_skip_create_users_and_groups'. Please make sure following user is created on NameNode.")
                print("User Id: " + params.config['configurations']['activity-conf']['global.activity.analyzer.user'] + "; group : " + params.config['configurations']['cluster-env']['user_group'])
            elif ignore_groupsusers_create and ( ignore_groupsusers_create == True or str(ignore_groupsusers_create).lower() == 'true'):
                print("Skipping activity analyzer user creation as specified by ambari config 'ignore_groupsusers_create'. Please make sure following user is created on NameNode.")
                print("User Id: " + params.config['configurations']['activity-conf']['global.activity.analyzer.user'] + "; group : " + params.config['configurations']['cluster-env']['user_group'])
            else:
                if 'hadoop-env' in params.config['configurations'] and 'hdfs_user' in params.config['configurations']['hadoop-env']:
                    try:
                        g = grp.getgrnam(params.config['configurations']['hadoop-env']['hdfs_user'])
                        User(params.config['configurations']['activity-conf']['global.activity.analyzer.user'],
                          gid=params.config['configurations']['cluster-env']['user_group'],
                          groups=params.config['configurations']['hadoop-env']['hdfs_user']
                         )
                        print ("Created user with additional group " + str(params.config['configurations']['hadoop-env']['hdfs_user']))
                    except KeyError:
                        User(params.config['configurations']['activity-conf']['global.activity.analyzer.user'],
                          gid=params.config['configurations']['cluster-env']['user_group']
                         )
                        print ("Created user without additional group " + str(params.config['configurations']['hadoop-env']['hdfs_user']))
                else:
                    User(params.config['configurations']['activity-conf']['global.activity.analyzer.user'],
                      gid=params.config['configurations']['cluster-env']['user_group']
                     )
                    print ("Created user without hdfs group ")


    def install_smartsense_view(self):
        import params
        import os.path
        import filecmp

        # Try installing only if this is ambari host, else ignore.
        if self.am_i_ambari_server() == True:
            print("This is found to be ambari server host. Installing the SmartSense view.")

            if (not os.path.isfile(params.ambari_view_jar_file_path)):
                print("View jar " + params.ambari_view_jar_file_path + " does not exist. Copying new.")
            elif (not filecmp.cmp (params.hst_view_jar_file_path, params.ambari_view_jar_file_path, shallow=False)):
                print("View jar " + params.ambari_view_jar_file_path + " exists but does not match with packaged one. Overwriting it.")
            else:
                print ("View is already deployed nothing to do.")
                return
            cmd = '{sudo} rm -rf ' + params.ambari_view_dir + '/work/*SMARTSENSE* ;  {sudo} cp ' + params.hst_view_jar_file_path + ' ' + params.ambari_view_jar_file_path + ' ; {sudo} chmod 644 ' +  params.ambari_view_jar_file_path
            exit_code, output, error = self.execute_command(cmd)

        else:
            print ("This is not ambari server host so nothing to install.")


    def am_i_ambari_server(self):
        import params
        import os
        current_host = params.hostname
        print ("This host is identified as " + current_host + " and ambari server is identified as " + str(params.ambari_server_host))
        if current_host in params.ambari_server_host:
            return True
        elif(os.path.exists(params.ambari_view_dir)):
            print ("Ambari server hostname did not match, however there is ambari views directory on this host.")
            return True
        else:
            return False

    def am_i_namenode(self):
        import params
        if 'namenode_host' in params.config['clusterHostInfo']:
            current_host = params.hostname
            print ("This host is identified as " + current_host + " and namenode is identified as " + str(params.config['clusterHostInfo']['namenode_host']))
            if current_host in params.config['clusterHostInfo']['namenode_host']:
                return True
            else:
                return False
        else:
            return False


    def set_permissions(self, run_as_user=None):
        import params
        if self.component is 'agent':
            dir_list = params.hst_agent_owned_dirs
            if (not run_as_user):
                agent_config = ConfigParser.RawConfigParser()
                agent_config.read(os.path.join(params.hst_conf_dir, params.hst_agent_conf_file_name))
                thisUser, configuredUser = self.get_agent_configured_users(agent_config)
                run_as_user = self.remove_prefix( params.run_as_user, DEFAULT_USER_PREFIX)

        elif self.component is 'server':
            dir_list = params.hst_server_owned_dirs
            if (not run_as_user):
                server_config = ConfigParser.RawConfigParser()
                server_config.read(os.path.join(params.hst_conf_dir, params.hst_server_conf_file_name))
                thisUser, configuredUser = self.get_server_configured_users(server_config)
                run_as_user = self.remove_prefix( params.run_as_user, DEFAULT_USER_PREFIX)

        if (run_as_user == None or run_as_user.strip() == ""):
            run_as_user = getpass.getuser()

        print("owner for set_permissions:"+run_as_user)
        id_cmd = 'id -gn ' + run_as_user
        p = subprocess.Popen([id_cmd], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, shell=True)
        output, error = p.communicate()
        if (p.returncode == 0 or p.returncode == None) :
            group = output.strip()
        else:
            group = output.user()

        for dir in dir_list :
            if (":" in dir):
                dir_path, permissions = dir.split(":")
            else:
                dir_path = dir
                permissions = None
            cmd = "{sudo} mkdir -p " + dir_path
            exit_code, output, error = self.execute_command(cmd, failOnError=False)

            cmd = "{sudo} chown -R '" + run_as_user + "':'" + group + "' " + dir_path
            exit_code, output, error = self.execute_command(cmd, failOnError=False)

            if(permissions):
                print("setting permissions: " +permissions)
                cmd = "{sudo} chmod " + permissions + " " + dir_path
                exit_code, output, error = self.execute_command(cmd, failOnError=False)

    def get_sudo_command(self):
        if os.geteuid() == 0:
            return ""
        else:
            return "sudo"

    def get_server_configured_users(self, config):
        thisUser = getpass.getuser()
        if ((not config.has_option('server', 'run.as.user')) or config.get('server', 'run.as.user') == None or config.get('server', 'run.as.user').strip == ""):
            configuredUser = ''
        else:
            configuredUser = config.get('server', 'run.as.user')
        return thisUser,configuredUser

    def get_agent_configured_users(self, config):
        thisUser = getpass.getuser()
        if ((not config.has_option('agent', 'run.as.user')) or config.get('agent', 'run.as.user') == None or config.get('agent', 'run.as.user').strip == ""):
            configuredUser = ''
        else:
            configuredUser = config.get('agent', 'run.as.user')
        return thisUser,configuredUser

    def remove_prefix(self, input_str, prefix):
        if input_str.startswith(prefix):
          return input_str[len(prefix):]
        return input_str

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

    def pre_upgrade_restart(self, env, upgrade_type=None):
        import params

        # NO Rolling upgrade for SmartSense yet
        # env.set_params(params)
        # if params.version and check_stack_feature(StackFeature.ROLLING_UPGRADE, params.version):
        #  Logger.info("Executing Spark Thrift Server Stack Upgrade pre-restart")
        #  conf_select.select(params.stack_name, "smartsense", params.version)
        #  stack_select.select("smartsense-hst", params.version)

    def get_max_version(self, config_version, scripts_version, rpm_version):
        versions = [config_version, scripts_version, rpm_version]
        sorted_versions = sorted(versions, key=LooseVersion, reverse=True) # reverse for descending
        return str(sorted_versions[0])

    def validate_versions(self, is_this_retry=False):
        import params

        print "validating versions"

        config_version = "1.1.0"
        scripts_version = "1.5.0.2.7.1.0-169"
        rpm_version = None
        if 'hst-agent-conf' in params.config['configurations'] and 'agent.version' in params.config['configurations']['hst-agent-conf']:
            config_version = params.config['configurations']['hst-agent-conf']['agent.version']

        command = "hst --version"
        failure_message = "ERROR: Could not get hst version. Please make sure smartsense-hst package is installed on this host."
        code, rpm_version, error = self.execute_command(command, debug=False)
        rpm_version = rpm_version.strip()
        max_version = self.get_max_version(config_version, scripts_version, rpm_version)

        if (max_version == scripts_version):
            message_prefix = "It appears that Ambari was upgraded but post upgrade steps to upgrade SmartSense were not completed.\n"
        elif (max_version == rpm_version):
            message_prefix = "It appears that SmartSense package was upgraded but some upgrade steps were not completed.\n"
        else:
            message_prefix = "It appears that SmartSense setup/upgrade was incomplete.\n"

        message_suffix = "\n\nPerform following steps to finish the upgrade:\n" \
        "1. Make sure that the smartsense-hst package on every host is upgraded to the latest version ("+max_version+" or higher).\n" \
        "2. run 'hst upgrade-ambari-service' on Ambari server host to finish the upgrade.\n" \
        "3. Restart all SmartSense components.\n\n" \
        "Refer SmartSense upgrade documentation available at https://docs.hortonworks.com/ for more details."

        if (LooseVersion(rpm_version) < LooseVersion(scripts_version)):
            if is_this_retry == False:
                print(message_prefix + "SmartSense stack version in Ambari is higher (" + scripts_version + ") than installed smartsense-hst package version (" + rpm_version + "). Upgrading smartsense-hst package on this host.")
                self.upgrade_packages()
                self.validate_versions(True)
            else:
                sys.exit(message_prefix + "SmartSense stack version in Ambari is higher (" + scripts_version + ") than installed smartsense-hst package version (" + rpm_version + ")."+ message_suffix)

        elif LooseVersion(rpm_version) < (LooseVersion(config_version)):
            if is_this_retry == False:
                print(message_prefix + "SmartSense configuration version in Ambari is higher (" + config_version + ") than installed smartsense-hst package version (" + rpm_version + "). Upgrading smartsense-hst package on this host.")
                self.upgrade_packages()
                self.validate_versions(True)
            else:
                sys.exit(message_prefix + "SmartSense configuration version in Ambari is higher (" + config_version + ") than installed smartsense-hst package version (" + rpm_version + ")."+ message_suffix)

        elif (LooseVersion(config_version) < LooseVersion(rpm_version)):
            sys.exit(message_prefix + "SmartSense configuration version in Ambari is lower (" + config_version + ") than installed smartsense-hst package version (" + rpm_version + ")."+ message_suffix)

        elif (LooseVersion(config_version) < LooseVersion(scripts_version)):
            sys.exit(message_prefix + "SmartSense configuration version in Ambari is lower (" + config_version + ") than Ambari smartsense stack version (" + scripts_version + ")."+ message_suffix)

        elif (LooseVersion(scripts_version) < LooseVersion(rpm_version)):
            sys.exit(message_prefix + "SmartSense stack version in Ambari is lower (" + scripts_version + ") than installed smartsense-hst package version (" + rpm_version + ")."+ message_suffix)
        elif (LooseVersion(config_version) > LooseVersion(scripts_version)):
            sys.exit(message_prefix + "SmartSense configuration version in Ambari is higher (" + config_version + ") Ambari smartsense  stack version (" + scripts_version + ")."+ message_suffix)

    def validate_json_content(self, content):
        if not content:
            print "Validation Failed: Input json content is invalid."
            return False
        try:
            json.loads(content)
        except Exception, e:
            print "Validation Failed: Input json is not valid. ", str(e)
            return False
        return True

if __name__ == "__main__":
    HSTScript().execute()
