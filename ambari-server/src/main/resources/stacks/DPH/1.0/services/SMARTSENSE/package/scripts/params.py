'''
Copyright (c) 2011-2018, Hortonworks Inc.  All rights reserved.
Except as expressly permitted in a written agreement between you
or your company and Hortonworks, Inc, any use, reproduction,
modification,
redistribution, sharing, lending or other exploitation
of all or any part of the contents of this file is strictly prohibited.
'''

from resource_management.libraries.functions import format
from resource_management.libraries.script.script import Script
import os
import os.path
import json
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.substitute_vars import substitute_vars
import getpass




DEFAULT_USER_PREFIX='default:'
# scenarios for get run as user
#   thisUser     default  configuredUser  return
#  ------------  -------  -------------  ---------
#  root
#  root                    root
#  root                    abc            abc
#  root          default   root
#  root          default   abc
#  abc                                    default + abc
#  abc                     root           root
#  abc                     abc            default + abc
#  abc           default   abc            default + abc
#  abc           default   root           default + abc
#  abc           default   xyz            default + abc
#  abc                     xyz            xyz
def find_run_as_user(configuredUser):
   import getpass

   thisUser = getpass.getuser()
   if(configuredUser == None):
      configuredUser = ''
   if ( 'default:' in configuredUser ):
      default = 'default'
      configuredUserName = configuredUser.split(':')[1] ;
   else:
      default = ''
      configuredUserName = configuredUser ;
   #print("thisUser:"+thisUser+".")
   #print("configuredUserName:"+configuredUserName+".")
   userMap = {}
   if(thisUser == 'root'):
       userMap['root' + '' + '' ] = '' ;
       userMap['root' + '' + 'root' ] = '' ;
       if('root' != configuredUserName):
           userMap['root' + '' +  configuredUserName] = configuredUserName ;
       userMap['root' + 'default' + 'root' ] = '' ;
       userMap['root' + 'default' +  configuredUserName] = '' ;
   else:
       userMap[thisUser + '' + '' ] = DEFAULT_USER_PREFIX + thisUser ;
       userMap[thisUser + '' +  'root'] = 'root' ;
       if(thisUser == configuredUserName or not configuredUserName):
           userMap[thisUser + '' +  configuredUserName ] = DEFAULT_USER_PREFIX + thisUser ;
       else:
           userMap[thisUser + '' +  configuredUserName ] = configuredUserName ;
       userMap[thisUser + 'default' + configuredUserName ] = DEFAULT_USER_PREFIX + thisUser ;
       userMap[thisUser + 'default' + 'root' ] = DEFAULT_USER_PREFIX + thisUser ;
       userMap[thisUser + 'default' +  configuredUserName ] = DEFAULT_USER_PREFIX + thisUser ;
   #print("returning userMap:" +str(userMap[thisUser + default + configuredUserName]))
   return userMap[thisUser + default + configuredUserName] ;



# config object that holds the configurations declared in the -config.xml file
config = Script.get_config()

# RPM versioning support
# rpm_version = default("/configurations/cluster-env/rpm_version", None)

ambari_view_dir = "/var/lib/ambari-server/resources/views"
ambari_agent_data_dir = "/var/lib/ambari-agent/data/"
ambari_server_resources_dir = "/var/lib/ambari-server/resources"
ambari_agent_cache_dir = "/var/lib/ambari-agent/cache"
if 'agentLevelParams' in config and 'agentCacheDir' in config['agentLevelParams']:
    ambari_agent_cache_dir = config['agentLevelParams']['agentCacheDir']
elif 'hostLevelParams' in config and 'agentCacheDir' in config['hostLevelParams']:
    ambari_agent_cache_dir = config['hostLevelParams']['agentCacheDir']

service_package_folder = ambari_agent_cache_dir + "/stacks/HDP/2.1/services/SMARTSENSE/package"
if 'serviceLevelParams' in config and 'service_package_folder' in config['serviceLevelParams']:
    service_package_folder = ambari_agent_cache_dir +  "/" + config['serviceLevelParams']['service_package_folder']
elif 'commandParams' in config and 'service_package_folder' in config['commandParams']:
    service_package_folder = ambari_agent_cache_dir +  "/" + config['commandParams']['service_package_folder']

hst_log_dir = '/var/log/hst'
if 'hst-env' in config['configurations'] and 'hst_log_dir' in config['configurations']['hst-env']:
        hst_log_dir = config['configurations']['hst-env']['hst_log_dir']

activity_log_dir = '/var/log/smartsense-activity'
if 'activity-log4j' in config['configurations'] and 'activity_log_dir' in config['configurations']['activity-log4j']:
        activity_log_dir = config['configurations']['activity-log4j']['activity_log_dir']

hst_conf_dir = '/etc/hst/conf'
# Env properties
if 'hst-env' in config['configurations'] and 'hst_conf_dir' in config['configurations']['hst-env']:
        hst_conf_dir = config['configurations']['hst-env']['hst_conf_dir']

activity_conf_dir = '/etc/smartsense-activity/conf'
if 'activity-env' in config['configurations'] and 'activity_conf_dir' in config['configurations']['activity-env']:
        activity_conf_dir = config['configurations']['activity-env']['activity_conf_dir']

log4j_conf_file = os.path.join(hst_conf_dir, 'log4j.properties');
activity_log4j_conf_file = os.path.join(activity_conf_dir, 'log4j.properties');
hst_win_service_name = 'hst'
hst_pid_dir = '/var/run/hst'
if 'hst-env' in config['configurations'] and 'hst_pid_dir' in config['configurations']['hst-env']:
        hst_pid_dir = config['configurations']['hst-env']['hst_pid_dir']


activity_analyzer_pid_dir = '/var/run/smartsense-activity-analyzer'
if 'activity-env' in config['configurations'] and 'activity_analyzer_pid_dir' in config['configurations']['activity-env']:
        activity_analyzer_pid_dir = config['configurations']['activity-env']['activity_analyzer_pid_dir']
activity_explorer_pid_dir = '/var/run/smartsense-activity-explorer'
if 'activity-env' in config['configurations'] and 'activity_explorer_pid_dir' in config['configurations']['activity-env']:
        activity_analyzer_pid_dir = config['configurations']['activity-env']['activity_explorer_pid_dir']
hst_server_conf_file_name = 'hst-server.ini'
activity_analyzer_conf_file_name = 'activity.ini'
activity_explorer_zeppelin_env_file_name='zeppelin-env.sh'
hst_agent_conf_file_name = 'hst-agent.ini'
hst_install_home = '/usr/hdp/share/hst'
hst_agent_home = os.path.join(hst_install_home, 'hst-agent')
hst_server_home = os.path.join(hst_install_home, 'hst-server')
anonymization_rules_json = os.path.join(hst_conf_dir, 'anonymization_rules.json')
product_info_json = os.path.join(hst_conf_dir, 'productinfo.json')
smartsense_packages = ["smartsense-hst"]
hst_files_dir = os.path.join(service_package_folder, 'files')
hst_view_dir = os.path.join(hst_files_dir, 'view')
# TODO Read these from ambari config files
ambari_view_dir = "/var/lib/ambari-server/resources/views"
ambari_agent_data_dir = "/var/lib/ambari-agent/data/"
hst_view_jar_file_name = 'smartsense-ambari-view-1.5.0.2.7.1.0-169.jar'
hst_view_jar_file_path = os.path.join(hst_view_dir, hst_view_jar_file_name)
ambari_view_jar_file_path = os.path.join(ambari_view_dir, hst_view_jar_file_name)


# #TODO The variable names should be different for hst and activity
# Env properties
if 'hst-log4j' in config['configurations']:
    if 'hst_log_dir' in config['configurations']['hst-log4j']:
        hst_log_dir = config['configurations']['hst-log4j']['hst_log_dir']
    if 'hst_max_file_size' in config['configurations']['hst-log4j']:
        hst_max_file_size = config['configurations']['hst-log4j']['hst_max_file_size']
    if 'hst_max_backup_index' in config['configurations']['hst-log4j']:
        hst_max_backup_index = config['configurations']['hst-log4j']['hst_max_backup_index']

# Path to HST PID file.
hst_pid_file = os.path.join(hst_pid_dir, 'hst-server.pid')
activity_analyzer_pid_file = os.path.join(activity_analyzer_pid_dir, 'activity-analyzer.pid')
activity_explorer_pid_file = os.path.join(activity_explorer_pid_dir, 'activity-explorer.pid')

hst_server_host = 'localhost'
if 'clusterHostInfo' in config and 'hst_server_hosts' in config['clusterHostInfo']:
    hst_server_host = config['clusterHostInfo']['hst_server_hosts'][0]

# #TODO The variable names should be different for hst and activity
# Env properties
if 'activity-log4j' in config['configurations']:
    if 'activity_log_dir' in config['configurations']['activity-log4j']:
        activity_log_dir = config['configurations']['activity-log4j']['activity_log_dir']
    if 'activity_max_file_size' in config['configurations']['activity-log4j']:
        activity_max_file_size = config['configurations']['activity-log4j']['activity_max_file_size']
    if 'activity_max_backup_index' in config['configurations']['activity-log4j']:
        activity_max_backup_index = config['configurations']['activity-log4j']['activity_max_backup_index']


activity_explorer_home = os.path.join(hst_install_home, "activity-explorer")
activity_analyzer_home = os.path.join(hst_install_home, "activity-analyzer")
activity_explorer_notebook_dir = "/var/lib/smartsense/activity-explorer/notebook"

hst_server_owned_dirs = [
                    '/etc/hst',
                    hst_log_dir+':750',
                    hst_conf_dir,
                    hst_pid_dir,
                    "/var/lib/smartsense/hst-gateway",
                    "/var/lib/smartsense/hst-common",
                    "/var/lib/smartsense/hst-server"
                    ]
activity_analyzer_owned_dirs = [
                    "/var/lib/smartsense/activity-analyzer",
                    '/etc/smartsense-activity',
                    '/var/log/smartsense-activity:750',
                    '/var/run/smartsense-activity-analyzer',
                    activity_log_dir+':750',
                    activity_conf_dir,
                    activity_analyzer_pid_dir
                    ]
activity_explorer_owned_dirs = [
                    "/var/lib/smartsense/activity-explorer",
                    '/etc/smartsense-activity',
                    '/var/log/smartsense-activity:750',
                    '/var/run/smartsense-activity-explorer',
                    activity_log_dir+':750',
                    activity_conf_dir,
                    activity_explorer_pid_dir
                    ]

hst_agent_owned_dirs = [
                    '/etc/hst',
                    hst_log_dir+':750',
                    hst_conf_dir,
                    "/var/lib/smartsense/hst-agent"
                    ]

if ('hst-server-conf' in config['configurations'] and 'server.storage.dir' in config['configurations']['hst-server-conf']) :
    hst_server_owned_dirs.append(config['configurations']['hst-server-conf']['server.storage.dir'])

if ('hst-server-conf' in config['configurations'] and 'server.tmp.dir' in config['configurations']['hst-server-conf']) :
    hst_server_owned_dirs.append(config['configurations']['hst-server-conf']['server.tmp.dir'])

if ('hst-agent-conf' in config['configurations'] and 'server.tmp_dir' in config['configurations']['hst-agent-conf']) :
    hst_agent_owned_dirs.append(config['configurations']['hst-agent-conf']['agent.tmp_dir'])


# Configurations
hst_server_config = None
if ('hst-server-conf' in config['configurations']) :
    hst_server_config = config['configurations']['hst-server-conf']

activity_analyzer_config = None
if ('activity-conf' in config['configurations']) :
    activity_analyzer_config = config['configurations']['activity-conf']

hst_agent_config = None
if ('hst-agent-conf' in config['configurations']) :
    hst_agent_config = config['configurations']['hst-agent-conf']


security_enabled = False
if 'cluster-env' in config['configurations'] and 'security_enabled' in config['configurations']['cluster-env']:
    if (str(config['configurations']['cluster-env']['security_enabled']).upper() == 'TRUE'):
        security_enabled = True

hostname = config['agentLevelParams']['hostname'] if 'agentLevelParams' in config else config['hostname']
ambari_server_host = config['ambariLevelParams']['ambari_server_host'] if 'ambariLevelParams' in config else config['clusterHostInfo']['ambari_server_host']
java_home = config['ambariLevelParams']['java_home'] if 'ambariLevelParams' in config else config['hostLevelParams']['java_home']
phoenix_zk_quorum = "localhost"
phoenix_zk_port = 61181
phoenix_zk_node = "/hbase"

if "ams-hbase-site" in config['configurations'] and 'hbase.zookeeper.property.clientPort' in config['configurations']['ams-hbase-site']:
    phoenix_zk_port = config['configurations']['ams-hbase-site']['hbase.zookeeper.property.clientPort']

if "ams-hbase-site" in config['configurations'] and 'hbase.zookeeper.quorum' in config['configurations']['ams-hbase-site']:
    phoenix_zk_quorum = config['configurations']['ams-hbase-site']['hbase.zookeeper.quorum']

if "ams-hbase-site" in config['configurations'] and 'zookeeper.znode.parent' in config['configurations']['ams-hbase-site']:
    phoenix_zk_node = config['configurations']['ams-hbase-site']['zookeeper.znode.parent']


is_ams_hbase_distributed = False

if 'ams-hbase-site' in config['configurations'] and 'hbase.cluster.distributed' in config['configurations']['ams-hbase-site']:
    if (str(config['configurations']['ams-hbase-site']['hbase.cluster.distributed']).upper() == 'TRUE'):
        is_ams_hbase_distributed = True

is_ams_hbase_secured = False

if (is_ams_hbase_distributed == True and security_enabled == True) :
    is_ams_hbase_secured = True

if phoenix_zk_quorum.startswith('{{'):
    if is_ams_hbase_distributed == False:
        if 'metrics_collector_hosts' in config['clusterHostInfo']:
            phoenix_zk_quorum = config['clusterHostInfo']['metrics_collector_hosts'][0]
        else:
            phoenix_zk_quorum = 'localhost'
    else:
        if 'zookeeper_hosts' in config['clusterHostInfo']:
            phoenix_zk_quorum = ",".join(config['clusterHostInfo']['zookeeper_hosts'])
        elif 'zookeeper_server_hosts' in config['clusterHostInfo']:
            phoenix_zk_quorum = ",".join(config['clusterHostInfo']['zookeeper_server_hosts'])
        else:
            if 'metrics_collector_hosts' in config['clusterHostInfo']:
                phoenix_zk_quorum = config['clusterHostInfo']['metrics_collector_hosts'][0]
            else:
                phoenix_zk_quorum = 'localhost'

if str(phoenix_zk_port).startswith('{{'):
    if is_ams_hbase_distributed == False:
        phoenix_zk_port = 61181
    else:
        if 'zoo.cfg' in config['configurations'] and 'clientPort' in config['configurations']['zoo.cfg']:
            phoenix_zk_port = config['configurations']['zoo.cfg']['clientPort']
        else:
            phoenix_zk_port = 2181

if phoenix_zk_node.startswith('{{'):
    if is_ams_hbase_distributed == False:
      phoenix_zk_node = "/ams-hbase-unsecure"
    else:
        if is_ams_hbase_secured == True:
            phoenix_zk_node = "/ams-hbase-secure"
        else:
            phoenix_zk_node = "/ams-hbase-unsecure"


activity_analyzer_keytab = "/etc/security/keytabs/activity-analyzer.headless.keytab"
activity_analyzer_principal = "activity-analyzer@EXAMPLE.COM"
activity_explorer_keytab = "/etc/security/keytabs/activity-explorer.headless.keytab"
activity_explorer_principal = "activity-explorer@EXAMPLE.COM"

if is_ams_hbase_secured == True and 'activity-conf' in config['configurations']:
    if 'global.activity.analyzer.user.keytab' in config['configurations']['activity-conf']:
        activity_analyzer_keytab = config['configurations']['activity-conf']['global.activity.analyzer.user.keytab']
    if 'global.activity.analyzer.user.principal' in config['configurations']['activity-conf']:
        activity_analyzer_principal = config['configurations']['activity-conf']['global.activity.analyzer.user.principal']
    if 'activity.explorer.user.keytab' in config['configurations']['activity-conf']:
        activity_explorer_keytab = config['configurations']['activity-conf']['activity.explorer.user.keytab']
    if 'activity.explorer.user.principal' in config['configurations']['activity-conf']:
        activity_explorer_principal = config['configurations']['activity-conf']['activity.explorer.user.principal']

activity_analyzer_jdbc_url = "jdbc:phoenix:" + str(phoenix_zk_quorum) + ":" + str(phoenix_zk_port) + ":" + str(phoenix_zk_node)
activity_explorer_jdbc_url = activity_analyzer_jdbc_url
if (is_ams_hbase_secured == True):
    activity_analyzer_jdbc_url = str(activity_analyzer_jdbc_url) + ":" + str(activity_analyzer_principal) + ":" + str(activity_analyzer_keytab)
    activity_explorer_jdbc_url = str(activity_explorer_jdbc_url) + ":" + str(activity_explorer_principal) + ":" + str(activity_explorer_keytab)

ams_jdbc_url = activity_explorer_jdbc_url



hdp_version = "2.0.0.0-0000"
stack_version = ""
cluster_stack_version = None
if 'clusterLevelParams' in config and 'stack_version' in config['clusterLevelParams']:
    cluster_stack_version = config['clusterLevelParams']['stack_version']
elif 'hostLevelParams' in config and 'stack_version' in config['hostLevelParams']:
    cluster_stack_version = config['hostLevelParams']['stack_version']
if cluster_stack_version:
    # e.g. 2.3
    stack_version_unformatted = str(cluster_stack_version)
    # e.g. 2.3.0.0
    try:
        from resource_management.libraries.functions.version import format_hdp_stack_version
        stack_version = format_hdp_stack_version(stack_version_unformatted)
    except ImportError:
        from resource_management.libraries.functions.version import format_stack_version
        stack_version = format_stack_version(stack_version_unformatted)
    # e.g. 2.3.0.0-2130
    full_version = default("/commandParams/version", None)
    hdp_version = full_version

product_name = ""
if 'clusterLevelParams' in config and 'stack_name' in config['clusterLevelParams']:
    product_name = str(config['clusterLevelParams']['stack_name'])
elif 'hostLevelParams' in config and 'stack_name' in config['hostLevelParams']:
    product_name = str(config['hostLevelParams']['stack_name'])
elif 'cluster-env' in config['configurations'] and 'stack_name' in config['configurations']['cluster-env']:
    product_name = str(config['configurations']['cluster-env']['stack_name'])

product_version = ""
if 'commandParams' in config and 'current_version' in config['commandParams']:
    product_version = str(config['commandParams']['current_version'])
elif 'hostLevelParams' in config and 'current_version' in config['hostLevelParams']:
    product_version = str(config['hostLevelParams']['current_version'])
else:
    product_version = stack_version

stack_root = "/usr/hdp"
if 'configurations' in config and 'cluster-env' in config['configurations'] and 'stack_root' in config['configurations']['cluster-env']:
    stack_root = config['configurations']['cluster-env']['stack_root']
    if '{' in str(stack_root):
        try:
            stack_root = json.loads(str(stack_root))[product_name]
        except:
            pass

ams_hbase_conf_dir = "/etc/ams-hbase/conf"
ams_user = "ams"
ams_user_group = "hadoop"
if ('configurations' in config and 'ams-env' in  config['configurations'] and 'ambari_metrics_user' in config['configurations']['ams-env']):
    ams_user = config['configurations']['ams-env']['ambari_metrics_user']

if ('configurations' in config and 'cluster-env' in  config['configurations'] and 'user_group' in config['configurations']['cluster-env']):
    ams_user_group = config['configurations']['cluster-env']["user_group"]


ams_phoenix_max_global_mem_percent = default('/configurations/ams-site/phoenix.query.maxGlobalMemoryPercentage', '20')
ams_phoenix_client_spool_dir = default('/configurations/ams-site/phoenix.spool.directory', '/tmp')
ams_phoenix_client_spool_dir = substitute_vars(ams_phoenix_client_spool_dir, config['configurations']['ams-hbase-site'])

analyzer_jvm_heap = 8192
analyzer_jvm_opts = ""

if 'activity-env' in config['configurations'] and 'analyzer_jvm_heap' in config['configurations']['activity-env']:
    analyzer_jvm_heap = config['configurations']['activity-env']['analyzer_jvm_heap']
if 'activity-env' in config['configurations'] and 'analyzer_jvm_opts' in config['configurations']['activity-env']:
    analyzer_jvm_opts = config['configurations']['activity-env']['analyzer_jvm_opts']

configuredUser = getpass.getuser()
if ( 'role' in config ):
  if ( config['role'] == 'HST_SERVER') and 'hst-server-conf' in config['configurations'] and 'server.run.as.user' in config['configurations']['hst-server-conf']:
    configuredUser = config['configurations']['hst-server-conf']['server.run.as.user']
  if ( config['role'] == 'HST_AGENT') and 'hst-agent-conf' in config['configurations'] and 'agent.run.as.user' in config['configurations']['hst-agent-conf']:
    configuredUser = config['configurations']['hst-agent-conf']['agent.run.as.user']
  if ( config['role'] == 'ACTIVITY_ANALYZER') and 'activity-conf' in config['configurations'] and 'global.run.as.user' in config['configurations']['activity-conf']:
    configuredUser = config['configurations']['activity-conf']['global.run.as.user']
  if ( config['role'] == 'ACTIVITY_EXPLORER') and 'activity-zeppelin-env' in config['configurations'] and 'explorer.run.as.user' in config['configurations']['activity-zeppelin-env']:
    configuredUser = config['configurations']['activity-zeppelin-env']['explorer.run.as.user']
    #print ("explorer configuredUser in params:"+configuredUser)

run_as_user = find_run_as_user(configuredUser)

