/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

module.exports = [
  //***************************************** ODP stack **************************************
  /**********************************************OZONE***************************************/
  /* OZONE - JAVA - Memory - Settings */
    /* Ozone Manager JVM settings */
    /* Ozone Manager JVM */
    {
      "name": "ozone_manager_heapsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_MANAGER_SETTINGS",
      "index": 1
    },
    {
      "name": "ozone_manager_opt_newsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_MANAGER_SETTINGS",
      "index": 2
    },
    {
      "name": "ozone_manager_opt_maxnewsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_MANAGER_SETTINGS",
      "index": 3
    },
    {
      "name": "ozone_manager_opt_permsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_MANAGER_SETTINGS",
      "index": 4
    },
    {
      "name": "ozone_manager_opt_maxpermsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_MANAGER_SETTINGS",
      "index": 5
    },
    {
      "name": "ozone_manager_java_opts",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_MANAGER_SETTINGS",
      "index": 6
    },
    /* Ozone Storage Container Manager JVM */
    {
      "name": "ozone_scm_heapsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SETTINGS",
      "index": 1
    },
    {
      "name": "ozone_scm_opt_newsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SETTINGS",
      "index": 2
    },
    {
      "name": "ozone_scm_opt_maxnewsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SETTINGS",
      "index": 3
    },
    {
      "name": "ozone_scm_opt_permsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SETTINGS",
      "index": 4
    },
    {
      "name": "ozone_scm_opt_maxpermsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SETTINGS",
      "index": 5
    },
    {
      "name": "ozone_scm_java_opts",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SETTINGS",
      "index": 6
    },
    /* Ozone DataNode JVM */
    {
      "name": "ozone_datanode_heapsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SETTINGS",
      "index": 1
    },
    {
      "name": "ozone_datanode_opt_newsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SETTINGS",
      "index": 2
    },
    {
      "name": "ozone_datanode_opt_maxnewsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SETTINGS",
      "index": 3
    },
    {
      "name": "ozone_datanode_opt_permsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SETTINGS",
      "index": 4
    },
    {
      "name": "ozone_datanode_opt_maxpermsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SETTINGS",
      "index": 5
    },
    {
      "name": "ozone_datanode_java_opts",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SETTINGS",
      "index": 6
    },
    /* Ozone Recon JVM */
    {
      "name": "ozone_recon_heapsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SETTINGS",
      "index": 1
    },
    {
      "name": "ozone_recon_opt_newsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SETTINGS",
      "index": 2
    },
    {
      "name": "ozone_recon_opt_maxnewsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SETTINGS",
      "index": 3
    },
    {
      "name": "ozone_recon_opt_permsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SETTINGS",
      "index": 4
    },
    {
      "name": "ozone_recon_opt_maxpermsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SETTINGS",
      "index": 5
    },
    {
      "name": "ozone_recon_java_opts",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SETTINGS",
      "index": 6
    },
    /* Ozone S3G JVM */
    {
      "name": "ozone_s3g_heapsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SETTINGS",
      "index": 1
    },
    {
      "name": "ozone_s3g_opt_newsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SETTINGS",
      "index": 2
    },
    {
      "name": "ozone_s3g_opt_maxnewsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SETTINGS",
      "index": 3
    },
    {
      "name": "ozone_s3g_opt_permsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SETTINGS",
      "index": 4
    },
    {
      "name": "ozone_s3g_opt_maxpermsize",
      "isOverridable": false,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SETTINGS",
      "index": 5
    },
    {
      "name": "ozone_s3g_java_opts",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SETTINGS",
      "index": 6
    },
  /* OZONE - SSL/TLS - PROPERTIES */
    /* Ozone Manager SSL/TLS */
    {
      "name": "ozone_manager_ssl_enabled",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_OM_SSL",
      "index": 1
    },
    {
      "name": "ssl.server.keystore.location",
      "serviceName": "OZONE",
      "filename": "ssl-server-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 2
    },
    {
      "name": "ssl.server.keystore.password",
      "serviceName": "OZONE",
      "filename": "ssl-server-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 3
    },
    {
      "name": "ssl.server.keystore.type",
      "serviceName": "OZONE",
      "filename": "ssl-server-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 4
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "serviceName": "OZONE",
      "filename": "ssl-server-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 5
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "serviceName": "OZONE",
      "filename": "ssl-server-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 6
    },
    {
      "name": "hadoop.security.credential.provider.path",
      "serviceName": "OZONE",
      "filename": "ssl-server-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 7
    },
    {
      "name": "ssl.client.truststore.location",
      "serviceName": "OZONE",
      "filename": "ssl-client-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 8
    },
    {
      "name": "ssl.client.truststore.password",
      "serviceName": "OZONE",
      "filename": "ssl-client-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 9
    },
    {
      "name": "ssl.client.truststore.type",
      "serviceName": "OZONE",
      "filename": "ssl-client-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 10
    },
    {
      "name": "ssl.client.truststore.reload.interval",
      "serviceName": "OZONE",
      "filename": "ssl-client-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 11
    },
    /* Ozone Storage Container SSL/TLS */
    {
      "name": "ozone_scm_ssl_enabled",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SSL",
      "index": 1
    },
    {
      "name": "ssl.server.keystore.location",
      "serviceName": "OZONE",
      "filename": "ssl-server-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 2
    },
    {
      "name": "ssl.server.keystore.password",
      "serviceName": "OZONE",
      "filename": "ssl-server-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 3
    },
    {
      "name": "ssl.server.keystore.type",
      "serviceName": "OZONE",
      "filename": "ssl-server-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 4
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "serviceName": "OZONE",
      "filename": "ssl-server-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 5
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "serviceName": "OZONE",
      "filename": "ssl-server-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 6
    },
    {
      "name": "hadoop.security.credential.provider.path",
      "serviceName": "OZONE",
      "filename": "ssl-server-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 7
    },
    {
      "name": "ssl.client.truststore.location",
      "serviceName": "OZONE",
      "filename": "ssl-client-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 8
    },
    {
      "name": "ssl.client.truststore.password",
      "serviceName": "OZONE",
      "filename": "ssl-client-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 9
    },
    {
      "name": "ssl.client.truststore.type",
      "serviceName": "OZONE",
      "filename": "ssl-client-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 10
    },
    {
      "name": "ssl.client.truststore.reload.interval",
      "serviceName": "OZONE",
      "filename": "ssl-client-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 11
    },
    /* Ozone Datanode SSL/TLS */
    {
      "name": "ozone_datanode_ssl_enabled",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SSL",
      "index": 1
    },
    {
      "name": "ssl.server.keystore.location",
      "serviceName": "OZONE",
      "filename": "ssl-server-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 2
    },
    {
      "name": "ssl.server.keystore.password",
      "serviceName": "OZONE",
      "filename": "ssl-server-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 3
    },
    {
      "name": "ssl.server.keystore.type",
      "serviceName": "OZONE",
      "filename": "ssl-server-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 4
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "serviceName": "OZONE",
      "filename": "ssl-server-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 5
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "serviceName": "OZONE",
      "filename": "ssl-server-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 6
    },
    {
      "name": "hadoop.security.credential.provider.path",
      "serviceName": "OZONE",
      "filename": "ssl-server-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 7
    },
    {
      "name": "ssl.client.truststore.location",
      "serviceName": "OZONE",
      "filename": "ssl-client-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 8
    },
    {
      "name": "ssl.client.truststore.password",
      "serviceName": "OZONE",
      "filename": "ssl-client-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 9
    },
    {
      "name": "ssl.client.truststore.type",
      "serviceName": "OZONE",
      "filename": "ssl-client-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 10
    },
    {
      "name": "ssl.client.truststore.reload.interval",
      "serviceName": "OZONE",
      "filename": "ssl-client-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 11
    },
    /* Ozone Recon SSL/TLS */
    {
      "name": "ozone_recon_ssl_enabled",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SSL",
      "index": 1
    },
    {
      "name": "ssl.server.keystore.location",
      "serviceName": "OZONE",
      "filename": "ssl-server-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 2
    },
    {
      "name": "ssl.server.keystore.password",
      "serviceName": "OZONE",
      "filename": "ssl-server-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 3
    },
    {
      "name": "ssl.server.keystore.type",
      "serviceName": "OZONE",
      "filename": "ssl-server-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 4
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "serviceName": "OZONE",
      "filename": "ssl-server-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 5
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "serviceName": "OZONE",
      "filename": "ssl-server-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 6
    },
    {
      "name": "hadoop.security.credential.provider.path",
      "serviceName": "OZONE",
      "filename": "ssl-server-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 7
    },
    {
      "name": "ssl.client.truststore.location",
      "serviceName": "OZONE",
      "filename": "ssl-client-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 8
    },
    {
      "name": "ssl.client.truststore.password",
      "serviceName": "OZONE",
      "filename": "ssl-client-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 9
    },
    {
      "name": "ssl.client.truststore.type",
      "serviceName": "OZONE",
      "filename": "ssl-client-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 10
    },
    {
      "name": "ssl.client.truststore.reload.interval",
      "serviceName": "OZONE",
      "filename": "ssl-client-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 11
    },
    /* Ozone S3G SSL/TLS */
    {
      "name": "ozone_s3g_ssl_enabled",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SSL",
      "index": 1
    },
    {
      "name": "ssl.server.keystore.location",
      "serviceName": "OZONE",
      "filename": "ssl-server-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 2
    },
    {
      "name": "ssl.server.keystore.password",
      "serviceName": "OZONE",
      "filename": "ssl-server-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 3
    },
    {
      "name": "ssl.server.keystore.type",
      "serviceName": "OZONE",
      "filename": "ssl-server-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 4
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "serviceName": "OZONE",
      "filename": "ssl-server-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 5
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "serviceName": "OZONE",
      "filename": "ssl-server-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 6
    },
    {
      "name": "hadoop.security.credential.provider.path",
      "serviceName": "OZONE",
      "filename": "ssl-server-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 7
    },
    {
      "name": "ssl.client.truststore.location",
      "serviceName": "OZONE",
      "filename": "ssl-client-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 8
    },
    {
      "name": "ssl.client.truststore.password",
      "serviceName": "OZONE",
      "filename": "ssl-client-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 9
    },
    {
      "name": "ssl.client.truststore.type",
      "serviceName": "OZONE",
      "filename": "ssl-client-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 10
    },
    {
      "name": "ssl.client.truststore.reload.interval",
      "serviceName": "OZONE",
      "filename": "ssl-client-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 11
    },
    
  /* Ozone Manager LOG4J Settings */
    {
      "name": "ozone_manager_log_level",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_OM_LOG4J",
      "index": 1
    },
    {
      "name": "ozone_security_log_number_of_backup_files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-om.xml",
      "category": "OZONE_OM_LOG4J",
      "index": 2
    },
    {
      "name": "ozone_security_log_max_backup_size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-om.xml",
      "category": "OZONE_OM_LOG4J",
      "index": 3
    },
    {
      "name": "ozone_log_number_of_backup_files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-om.xml",
      "category": "OZONE_OM_LOG4J",
      "index": 4
    },
    {
      "name": "ozone_log_max_backup_size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-om.xml",
      "category": "OZONE_OM_LOG4J",
      "index": 5
    },
    {
      "name": "content",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-om.xml",
      "category": "OZONE_OM_LOG4J",
      "index": 6
    },
    /* Ozone SCM */
    {
      "name": "ozone_scm_log_level",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_LOG4J",
      "index": 1
    },
    {
      "name": "ozone_security_log_number_of_backup_files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-scm.xml",
      "category": "OZONE_SCM_LOG4J",
      "index": 2
    },
    {
      "name": "ozone_security_log_max_backup_size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-scm.xml",
      "category": "OZONE_SCM_LOG4J",
      "index": 3
    },
    {
      "name": "ozone_log_number_of_backup_files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-scm.xml",
      "category": "OZONE_SCM_LOG4J",
      "index": 4
    },
    {
      "name": "ozone_log_max_backup_size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-scm.xml",
      "category": "OZONE_SCM_LOG4J",
      "index": 5
    },
    {
      "name": "content",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-scm.xml",
      "category": "OZONE_SCM_LOG4J",
      "index": 6
    },
    /* Ozone S3G */
    {
      "name": "ozone_s3g_log_level",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_LOG4J",
      "index": 1
    },
    {
      "name": "ozone_security_log_number_of_backup_files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-s3g.xml",
      "category": "OZONE_S3G_LOG4J",
      "index": 2
    },
    {
      "name": "ozone_security_log_max_backup_size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-s3g.xml",
      "category": "OZONE_S3G_LOG4J",
      "index": 3
    },
    {
      "name": "ozone_log_number_of_backup_files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-s3g.xml",
      "category": "OZONE_S3G_LOG4J",
      "index": 4
    },
    {
      "name": "ozone_log_max_backup_size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-s3g.xml",
      "category": "OZONE_S3G_LOG4J",
      "index": 5
    },
    {
      "name": "content",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-s3g.xml",
      "category": "OZONE_S3G_LOG4J",
      "index": 6
    },
    /* Ozone Datanode */
    {
      "name": "ozone_datanode_log_level",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_LOG4J",
      "index": 1
    },
    {
      "name": "ozone_security_log_number_of_backup_files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-datanode.xml",
      "category": "OZONE_DN_LOG4J",
      "index": 2
    },
    {
      "name": "ozone_security_log_max_backup_size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-datanode.xml",
      "category": "OZONE_DN_LOG4J",
      "index": 3
    },
    {
      "name": "ozone_log_number_of_backup_files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-datanode.xml",
      "category": "OZONE_DN_LOG4J",
      "index": 4
    },
    {
      "name": "ozone_log_max_backup_size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-datanode.xml",
      "category": "OZONE_DN_LOG4J",
      "index": 5
    },
    {
      "name": "content",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-datanode.xml",
      "category": "OZONE_DN_LOG4J",
      "index": 6
    },
    /* Ozone Recon */
    {
      "name": "ozone_recon_log_level",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_LOG4J",
      "index": 1
    },
    {
      "name": "ozone_security_log_number_of_backup_files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-recon.xml",
      "category": "OZONE_RECON_LOG4J",
      "index": 2
    },
    {
      "name": "ozone_security_log_max_backup_size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-recon.xml",
      "category": "OZONE_RECON_LOG4J",
      "index": 3
    },
    {
      "name": "ozone_log_number_of_backup_files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-recon.xml",
      "category": "OZONE_RECON_LOG4J",
      "index": 4
    },
    {
      "name": "ozone_log_max_backup_size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-recon.xml",
      "category": "OZONE_RECON_LOG4J",
      "index": 5
    },
    {
      "name": "content",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-recon.xml",
      "category": "OZONE_RECON_LOG4J",
      "index": 6
    },
        /* Ozone Client */
    {
      "name": "ozone_gateway_log_level",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_GATEWAY_LOG4J",
      "index": 1
    },
    {
      "name": "ozone_security_log_number_of_backup_files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-properties.xml",
      "category": "OZONE_GATEWAY_LOG4J",
      "index": 2
    },
    {
      "name": "ozone_security_log_max_backup_size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-properties.xml",
      "category": "OZONE_GATEWAY_LOG4J",
      "index": 3
    },
    {
      "name": "ozone_log_number_of_backup_files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-properties.xml",
      "category": "OZONE_GATEWAY_LOG4J",
      "index": 4
    },
    {
      "name": "ozone_log_max_backup_size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-properties.xml",
      "category": "OZONE_GATEWAY_LOG4J",
      "index": 5
    },
    {
      "name": "content",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-properties.xml",
      "category": "OZONE_GATEWAY_LOG4J",
      "index": 6
    },
    {
      "filename": "ranger-ozone-plugin-properties.xml",
      "index": 1,
      "name": "ranger-ozone-plugin-enabled",
      "serviceName": "OZONE"
    }
  ];