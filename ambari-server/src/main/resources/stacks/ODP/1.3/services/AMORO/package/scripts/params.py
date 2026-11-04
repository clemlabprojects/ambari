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
import re
import socket

from resource_management.libraries.functions import conf_select
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.get_kinit_path import get_kinit_path
from resource_management.libraries.functions.stack_features import check_stack_feature, get_stack_feature_version
from resource_management.libraries.script.script import Script

import status_params


def _to_bool(value, default=False):
  if value is None:
    return default
  return str(value).strip().lower() in ("true", "yes", "1", "on")


config = Script.get_config()
stack_root = Script.get_stack_root()

version = default("/commandParams/version", None)
version_for_stack_feature_checks = get_stack_feature_version(config)

# Identities and JDK. The Hive 4 jars of the stack are Java 21 class files: AMORO_SERVER selects the
# secondary JDK (javaHomeSelector), resolved as for HIVE and POLARIS.
amoro_user = status_params.amoro_user
user_group = config['configurations']['cluster-env']['user_group']
java64_home = config['ambariLevelParams']['java_home']
host_level_params = default("/hostLevelParams", {})
ambari_level_params = default("/ambariLevelParams", {})
command_params_map = default("/commandParams", {})
java_home_selector = (
  host_level_params.get("java_home_selector")
  or command_params_map.get("java_home_selector")
  or ambari_level_params.get("java_home_selector")
  or "java_home"
)
amoro_java_home = java64_home
if check_stack_feature(StackFeature.SECONDARY_JAVA_HOME_SUPPORT, version_for_stack_feature_checks):
  amoro_java_home = (host_level_params.get(java_home_selector)
                     or ambari_level_params.get(java_home_selector)
                     or java64_home)
jdk_location = default("/ambariLevelParams/jdk_location", "/usr/share/java")
custom_postgres_jdbc_name = default("/ambariLevelParams/custom_postgres_jdbc_name", None)
custom_mysql_jdbc_name = default("/ambariLevelParams/custom_mysql_jdbc_name", None)

security_enabled = _to_bool(default("/configurations/cluster-env/security_enabled", "false"))
kinit_path_local = get_kinit_path(default("/configurations/kerberos-env/executable_search_paths", None))
amoro_principal = str(default("/configurations/amoro-env/amoro_principal", "")).strip()
amoro_principal = amoro_principal.replace("_HOST", config['agentLevelParams']['hostname'].lower())
amoro_keytab = default("/configurations/amoro-env/amoro_keytab", "/etc/security/keytabs/amoro.service.keytab")

# Layout: packaged installation (odp-select amoro-server), conf-select managed configuration.
amoro_home = format("{stack_root}/current/amoro-server")
amoro_conf_dir = default("/configurations/amoro-env/amoro_conf_dir", "/etc/amoro/conf")
amoro_log_dir = default("/configurations/amoro-env/amoro_log_dir", "/var/log/amoro")
amoro_pid_dir = status_params.amoro_pid_dir
amoro_pid_file = status_params.amoro_pid_file
amoro_data_dir = default("/configurations/amoro-env/amoro_data_dir", "/var/lib/amoro")
# Ambari web saves the *_heapsize properties with an "m" (MB) suffix
amoro_server_heapsize = int(re.search(r'\d+', str(default("/configurations/amoro-env/amoro_server_heapsize", 2048))).group(0))
amoro_env_sh_template = config['configurations']['amoro-env']['content']
amoro_log4j_template = config['configurations']['amoro-log4j']['content']

amoro_optimizer_group = str(default("/configurations/amoro-env/amoro_optimizer_group", "local")).strip() or "local"
amoro_optimizer_parallelism = int(default("/configurations/amoro-env/amoro_optimizer_parallelism", 2))
amoro_optimizer_memory = int(default("/configurations/amoro-env/amoro_optimizer_memory", 2048))
# Amoro keeps amoro_optimizer_parallelism threads running in the group (min-parallelism), unless off.
amoro_optimizer_managed = str(default("/configurations/amoro-env/amoro_optimizer_managed", "true")).strip().lower() != "false"
# Optimizers as Spark applications on YARN ("spark") or processes on the Amoro host ("local").
amoro_optimizer_container = str(default("/configurations/amoro-env/amoro_optimizer_container", "local")).strip().lower() or "local"
amoro_optimizer_yarn_queue = str(default("/configurations/amoro-env/amoro_optimizer_yarn_queue", "default")).strip() or "default"
amoro_optimizer_spark_driver_memory = int(default("/configurations/amoro-env/amoro_optimizer_spark_driver_memory", 1024))
spark_home = format("{stack_root}/current/spark3-client")
amoro_admin_ldap_group_dn = str(default("/configurations/amoro-env/amoro_admin_ldap_group_dn", "")).strip()

# Amoro configuration ("ams" section of config.yaml).
amoro_site = dict(config['configurations']['amoro-site'])
amoro_admin_username = str(amoro_site.get("admin-username", "admin")).strip() or "admin"
amoro_admin_password = str(amoro_site.get("admin-password", ""))
amoro_http_port = int(amoro_site.get("http-server.bind-port", 1630))
amoro_ssl_enabled = _to_bool(amoro_site.get("http-server.ssl.enabled"), False)
amoro_protocol = "https" if amoro_ssl_enabled else "http"

hostname = config['agentLevelParams']['hostname'].lower()
amoro_server_hosts = sorted(default("/clusterHostInfo/amoro_server_hosts", []))
amoro_server_host = amoro_server_hosts[0] if amoro_server_hosts else hostname
# Amoro advertises its Thrift services on the address of the host (server-expose-host is an address
# prefix, not a host name).
try:
  amoro_server_address = socket.gethostbyname(hostname)
except Exception:
  amoro_server_address = hostname
amoro_url = format("{amoro_protocol}://{amoro_server_host}:{amoro_http_port}")

# Database.
amoro_db_properties = config['configurations']['amoro-db-properties']
amoro_db_flavor = str(amoro_db_properties.get("DB_FLAVOR", "POSTGRES")).upper()
amoro_create_db_dbuser = _to_bool(amoro_db_properties.get("create_db_dbuser"), True)
amoro_db_host = str(amoro_db_properties.get("db_host", "localhost")).strip()
amoro_db_port = str(amoro_db_properties.get("db_port", "5432")).strip()
amoro_db_name = str(amoro_db_properties.get("db_name", "amoro")).strip()
amoro_db_user = str(amoro_db_properties.get("db_user", "amoro")).strip()
amoro_db_password = str(amoro_db_properties.get("db_password", ""))
amoro_db_root_user = str(amoro_db_properties.get("db_root_user", "postgres")).strip()
amoro_db_root_password = str(amoro_db_properties.get("db_root_password", ""))
amoro_privelege_user_jdbc_url = str(amoro_db_properties.get("amoro_privelege_user_jdbc_url", "")).strip()
# amoro.jdbc.driver and amoro.jdbc.url are recommended by the service advisor from the fields above.
if amoro_db_flavor == "MYSQL":
  amoro_jdbc_driver_class = "com.mysql.cj.jdbc.Driver"
  amoro_jdbc_url = format("jdbc:mysql://{amoro_db_host}:{amoro_db_port}/{amoro_db_name}"
                          "?useUnicode=true&characterEncoding=UTF8&autoReconnect=true&useAffectedRows=true")
else:
  amoro_jdbc_driver_class = "org.postgresql.Driver"
  amoro_jdbc_url = format("jdbc:postgresql://{amoro_db_host}:{amoro_db_port}/{amoro_db_name}")
amoro_jdbc_driver_class = str(amoro_db_properties.get("amoro.jdbc.driver", "")).strip() or amoro_jdbc_driver_class
amoro_jdbc_url = str(amoro_db_properties.get("amoro.jdbc.url", "")).strip() or amoro_jdbc_url

# Client configurations of the cluster (env.sh, Hive catalog of Amoro).
hadoop_conf_dir = conf_select.get_hadoop_conf_dir()
has_hive = len(default("/clusterHostInfo/hive_metastore_hosts", [])) > 0
# Hive client configuration of Amoro (HIVE_CONF_DIR, uploaded with the Hive catalog): a copy of hive-site
# Ambari writes for the amoro user, without its passwords. The cluster /etc/hive/conf/hive-site.xml is 0600
# hive on the Hive server hosts, and carries the password of the metastore database.
hive_site = dict(default("/configurations/hive-site", {})) if has_hive else {}
hive_site_attributes = default("/configurationAttributes/hive-site", {})
hive_conf_dir = os.path.join(amoro_conf_dir, "hive") if hive_site else "/etc/hive/conf"
krb5_conf_path = "/etc/krb5.conf"
amoro_truststore_path = str(default("/configurations/amoro-env/amoro_truststore_path", "")).strip()
if amoro_truststore_path and not os.path.isfile(amoro_truststore_path):
  amoro_truststore_path = ""
amoro_truststore_type = str(default("/configurations/amoro-env/amoro_truststore_type", "JKS")).strip() or "JKS"
# The certificates of a PKCS12 truststore are only readable with its password. Empty: the password of the
# Hadoop client truststore (ssl-client) when Amoro uses that same file.
amoro_truststore_password = str(default("/configurations/amoro-env/amoro_truststore_password", ""))
if not amoro_truststore_password and amoro_truststore_path and amoro_truststore_path == \
    str(default("/configurations/ssl-client/ssl.client.truststore.location", "")).strip():
  amoro_truststore_password = str(default("/configurations/ssl-client/ssl.client.truststore.password", ""))
amoro_jvm_truststore_path = os.path.join(amoro_data_dir, "truststore.jks")

# Catalogs registered by Ambari (REGISTER_CATALOGS, start): the Hive Metastore, and the Ozone
# catalog Ambari manages in Polaris.
amoro_hive_catalog_name = "hive"
cluster_name = config['clusterName']

polaris_env = default("/configurations/polaris-env", {})
polaris_app_properties = default("/configurations/polaris-application-properties", {})
polaris_server_hosts = sorted(default("/clusterHostInfo/polaris_server_hosts", []))
ozone_manager_hosts = default("/clusterHostInfo/ozone_manager_hosts", [])
has_polaris_ozone_catalog = bool(polaris_server_hosts) and bool(ozone_manager_hosts)
polaris_catalog_name = str(polaris_env.get("polaris_ozone_catalog_name", "ozone")).strip() or "ozone"
polaris_ssl_enabled = _to_bool(polaris_env.get("polaris_ssl_enabled"), False)
if polaris_ssl_enabled:
  polaris_port = str(polaris_app_properties.get("quarkus.http.ssl-port", "8443")).strip() or "8443"
else:
  polaris_port = str(polaris_app_properties.get("quarkus.http.port", "8181")).strip() or "8181"
polaris_url = "{0}://{1}:{2}".format("https" if polaris_ssl_enabled else "http",
                                     polaris_server_hosts[0] if polaris_server_hosts else "localhost", polaris_port)
polaris_realm = (str(polaris_app_properties.get("polaris.realm-context.realms", "POLARIS")).split(",")[0].strip()
                 or "POLARIS")
polaris_realm_header = (str(polaris_app_properties.get("polaris.realm-context.header-name", "Polaris-Realm")).strip()
                        or "Polaris-Realm")
polaris_admin_username = str(polaris_env.get("polaris_admin_username", "")).strip()
polaris_admin_password = str(polaris_env.get("polaris_admin_password", ""))
polaris_authorization_type = str(polaris_app_properties.get("polaris.authorization.type", "internal")).strip().lower()
polaris_ranger_service = str(default("/configurations/ranger-polaris-security/ranger.plugin.polaris.service.name", "")).strip()
if not polaris_ranger_service or polaris_ranger_service == "{{repo_name}}":
  polaris_ranger_service = "{0}_polaris".format(cluster_name)
# The Polaris principal of Amoro carries the service user name, which Ranger policies grant.
amoro_polaris_principal = amoro_user
# With the cluster OIDC, Amoro authenticates to Polaris with the OIDC client Ambari manages for it
# (oidc.json: secret in the Ambari credential store, principal name claim = amoro_user); without it,
# with credentials Polaris issues to the principal amoro_polaris_principal.
amoro_oidc_client_id = str(default("/configurations/amoro-env/amoro_oidc_client_id", "")).strip()
amoro_oidc_client_secret = str(default("/configurations/amoro-env/amoro_oidc_client_secret", ""))
amoro_ozone_s3_secret = str(default("/configurations/amoro-env/amoro_ozone_s3_secret", "")).strip()
amoro_oidc_auth_server_url = str(default("/configurations/amoro-env/amoro_oidc_auth_server_url", "")).strip().rstrip("/")
polaris_credential_mode = str(polaris_app_properties.get("polaris.authentication.credential-mode", "internal")).strip().lower()
amoro_polaris_oidc = (bool(amoro_oidc_client_id and amoro_oidc_client_secret.strip() and amoro_oidc_auth_server_url)
                      and polaris_credential_mode == "external")

# Amoro reads and writes the Ozone catalog data with its own S3 credential (the Ozone catalog of
# Polaris vends none): a secret Ambari generates and sets on Ozone for the Amoro principal.
ozone_security_enabled = _to_bool(default("/configurations/ozone-site/ozone.security.enabled", "false"))
ozone_cmd = format("{stack_root}/current/ozone-client/bin/ozone")
ozone_client_lib_dir = format("{stack_root}/current/ozone-client/share/ozone/lib")
ozone_om_service_id = str(default("/configurations/ozone-site/ozone.om.internal.service.id", "")).strip()
if not ozone_om_service_id:
  ozone_om_service_id = str(default("/configurations/ozone-site/ozone.om.service.ids", "")).split(",")[0].strip()

# Ranger admin, for the policies granting the Amoro user (Polaris catalog, HDFS, Ozone).
has_ranger_admin = len(default("/clusterHostInfo/ranger_admin_hosts", [])) > 0
ranger_admin_url = str(default("/configurations/admin-properties/policymgr_external_url", "")).strip().rstrip("/")
ranger_admin_username = str(default("/configurations/ranger-env/admin_username", "")).strip()
ranger_admin_password = str(default("/configurations/ranger-env/admin_password", ""))

# Data access of the Amoro user (SETUP_ACCESS, start): Ranger policies on the Hive warehouse (HDFS or
# Ozone) and on the bucket of the Polaris Ozone catalog.
default_fs = str(default("/configurations/core-site/fs.defaultFS", "")).strip()
hive_warehouse_dirs = [str(default("/configurations/hive-site/" + name, "")).strip() for name in (
  "hive.metastore.warehouse.dir", "hive.metastore.warehouse.external.dir")] if has_hive else []
hive_warehouse_dirs = [path for path in hive_warehouse_dirs if path]
polaris_catalog_base_location = str(polaris_env.get("polaris_ozone_catalog_base_location", "")).strip()
if has_polaris_ozone_catalog and not polaris_catalog_base_location:
  polaris_catalog_base_location = "s3://{0}/polaris/".format(cluster_name.lower())
ranger_hdfs_enabled = str(default("/configurations/ranger-hdfs-plugin-properties/ranger-hdfs-plugin-enabled", "No")).lower() == "yes"
ranger_hdfs_service = str(default("/configurations/ranger-hdfs-security/ranger.plugin.hdfs.service.name", "")).strip()
if not ranger_hdfs_service or ranger_hdfs_service == "{{repo_name}}":
  ranger_hdfs_service = "{0}_hadoop".format(cluster_name)
ranger_ozone_enabled = str(default("/configurations/ranger-ozone-plugin-properties/ranger-ozone-plugin-enabled", "No")).lower() == "yes"
ranger_ozone_service = str(default("/configurations/ranger-ozone-security/ranger.plugin.ozone.service.name", "")).strip()
if not ranger_ozone_service or ranger_ozone_service == "{{repo_name}}":
  ranger_ozone_service = "{0}_ozone".format(cluster_name)
ranger_yarn_enabled = str(default("/configurations/ranger-yarn-plugin-properties/ranger-yarn-plugin-enabled", "No")).lower() == "yes"
ranger_yarn_service = str(default("/configurations/ranger-yarn-security/ranger.plugin.yarn.service.name", "")).strip()
if not ranger_yarn_service or ranger_yarn_service == "{{repo_name}}":
  ranger_yarn_service = "{0}_yarn".format(cluster_name)

# HDFS home of the Amoro user: Spark on YARN stages the optimizer job under /user/<amoro_user>/.sparkStaging.
has_namenode = len(default("/clusterHostInfo/namenode_hosts", [])) > 0
amoro_hdfs_home = "/user/" + amoro_user
if has_namenode:
  import functools
  from resource_management.libraries.functions import stack_select
  from resource_management.libraries.functions.get_not_managed_resources import get_not_managed_resources
  from resource_management.libraries.resources.hdfs_resource import HdfsResource

  HdfsResource = functools.partial(
    HdfsResource,
    user=config['configurations']['hadoop-env']['hdfs_user'],
    hdfs_resource_ignore_file="/var/lib/ambari-agent/data/.hdfs_resource_ignore",
    security_enabled=security_enabled,
    keytab=config['configurations']['hadoop-env']['hdfs_user_keytab'],
    kinit_path_local=kinit_path_local,
    hadoop_bin_dir=stack_select.get_hadoop_dir("bin"),
    hadoop_conf_dir=hadoop_conf_dir,
    principal_name=config['configurations']['hadoop-env']['hdfs_principal_name'],
    hdfs_site=config['configurations']['hdfs-site'],
    default_fs=config['configurations']['core-site']['fs.defaultFS'],
    immutable_paths=get_not_managed_resources(),
    dfs_type=default("/clusterLevelParams/dfs_type", ""),
  )
