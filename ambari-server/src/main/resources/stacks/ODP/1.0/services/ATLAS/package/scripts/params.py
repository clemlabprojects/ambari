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
import sys

from ambari_commons import OSCheck
from resource_management import get_bare_principal
from resource_management.core.logger import Logger
from resource_management.libraries.functions.version import format_stack_version
from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.default import default

# Local Imports
from status_params import *
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions import StackFeature
from resource_management.libraries.functions.is_empty import is_empty
from resource_management.libraries.functions.expect import expect
from resource_management.libraries.functions.setup_ranger_plugin_xml import generate_ranger_service_config


def configs_for_ha(atlas_hosts, metadata_port, is_atlas_ha_enabled, metadata_protocol):
  """
  Return a dictionary of additional configs to merge if Atlas HA is enabled.
  :param atlas_hosts: List of hostnames that contain Atlas
  :param metadata_port: Port number
  :param is_atlas_ha_enabled: None, True, or False
  :param metadata_protocol: http or https
  :return: Dictionary with additional configs to merge to application-properties if HA is enabled.
  """
  additional_props = {}
  if atlas_hosts is None or len(atlas_hosts) == 0 or metadata_port is None:
    return additional_props

  # Sort to guarantee each host sees the same values, assuming restarted at the same time.
  atlas_hosts = sorted(atlas_hosts)

  # E.g., id1,id2,id3,...,idn
  _server_id_list = ["id" + str(i) for i in range(1, len(atlas_hosts) + 1)]
  atlas_server_ids = ",".join(_server_id_list)
  additional_props["atlas.server.ids"] = atlas_server_ids

  i = 0
  for curr_hostname in atlas_hosts:
    id = _server_id_list[i]
    prop_name = "atlas.server.address." + id
    prop_value = curr_hostname + ":" + metadata_port
    additional_props[prop_name] = prop_value
    if "atlas.rest.address" in additional_props:
      additional_props["atlas.rest.address"] += "," + metadata_protocol + "://" + prop_value
    else:
      additional_props["atlas.rest.address"] = metadata_protocol + "://" + prop_value

    i += 1

  # This may override the existing property
  if i == 1 or (i > 1 and is_atlas_ha_enabled is False):
    additional_props["atlas.server.ha.enabled"] = "false"
  elif i > 1:
    additional_props["atlas.server.ha.enabled"] = "true"

  return additional_props
  
# server configurations
config = Script.get_config()
exec_tmp_dir = Script.get_tmp_dir()
stack_root = Script.get_stack_root()

# Needed since this is an Atlas Hook service.
cluster_name = config['clusterName']

java_version = expect("/ambariLevelParams/java_version", int)

zk_root = default('/configurations/application-properties/atlas.server.ha.zookeeper.zkroot', '/apache_atlas')
stack_supports_zk_security = check_stack_feature(StackFeature.SECURE_ZOOKEEPER, version_for_stack_feature_checks)
atlas_kafka_group_id = default('/configurations/application-properties/atlas.kafka.hook.group.id', None)

if security_enabled:
  _hostname_lowercase = config['agentLevelParams']['hostname'].lower()
  _atlas_principal_name = config['configurations']['application-properties']['atlas.authentication.principal']
  atlas_jaas_principal = _atlas_principal_name.replace('_HOST',_hostname_lowercase)
  atlas_keytab_path = config['configurations']['application-properties']['atlas.authentication.keytab']

# New Cluster Stack Version that is defined during the RESTART of a Stack Upgrade
version = default("/commandParams/version", None)

# stack version
stack_version_unformatted = config['clusterLevelParams']['stack_version']
stack_version_formatted = format_stack_version(stack_version_unformatted)

metadata_home = format('{stack_root}/current/atlas-server')
metadata_bin = format("{metadata_home}/bin")

python_binary = os.environ['PYTHON_EXE'] if 'PYTHON_EXE' in os.environ else sys.executable
metadata_start_script = format("{metadata_bin}/atlas_start.py")
metadata_stop_script = format("{metadata_bin}/atlas_stop.py")

# metadata local directory structure
log_dir = config['configurations']['atlas-env']['metadata_log_dir']

# service locations
hadoop_conf_dir = os.path.join(os.environ["HADOOP_HOME"], "conf") if 'HADOOP_HOME' in os.environ else '/etc/hadoop/conf'

# some commands may need to supply the JAAS location when running as atlas
atlas_jaas_file = format("{conf_dir}/atlas_jaas.conf")

# user
user_group = config['configurations']['cluster-env']['user_group']

# metadata env
java64_home = config['ambariLevelParams']['java_home']
java_exec = format("{java64_home}/bin/java")
env_sh_template = config['configurations']['atlas-env']['content']

# credential provider
credential_provider = format( "jceks://file@{conf_dir}/atlas-site.jceks")

# command line args
ssl_enabled = default("/configurations/application-properties/atlas.enableTLS", False)
http_port = default("/configurations/application-properties/atlas.server.http.port", "21000")
https_port = default("/configurations/application-properties/atlas.server.https.port", "21443")
if ssl_enabled:
  metadata_port = https_port
  metadata_protocol = 'https'
else:
  metadata_port = http_port
  metadata_protocol = 'http'

metadata_host = config['agentLevelParams']['hostname']

atlas_hosts = sorted(default('/clusterHostInfo/atlas_server_hosts', []))
metadata_server_host = atlas_hosts[0] if len(atlas_hosts) > 0 else "UNKNOWN_HOST"

# application properties
application_properties = dict(config['configurations']['application-properties'])
# Depending on user configured value for atlas.server.bind.address
# we need to populate the value in atlas-application.properties
metadata_host_bind_address_value = default('/configurations/application-properties/atlas.server.bind.address','0.0.0.0')
if metadata_host_bind_address_value == '0.0.0.0':
  application_properties["atlas.server.bind.address"] = '0.0.0.0'
elif metadata_host_bind_address_value == 'localhost':
  application_properties["atlas.server.bind.address"] = metadata_host
else:
  application_properties["atlas.server.bind.address"] = metadata_host_bind_address_value

# trimming knox_key
if 'atlas.sso.knox.publicKey' in application_properties:
  knox_key = application_properties['atlas.sso.knox.publicKey']
  knox_key_without_new_line = knox_key.replace("\n","")
  application_properties['atlas.sso.knox.publicKey'] = knox_key_without_new_line

if check_stack_feature(StackFeature.ATLAS_UPGRADE_SUPPORT, version_for_stack_feature_checks):
  metadata_server_url = application_properties["atlas.rest.address"]
else:
  # In HDP 2.3 and 2.4 the property was computed and saved to the local config but did not exist in the database.
  metadata_server_url = format('{metadata_protocol}://{metadata_server_host}:{metadata_port}')
  application_properties["atlas.rest.address"] = metadata_server_url

# Atlas HA should populate
# atlas.server.ids = id1,id2,...,idn
# atlas.server.address.id# = host#:port
# User should not have to modify this property, but still allow overriding it to False if multiple Atlas servers exist
# This can be None, True, or False
is_atlas_ha_enabled = default("/configurations/application-properties/atlas.server.ha.enabled", None)
additional_ha_props = configs_for_ha(atlas_hosts, metadata_port, is_atlas_ha_enabled, metadata_protocol)
for k,v in additional_ha_props.items():
  application_properties[k] = v


metadata_env_content = config['configurations']['atlas-env']['content']

metadata_opts = config['configurations']['atlas-env']['metadata_opts']
metadata_classpath = config['configurations']['atlas-env']['metadata_classpath']
data_dir = format("{stack_root}/current/atlas-server/data")
expanded_war_dir = os.environ['METADATA_EXPANDED_WEBAPP_DIR'] if 'METADATA_EXPANDED_WEBAPP_DIR' in os.environ else format("{stack_root}/current/atlas-server/server/webapp")

metadata_log4j_content = config['configurations']['atlas-log4j']['content']

metadata_solrconfig_content = default("/configurations/atlas-solrconfig/content", None)
metadata_solrschema_content = default("/configurations/atlas-solrschema/content", None)

atlas_log_level = config['configurations']['atlas-log4j']['atlas_log_level']
audit_log_level = config['configurations']['atlas-log4j']['audit_log_level']
atlas_log_max_backup_size = default("/configurations/atlas-log4j/atlas_log_max_backup_size", 256)
atlas_log_number_of_backup_files = default("/configurations/atlas-log4j/atlas_log_number_of_backup_files", 20)

# smoke test
smoke_test_user = config['configurations']['cluster-env']['smokeuser']
smoke_test_password = 'smoke'
smokeuser_principal =  config['configurations']['cluster-env']['smokeuser_principal_name']
smokeuser_keytab = config['configurations']['cluster-env']['smokeuser_keytab']


security_check_status_file = format('{log_dir}/security_check.status')

# hbase
hbase_conf_dir = default('configurations/atlas-env/atlas.hbase.conf.dir','/etc/hbase/conf')

atlas_search_backend = default("/configurations/application-properties/atlas.graph.index.search.backend", "solr")
search_backend_solr = atlas_search_backend.startswith('solr')

# infra solr
infra_solr_znode = default("/configurations/infra-solr-env/infra_solr_znode", None)
infra_solr_hosts = default("/clusterHostInfo/infra_solr_hosts", [])
infra_solr_replication_factor = 2 if len(infra_solr_hosts) > 1 else 1
if 'atlas_solr_replication_factor' in config['configurations']['atlas-env']:
  infra_solr_replication_factor = int(default("/configurations/atlas-env/atlas_solr_replication_factor", 1))
atlas_solr_shards = default("/configurations/atlas-env/atlas_solr_shards", 1)
has_infra_solr = len(infra_solr_hosts) > 0
infra_solr_role_atlas = default('configurations/infra-solr-security-json/infra_solr_role_atlas', 'atlas_user')
infra_solr_role_dev = default('configurations/infra-solr-security-json/infra_solr_role_dev', 'dev')
infra_solr_role_ranger_audit = default('configurations/infra-solr-security-json/infra_solr_role_ranger_audit', 'ranger_audit_user')

# zookeeper
zookeeper_hosts = config['clusterHostInfo']['zookeeper_server_hosts']
zookeeper_port = default('/configurations/zoo.cfg/clientPort', None)

# get comma separated lists of zookeeper hosts from clusterHostInfo
index = 0
zookeeper_quorum = ""
for host in zookeeper_hosts:
  zookeeper_host = host
  if zookeeper_port is not None:
    zookeeper_host = host + ":" + str(zookeeper_port)

  zookeeper_quorum += zookeeper_host
  index += 1
  if index < len(zookeeper_hosts):
    zookeeper_quorum += ","

stack_supports_atlas_hdfs_site_on_namenode_ha = check_stack_feature(StackFeature.ATLAS_HDFS_SITE_ON_NAMENODE_HA, version_for_stack_feature_checks)
stack_supports_atlas_core_site = check_stack_feature(StackFeature.ATLAS_CORE_SITE_SUPPORT,version_for_stack_feature_checks)
atlas_server_xmx = default("configurations/atlas-env/atlas_server_xmx", 2048)
atlas_server_max_new_size = default("configurations/atlas-env/atlas_server_max_new_size", 614)

hbase_master_hosts = default('/clusterHostInfo/hbase_master_hosts', [])
has_hbase_master = not len(hbase_master_hosts) == 0

atlas_hbase_setup = format("{exec_tmp_dir}/atlas_hbase_setup.rb")
atlas_kafka_setup = format("{exec_tmp_dir}/atlas_kafka_acl.sh")
atlas_graph_storage_hbase_table = default('/configurations/application-properties/atlas.graph.storage.hbase.table', None)
atlas_audit_hbase_tablename = default('/configurations/application-properties/atlas.audit.hbase.tablename', None)

hbase_user_keytab = default('/configurations/hbase-env/hbase_user_keytab', None)
hbase_principal_name = default('/configurations/hbase-env/hbase_principal_name', None)

# Atlas Hooks
hive_server_hosts = default('/clusterHostInfo/hive_server_hosts', [])
has_hive_server = not len(hive_server_hosts) == 0

# Nifi Publish
nifi_hosts = default('/clusterHostInfo/nifi_master_hosts', [])
has_nifi = not len(nifi_hosts) == 0

# ToDo: Kafka port to Atlas
# Used while upgrading the stack in a kerberized cluster and running kafka-acls.sh
hosts_with_kafka = default('/clusterHostInfo/kafka_broker_hosts', [])
host_with_kafka = hostname in hosts_with_kafka

ranger_tagsync_hosts = default("/clusterHostInfo/ranger_tagsync_hosts", [])
has_ranger_tagsync = len(ranger_tagsync_hosts) > 0
rangertagsync_user = "rangertagsync"

kafka_keytab = default('/configurations/kafka-env/kafka_keytab', None)
kafka_principal_name = default('/configurations/kafka-env/kafka_principal_name', None)
default_replication_factor = default('/configurations/application-properties/atlas.notification.replicas', None)

if check_stack_feature(StackFeature.ATLAS_UPGRADE_SUPPORT, version_for_stack_feature_checks):
  default_replication_factor = default('/configurations/application-properties/atlas.notification.replicas', None)

  kafka_env_sh_template = config['configurations']['kafka-env']['content']
  kafka_home = os.path.join(stack_root,  "current", "kafka-broker")
  kafka_conf_dir = os.path.join(kafka_home, "config")

  kafka_zk_endpoint = default("/configurations/kafka-broker/zookeeper.connect", None)
  kafka_kerberos_enabled = (('security.inter.broker.protocol' in config['configurations']['kafka-broker']) and
                            ((config['configurations']['kafka-broker']['security.inter.broker.protocol'] == "SASL_PLAINTEXT") or 
                             (config['configurations']['kafka-broker']['security.inter.broker.protocol'] == "SASL_SSL")
                            ))
  if security_enabled and stack_version_formatted != "" and 'kafka_principal_name' in config['configurations']['kafka-env'] \
    and check_stack_feature(StackFeature.KAFKA_KERBEROS, stack_version_formatted):
    _hostname_lowercase = config['agentLevelParams']['hostname'].lower()
    _kafka_principal_name = config['configurations']['kafka-env']['kafka_principal_name']
    kafka_jaas_principal = _kafka_principal_name.replace('_HOST', _hostname_lowercase)
    kafka_keytab_path = config['configurations']['kafka-env']['kafka_keytab']
    kafka_bare_jaas_principal = get_bare_principal(_kafka_principal_name)
    kafka_kerberos_params = "-Djava.security.auth.login.config={0}/kafka_jaas.conf".format(kafka_conf_dir)
  else:
    kafka_kerberos_params = ''
    kafka_jaas_principal = None
    kafka_keytab_path = None

namenode_host = set(default("/clusterHostInfo/namenode_hosts", []))
has_namenode = not len(namenode_host) == 0

upgrade_direction = default("/commandParams/upgrade_direction", None)

# ranger host
ranger_admin_hosts = default("/clusterHostInfo/ranger_admin_hosts", [])
has_ranger_admin = not len(ranger_admin_hosts) == 0

retry_enabled = default("/commandParams/command_retry_enabled", False)

stack_supports_atlas_ranger_plugin = check_stack_feature(StackFeature.ATLAS_RANGER_PLUGIN_SUPPORT, version_for_stack_feature_checks)
stack_supports_ranger_kerberos = check_stack_feature(StackFeature.RANGER_KERBEROS_SUPPORT, version_for_stack_feature_checks)

# ranger support xml_configuration flag, instead of depending on ranger xml_configurations_supported/ranger-env, using stack feature
xml_configurations_supported = check_stack_feature(StackFeature.RANGER_XML_CONFIGURATION, version_for_stack_feature_checks)

# ranger atlas plugin enabled property
enable_ranger_atlas = default("/configurations/ranger-atlas-plugin-properties/ranger-atlas-plugin-enabled", "No")
enable_ranger_atlas = True if enable_ranger_atlas.lower() == "yes" else False

# ranger hbase plugin enabled property
enable_ranger_hbase = default("/configurations/ranger-hbase-plugin-properties/ranger-hbase-plugin-enabled", "No")
enable_ranger_hbase = True if enable_ranger_hbase.lower() == 'yes' else False

if stack_supports_atlas_ranger_plugin and enable_ranger_atlas:
  # for create_hdfs_directory
  hdfs_user = config['configurations']['hadoop-env']['hdfs_user'] if has_namenode else None
  hdfs_user_keytab = config['configurations']['hadoop-env']['hdfs_user_keytab']  if has_namenode else None
  hdfs_principal_name = config['configurations']['hadoop-env']['hdfs_principal_name'] if has_namenode else None
  hdfs_site = config['configurations']['hdfs-site']
  default_fs = config['configurations']['core-site']['fs.defaultFS']
  dfs_type = default("/clusterLevelParams/dfs_type", "")

  import functools
  from resource_management.libraries.resources.hdfs_resource import HdfsResource
  from resource_management.libraries.functions.get_not_managed_resources import get_not_managed_resources
  #create partial functions with common arguments for every HdfsResource call
  #to create hdfs directory we need to call params.HdfsResource in code

  HdfsResource = functools.partial(
    HdfsResource,
    user = hdfs_user,
    hdfs_resource_ignore_file = "/var/lib/ambari-agent/data/.hdfs_resource_ignore",
    security_enabled = security_enabled,
    keytab = hdfs_user_keytab,
    kinit_path_local = kinit_path_local,
    hadoop_bin_dir = hadoop_bin_dir,
    hadoop_conf_dir = hadoop_conf_dir,
    principal_name = hdfs_principal_name,
    hdfs_site = hdfs_site,
    default_fs = default_fs,
    immutable_paths = get_not_managed_resources(),
    dfs_type = dfs_type
  )

  # ranger atlas service/repository name
  repo_name = str(config['clusterName']) + '_atlas'
  repo_name_value = config['configurations']['ranger-atlas-security']['ranger.plugin.atlas.service.name']
  if not is_empty(repo_name_value) and repo_name_value != "{{repo_name}}":
    repo_name = repo_name_value

  ssl_keystore_password = config['configurations']['ranger-atlas-policymgr-ssl']['xasecure.policymgr.clientssl.keystore.password']
  ssl_truststore_password = config['configurations']['ranger-atlas-policymgr-ssl']['xasecure.policymgr.clientssl.truststore.password']
  credential_file = format('/etc/ranger/{repo_name}/cred.jceks')
  xa_audit_hdfs_is_enabled = default('/configurations/ranger-atlas-audit/xasecure.audit.destination.hdfs', False)

  # SSL related configuration
  atlas_credential_file_path = config['configurations']['application-properties']['cert.stores.credential.provider.path']
  tls_ssl_keystore_password = config['configurations']['application-properties']['keystore.password']
  tls_ssl_truststore_password = config['configurations']['application-properties']['truststore.password']

  atlas_tls_ssl_keystore_password = format('{tls_ssl_keystore_password}')
  atlas_tls_ssl_truststore_password = format('{tls_ssl_truststore_password}')
  # Needed by both Server and Client
  if ssl_enabled and config['configurations']['application-properties']['keystore.password'] is not None:
    Logger.info('deleting keystore.password from atlas-application.properties')
    del(application_properties['keystore.password'])
  if ssl_enabled and config['configurations']['application-properties']['truststore.password'] is not None:
    Logger.info('deleting truststore.password from atlas-application.properties')
    del(application_properties['truststore.password'])


  # get ranger policy url
  policymgr_mgr_url = config['configurations']['ranger-atlas-security']['ranger.plugin.atlas.policy.rest.url']

  if not is_empty(policymgr_mgr_url) and policymgr_mgr_url.endswith('/'):
    policymgr_mgr_url = policymgr_mgr_url.rstrip('/')

  downloaded_custom_connector = None
  driver_curl_source = None
  driver_curl_target = None

  ranger_env = config['configurations']['ranger-env']

  # create ranger-env config having external ranger credential properties
  if not has_ranger_admin and enable_ranger_atlas:
    external_admin_username = default('/configurations/ranger-atlas-plugin-properties/external_admin_username', 'admin')
    external_admin_password = default('/configurations/ranger-atlas-plugin-properties/external_admin_password', 'admin')
    external_ranger_admin_username = default('/configurations/ranger-atlas-plugin-properties/external_ranger_admin_username', 'amb_ranger_admin')
    external_ranger_admin_password = default('/configurations/ranger-atlas-plugin-properties/external_ranger_admin_password', 'amb_ranger_admin')
    ranger_env = {}
    ranger_env['admin_username'] = external_admin_username
    ranger_env['admin_password'] = external_admin_password
    ranger_env['ranger_admin_username'] = external_ranger_admin_username
    ranger_env['ranger_admin_password'] = external_ranger_admin_password
    ranger_admin_username = external_ranger_admin_username
    ranger_admin_password = external_admin_password

  if has_ranger_admin:
    ranger_admin_username = config['configurations']['ranger-env']['admin_username']
    ranger_admin_password = config['configurations']['ranger-env']['admin_password']

  ranger_plugin_properties = config['configurations']['ranger-atlas-plugin-properties']
  ranger_atlas_audit = config['configurations']['ranger-atlas-audit']
  ranger_atlas_audit_attrs = config['configurationAttributes']['ranger-atlas-audit']
  ranger_atlas_security = config['configurations']['ranger-atlas-security']
  ranger_atlas_security_attrs = config['configurationAttributes']['ranger-atlas-security']
  ranger_atlas_policymgr_ssl = config['configurations']['ranger-atlas-policymgr-ssl']
  ranger_atlas_policymgr_ssl_attrs = config['configurationAttributes']['ranger-atlas-policymgr-ssl']

  policy_user = config['configurations']['ranger-atlas-plugin-properties']['policy_user']

  atlas_repository_configuration = {
    'username' : config['configurations']['ranger-atlas-plugin-properties']['REPOSITORY_CONFIG_USERNAME'],
    'password' : config['configurations']['ranger-atlas-plugin-properties']['REPOSITORY_CONFIG_PASSWORD'],
    'atlas.rest.address' : metadata_server_url,
    'commonNameForCertificate' : config['configurations']['ranger-atlas-plugin-properties']['common.name.for.certificate'],
    'ambari.service.check.user' : policy_user
  }

  custom_ranger_service_config = generate_ranger_service_config(ranger_plugin_properties)
  if len(custom_ranger_service_config) > 0:
    atlas_repository_configuration.update(custom_ranger_service_config)

  if security_enabled:
    atlas_repository_configuration['policy.download.auth.users'] = metadata_user
    atlas_repository_configuration['tag.download.auth.users'] = metadata_user

  atlas_ranger_plugin_repo = {
    'isEnabled': 'true',
    'configs': atlas_repository_configuration,
    'description': 'atlas repo',
    'name': repo_name,
    'type': 'atlas',
    }

# required when Ranger-KMS is SSL enabled
ranger_kms_hosts = default('/clusterHostInfo/ranger_kms_server_hosts',[])
has_ranger_kms = len(ranger_kms_hosts) > 0
is_ranger_kms_ssl_enabled = default('configurations/ranger-kms-site/ranger.service.https.attrib.ssl.enabled',False)
# ranger atlas plugin section end
# atlas admin login username password
atlas_admin_username = config['configurations']['atlas-env']['atlas.admin.username']
atlas_admin_password = config['configurations']['atlas-env']['atlas.admin.password']

mount_table_xml_inclusion_file_full_path = None
mount_table_content = None
if 'viewfs-mount-table' in config['configurations']:
  xml_inclusion_file_name = 'viewfs-mount-table.xml'
  mount_table = config['configurations']['viewfs-mount-table']

  if 'content' in mount_table and mount_table['content'].strip():
    mount_table_xml_inclusion_file_full_path = os.path.join(conf_dir, xml_inclusion_file_name)
    mount_table_content = mount_table['content']

titan_table_name = config['configurations']['application-properties']['atlas.graph.storage.hbase.table']
if titan_table_name is None:
  titan_table_name = "atlas_titan"
# create a ranger policy for atlas permission
ranger_policy_config = {
    "isEnabled": "true",
    "service": cluster_name + "_hbase",
    "name": "Atlas Janus Graph",
    "resources": {
      "column": {
        "values": ["*"]
       },
      "column-family": {
        "values": [ "*" ]
       },
      "table": {
        "values": [titan_table_name, "ATLAS_ENTITY_AUDIT_EVENTS" ]
      }
    },
    "policyItems": [{
      "accesses": [
        {
        'type': 'read',
        'isAllowed': True
        },{
        'type': 'write',
        'isAllowed': True
        },{
        'type': 'create',
        'isAllowed': True
        },
        {
        'type': 'admin',
        'isAllowed': True
        }
      ],
      "users": [metadata_user],
      "groups": [],
      "roles": [],
      "conditions": [],
      "delegateAdmin": "false"
    }]
  }

# AMBARI-186 (clemlab): update Atlas Kafka ACL script by removing zookeeper reference to it
atlas_kafka_3_acl_support = check_stack_feature(StackFeature.ATLAS_KAFKA_3_ACL_SUPPORT, version_for_stack_feature_checks)
atlas_kafka_security_protocol = default("/configurations/application-properties/atlas.kafka.security.protocol", "PLAINTEXT")
# Kafka bootstrap servers
kafka_bootstrap_servers = default("/configurations/application-properties/atlas.kafka.bootstrap.servers", None)

if kafka_bootstrap_servers is None:
  kafka_broker_hosts = default("/clusterHostInfo/kafka_broker_hosts", [])
  atlas_kafka_security_protocol = default("/configurations/kafka-broker/security.inter.broker.protocol", "PLAINTEXT")

  kafka_listeners = default("/configurations/kafka-broker/listeners", "").split(",")
  kafka_bootstrap_servers = []
  valid_protocols = ["SASL_SSL", "SASL_PLAINTEXT", "SSL", "PLAINTEXT"]
  listener_protocols = [listener.split("://")[0] for listener in kafka_listeners]
  if atlas_kafka_security_protocol not in listener_protocols:
    for protocol in valid_protocols:
      if protocol in listener_protocols:
        atlas_kafka_security_protocol = protocol
        break
  for listener in kafka_listeners:
    protocol, address = listener.split("://")
    host, port = address.split(":")
    if protocol == atlas_kafka_security_protocol:
      kafka_bootstrap_servers.append(f"{host}:{port}")
  kafka_bootstrap_servers = ",".join(kafka_bootstrap_servers)
atlas_hook_publishers = []

if atlas_kafka_3_acl_support:
  atlas_kafka_setup = format("{exec_tmp_dir}/atlas_kafka_3_acl.sh")
  kafka_cmd_config_file = format("{exec_tmp_dir}/atlas_kafka_3_cmd_config.txt")

# AMBARI-187 (clemlab) Authorize Atlas service to access Kafka notification with Ranger Kafka Plugin policies 
# ranger kafka plugin enabled property
ranger_kafka_plugin_enabled = default("/configurations/ranger-kafka-plugin-properties/ranger-kafka-plugin-enabled", "No")
ranger_kafka_plugin_enabled = True if ranger_kafka_plugin_enabled.lower() == 'yes' else False
ranger_tagsync_user = default("/configurations/ranger-tagsync-site/ranger.tagsync.kerberos.principal", "rangertagsync")
if ranger_kafka_plugin_enabled:
  # read user name from principal names
  if security_enabled:
    if has_ranger_tagsync:
      tagsync_principal = config['configurations']['ranger-tagsync-site']['ranger.tagsync.kerberos.principal']
      rangertagsync_user = tagsync_principal.split('@')[0]
    if has_hbase_master:
      hbase_user = config['configurations']['hbase-site']['hbase.master.kerberos.principal'].split('/')[0]
      atlas_hook_publishers.append(hbase_user)
    if has_hive_server:
      hive_user = config['configurations']['hive-site']['hive.server2.authentication.kerberos.principal'].split('/')[0]
      atlas_hook_publishers.append(hive_user)
    if has_nifi:
      nifi_user = config['configurations']['nifi-properties']['nifi.kerberos.service.principal'].split('/')[0]
      atlas_hook_publishers.append(nifi_user)
  else:
    if has_hbase_master:
      hbase_user = config['configurations']['hbase-env']['hbase_user']
      atlas_hook_publishers.append(hbase_user)
    if has_hive_server:
      hive_user = config['configurations']['hive-env']['hive_user']
      atlas_hook_publishers.append(hive_user)
    if has_nifi:
      nifi_user = config['configurations']['nifi-env']['nifi_user']
      atlas_hook_publishers.append(nifi_user)
  ranger_atlas_kafka_policies = []
  ranger_atlas_kafka_policies.append({
    "isEnabled": "true",
    "service": cluster_name + "_kafka",
    "name": "ATLAS_HOOK",
    "resources": {
      "topic": {
        "values": [
            "ATLAS_HOOK"
        ],
        "isExcludes": False,
        "isRecursive": False
      }
    },
    "policyItems": [{
      "accesses": [
        {
        'type': 'create',
        'isAllowed': True
        },{
        'type': 'configure',
        'isAllowed': True
        },{
        'type': 'consume',
        'isAllowed': True
        },
        {
        'type': 'publish',
        'isAllowed': True
        }
      ],
      "users": [metadata_user],
      "groups": [],
      "roles": [],
      "conditions": [],
      "delegateAdmin": "false"
    },
    {
      "accesses": [
        {
        'type': 'publish',
        'isAllowed': True
        }
      ],
      "users": atlas_hook_publishers,
      "groups": [],
      "roles": [],
      "conditions": [],
      "delegateAdmin": "false"
    },

    ]
  })
  ranger_atlas_kafka_policies.append({
    "isEnabled": "true",
    "service": cluster_name + "_kafka",
    "name": "ATLAS_ENTITIES",
    "resources": {
      "topic": {
        "values": [
            "ATLAS_ENTITIES"
        ],
        "isExcludes": False,
        "isRecursive": False
      }
    },
    "policyItems": [{
      "accesses": [
        {
        'type': 'create',
        'isAllowed': True
        },{
        'type': 'configure',
        'isAllowed': True
        },
        {
        'type': 'publish',
        'isAllowed': True
        }
      ],
      "users": [metadata_user],
      "groups": [],
      "roles": [],
      "conditions": [],
      "delegateAdmin": "false"
    },
    {
      "accesses": [
        {
        'type': 'consume',
        'isAllowed': True
        }
      ],
      "users": [rangertagsync_user],
      "groups": [],
      "roles": [],
      "conditions": [],
      "delegateAdmin": "false"
    },
    ]
  })
  ranger_atlas_kafka_policies.append({
    "isEnabled": "true",
    "service": cluster_name + "_kafka",
    "name": "ATLAS_SPARK_HOOK",
    "resources": {
      "topic": {
        "values": [
            "ATLAS_SPARK_HOOK"
        ],
        "isExcludes": False,
        "isRecursive": False
      }
    },
    "policyItems": [{
      "accesses": [
        {
        'type': 'create',
        'isAllowed': True
        },{
        'type': 'configure',
        'isAllowed': True
        },
        {
        'type': 'consume',
        'isAllowed': True
        }
      ],
      "users": [metadata_user],
      "groups": [],
      "roles": [],
      "conditions": [],
      "delegateAdmin": "false"
    },
    {
      "accesses": [
        {
        'type': 'publish',
        'isAllowed': True
        }
      ],
      "users": [],
      "groups": ["public"],
      "roles": [],
      "conditions": [],
      "delegateAdmin": "false"
    },
    ]
  })
  ranger_atlas_kafka_policies.append({
    "isEnabled": "true",
    "service": cluster_name + "_kafka",
    "name": "Atlas Consumer Group Access",
    "resources": {
      "consumergroup": {
        "values": [
            "atlas"
        ],
        "isExcludes": False,
        "isRecursive": False
      }
    },
    "policyItems": [{
      "accesses": [
        {
        'type': 'describe',
        'isAllowed': True
        },
        {
        'type': 'consume',
        'isAllowed': True
        }
      ],
      "users": [metadata_user],
      "groups": [],
      "roles": [],
      "conditions": [],
      "delegateAdmin": "false"
    }]
  })
  ranger_atlas_kafka_policies.append({
    "isEnabled": "true",
    "service": cluster_name + "_kafka",
    "name": "Ranger Entities Consumergroup Access",
    "resources": {
      "consumergroup": {
        "values": [
            "ranger_entities_consumer"
        ],
        "isExcludes": False,
        "isRecursive": False
      }
    },
    "policyItems": [{
      "accesses": [
        {
        'type': 'describe',
        'isAllowed': True
        },
        {
        'type': 'consume',
        'isAllowed': True
        }
      ],
      "users": [rangertagsync_user],
      "groups": [],
      "roles": [],
      "conditions": [],
      "delegateAdmin": "false"
    }]
  })

kafka_client_tools_log_level = default("/configurations/atlas-env/atlas.kafka.client.log.level", "WARN")
