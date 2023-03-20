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
      "displayName": "Ozone Manager JAVA Heapszie",
      "description": "Initial and maximum Java heap size for Ozone Manager (Java options -Xms and -Xmx).",
      "recommendedValue": "1024",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_MANAGER_SETTINGS",
      "index": 1
    },
    {
      "name": "ozone_manager_opt_newsize",
      "displayName": "Ozone Manager new generation size",
      "description": "Default size of Java new generation for Ozone Manager (Java option -XX:NewSize).",
      "recommendedValue": "200",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_MANAGER_SETTINGS",
      "index": 2
    },
    {
      "name": "ozone_manager_opt_maxnewsize",
      "displayName": "Ozone Manager maximum new generation size",
      "description": "Maximum size of Java new generation for Ozone Manager (Java option -XX:MaxnewSize).",
      "recommendedValue": "200",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_MANAGER_SETTINGS",
      "index": 3
    },
    {
      "name": "ozone_manager_opt_permsize",
      "displayName": "Ozone Manager permanent generation size",
      "description": "Default size of Java new generation for Ozone Manager (Java option -XX:PermSize).",
      "recommendedValue": "128",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_MANAGER_SETTINGS",
      "index": 4
    },
    {
      "name": "ozone_manager_opt_maxpermsize",
      "displayName": "Ozone Manager maximum permanent generation size",
      "description": "Maximum size of Java permanent generation for Ozone Manager (Java option -XX:MaxPermSize).",
      "recommendedValue": "256",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_MANAGER_SETTINGS",
      "index": 5
    },
    {
      "name": "ozone_manager_java_opts",
      "displayName": "Ozone Manager JVM Options",
      "description": "JAVA Arguments for Ozone Manager.",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_MANAGER_SETTINGS",
      "index": 6
    },
    /* Ozone Storage Container Manager JVM */
    {
      "name": "ozone_scm_heapsize",
      "displayName": "Ozone Storage Container Manager JAVA Heapszie",
      "description": "Initial and maximum Java heap size for Ozone Storage Container Manager (Java options -Xms and -Xmx).",
      "recommendedValue": "1024",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SETTINGS",
      "index": 1
    },
    {
      "name": "ozone_scm_opt_newsize",
      "displayName": "Ozone Storage Container Manager new generation size",
      "description": "Default size of Java new generation for Ozone Storage Container Manager (Java option -XX:NewSize).",
      "recommendedValue": "200",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SETTINGS",
      "index": 2
    },
    {
      "name": "ozone_scm_opt_maxnewsize",
      "displayName": "Ozone Storage Container Manager maximum new generation size",
      "description": "Maximum size of Java new generation for Ozone Storage Container Manager (Java option -XX:MaxnewSize).",
      "recommendedValue": "200",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SETTINGS",
      "index": 3
    },
    {
      "name": "ozone_scm_opt_permsize",
      "displayName": "Ozone Storage Container Manager permanent generation size",
      "description": "Default size of Java new generation for Ozone Storage Container Manager (Java option -XX:PermSize).",
      "recommendedValue": "128",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SETTINGS",
      "index": 4
    },
    {
      "name": "ozone_scm_opt_maxpermsize",
      "displayName": "Ozone Storage Container Manager maximum permanent generation size",
      "description": "Maximum size of Java permanent generation for Ozone Storage Container Manager (Java option -XX:MaxPermSize).",
      "recommendedValue": "256",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SETTINGS",
      "index": 5
    },
    {
      "name": "ozone_scm_java_opts",
      "displayName": "Ozone Storage Container Manager JVM Options",
      "description": "JAVA Arguments for Ozone Manager.",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SETTINGS",
      "index": 6
    },
    /* Ozone DataNode JVM */
    {
      "name": "ozone_datanode_heapsize",
      "displayName": "Ozone DataNode JAVA Heapszie",
      "description": "Initial and maximum Java heap size for Ozone DataNode (Java options -Xms and -Xmx).",
      "recommendedValue": "1024",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SETTINGS",
      "index": 1
    },
    {
      "name": "ozone_datanode_opt_newsize",
      "displayName": "Ozone DataNode new generation size",
      "description": "Default size of Java new generation for Ozone DataNode (Java option -XX:NewSize).",
      "recommendedValue": "200",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SETTINGS",
      "index": 2
    },
    {
      "name": "ozone_datanode_opt_maxnewsize",
      "displayName": "Ozone DataNode maximum new generation size",
      "description": "Maximum size of Java new generation for Ozone DataNode (Java option -XX:MaxnewSize).",
      "recommendedValue": "200",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SETTINGS",
      "index": 3
    },
    {
      "name": "ozone_datanode_opt_permsize",
      "displayName": "Ozone DataNode permanent generation size",
      "description": "Default size of Java new generation for Ozone DataNode (Java option -XX:PermSize).",
      "recommendedValue": "128",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SETTINGS",
      "index": 4
    },
    {
      "name": "ozone_datanode_opt_maxpermsize",
      "displayName": "Ozone DataNode maximum permanent generation size",
      "description": "Maximum size of Java permanent generation for Ozone DataNode (Java option -XX:MaxPermSize).",
      "recommendedValue": "256",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SETTINGS",
      "index": 5
    },
    {
      "name": "ozone_datanode_java_opts",
      "displayName": "Ozone DataNode JVM Options",
      "description": "JAVA Arguments for Ozone Manager.",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SETTINGS",
      "index": 6
    },
    /* Ozone Recon JVM */
    {
      "name": "ozone_recon_heapsize",
      "displayName": "Ozone Recon UI JAVA Heapszie",
      "description": "Initial and maximum Java heap size for Ozone Recon UI (Java options -Xms and -Xmx).",
      "recommendedValue": "1024",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SETTINGS",
      "index": 1
    },
    {
      "name": "ozone_recon_opt_newsize",
      "displayName": "Ozone Recon UI new generation size",
      "description": "Default size of Java new generation for Ozone Recon UI (Java option -XX:NewSize).",
      "recommendedValue": "200",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SETTINGS",
      "index": 2
    },
    {
      "name": "ozone_recon_opt_maxnewsize",
      "displayName": "Ozone Recon UI maximum new generation size",
      "description": "Maximum size of Java new generation for Ozone Recon UI (Java option -XX:MaxnewSize).",
      "recommendedValue": "200",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SETTINGS",
      "index": 3
    },
    {
      "name": "ozone_recon_opt_permsize",
      "displayName": "Ozone Recon UI permanent generation size",
      "description": "Default size of Java new generation for Ozone Recon UI (Java option -XX:PermSize).",
      "recommendedValue": "128",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SETTINGS",
      "index": 4
    },
    {
      "name": "ozone_recon_opt_maxpermsize",
      "displayName": "Ozone Recon UI maximum permanent generation size",
      "description": "Maximum size of Java permanent generation for Ozone Recon UI (Java option -XX:MaxPermSize).",
      "recommendedValue": "256",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SETTINGS",
      "index": 5
    },
    {
      "name": "ozone_recon_java_opts",
      "displayName": "Ozone Recon JVM Options",
      "description": "JAVA Arguments for Ozone Manager.",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SETTINGS",
      "index": 6
    },
    /* Ozone S3G JVM */
    {
      "name": "ozone_s3g_heapsize",
      "displayName": "Ozone S3 Gateway JAVA Heapszie",
      "description": "Initial and maximum Java heap size for Ozone S3 Gateway (Java options -Xms and -Xmx).",
      "recommendedValue": "1024",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SETTINGS",
      "index": 1
    },
    {
      "name": "ozone_s3g_opt_newsize",
      "displayName": "Ozone S3 Gateway new generation size",
      "description": "Default size of Java new generation for Ozone S3 Gateway (Java option -XX:NewSize).",
      "recommendedValue": "200",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SETTINGS",
      "index": 2
    },
    {
      "name": "ozone_s3g_opt_maxnewsize",
      "displayName": "Ozone S3 Gateway maximum new generation size",
      "description": "Maximum size of Java new generation for Ozone S3 Gateway (Java option -XX:MaxnewSize).",
      "recommendedValue": "200",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SETTINGS",
      "index": 3
    },
    {
      "name": "ozone_s3g_opt_permsize",
      "displayName": "Ozone S3 Gateway permanent generation size",
      "description": "Default size of Java new generation for Ozone S3 Gateway (Java option -XX:PermSize).",
      "recommendedValue": "128",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SETTINGS",
      "index": 4
    },
    {
      "name": "ozone_s3g_opt_maxpermsize",
      "displayName": "Ozone S3 Gateway maximum permanent generation size",
      "description": "Maximum size of Java permanent generation for Ozone S3 Gateway (Java option -XX:MaxPermSize).",
      "recommendedValue": "256",
      "displayType": "int",
      "unit": "MB",
      "isOverridable": false,
      "isVisible": true,
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SETTINGS",
      "index": 5
    },
    {
      "name": "ozone_s3g_java_opts",
      "displayName": "Ozone S3 JVM Options",
      "description": "JAVA Arguments for Ozone Manager.",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SETTINGS",
      "index": 6
    },
  /* OZONE - SSL/TLS - PROPERTIES */
    /* Ozone Manager SSL/TLS */
    {
      "name": "ozone_manager_ssl_enabled",
      "displayName": "Ozone Manager - Enable SSL",
      "displayType": "checkbox",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_OM_SSL",
      "index": 1
    },
    {
      "name": "ssl.server.keystore.location",
      "displayName": "Ozone Manager TLS/SSL keystore location",
      "serviceName": "OZONE",
      "filename": "ssl-server-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 2
    },
    {
      "name": "ssl.server.keystore.password",
      "displayName": "Ozone Manager TLS/SSL keystore password",
      "serviceName": "OZONE",
      "displayType": "password",
      "filename": "ssl-server-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 3
    },
    {
      "name": "ssl.server.keystore.type",
      "displayName":  "Ozone Manager TLS/SSL keystore type",
      "serviceName": "OZONE",
      "filename": "ssl-server-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 4
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "displayName": "Ozone Manager TLS/SSL keystore exclude cipher",
      "serviceName": "OZONE",
      "filename": "ssl-server-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 5
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "displayName": "Ozone Manager TLS/SSL keystore exclude cipher",
      "serviceName": "OZONE",
      "filename": "ssl-server-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 6
    },
    {
      "name": "hadoop.security.credential.provider.path",
      "displayName": "Ozone Manager TLS/SSL credentials store",
      "serviceName": "OZONE",
      "filename": "ssl-server-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 7
    },
    {
      "name": "ssl.client.truststore.location",
      "displayName": "Ozone Manager TLS/SSL truststore location",
      "serviceName": "OZONE",
      "filename": "ssl-client-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 8
    },
    {
      "name": "ssl.client.truststore.password",
      "displayName": "Ozone Manager TLS/SSL truststore password",
      "displayType": "password",
      "serviceName": "OZONE",
      "filename": "ssl-client-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 9
    },
    {
      "name": "ssl.client.truststore.type",
      "displayName": "Ozone Manager TLS/SSL truststore type",
      "serviceName": "OZONE",
      "filename": "ssl-client-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 10
    },
    {
      "name": "ssl.client.truststore.reload.interval",
      "displayName": "Ozone Manager TLS/SSL truststore reload interval",
      "displayType": "int",
      "serviceName": "OZONE",
      "filename": "ssl-client-om.xml",
      "category": "OZONE_OM_SSL",
      "index": 11
    },
    /* Ozone Storage Container SSL/TLS */
    {
      "name": "ozone_scm_ssl_enabled",
      "displayName": "Ozone Storage Container Manager - Enable SSL",
      "displayType": "checkbox",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_SSL",
      "index": 1
    },
    {
      "name": "ssl.server.keystore.location",
      "displayName": "Ozone Storage Container Manager TLS/SSL keystore location",
      "serviceName": "OZONE",
      "filename": "ssl-server-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 2
    },
    {
      "name": "ssl.server.keystore.password",
      "displayName": "Ozone Storage Container Manager TLS/SSL keystore password",
      "serviceName": "OZONE",
      "displayType": "password",
      "filename": "ssl-server-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 3
    },
    {
      "name": "ssl.server.keystore.type",
      "displayName":  "Ozone Storage Container Manager TLS/SSL keystore type",
      "serviceName": "OZONE",
      "filename": "ssl-server-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 4
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "displayName": "Ozone Storage Container Manager TLS/SSL keystore exclude cipher",
      "serviceName": "OZONE",
      "filename": "ssl-server-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 5
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "displayName": "Ozone Storage Container Manager TLS/SSL keystore exclude cipher",
      "serviceName": "OZONE",
      "filename": "ssl-server-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 6
    },
    {
      "name": "hadoop.security.credential.provider.path",
      "displayName": "Ozone Storage Container Manager TLS/SSL credentials store",
      "serviceName": "OZONE",
      "filename": "ssl-server-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 7
    },
    {
      "name": "ssl.client.truststore.location",
      "displayName": "Ozone Storage Container Manager TLS/SSL truststore location",
      "serviceName": "OZONE",
      "filename": "ssl-client-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 8
    },
    {
      "name": "ssl.client.truststore.password",
      "displayName": "Ozone Storage Container Manager TLS/SSL truststore password",
      "displayType": "password",
      "serviceName": "OZONE",
      "filename": "ssl-client-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 9
    },
    {
      "name": "ssl.client.truststore.type",
      "displayName": "Ozone Storage Container Manager TLS/SSL truststore type",
      "serviceName": "OZONE",
      "filename": "ssl-client-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 10
    },
    {
      "name": "ssl.client.truststore.reload.interval",
      "displayName": "Ozone Storage Container Manager TLS/SSL truststore reload interval",
      "displayType": "int",
      "serviceName": "OZONE",
      "filename": "ssl-client-scm.xml",
      "category": "OZONE_SCM_SSL",
      "index": 11
    },
    /* Ozone Datanode SSL/TLS */
    {
      "name": "ozone_datanode_ssl_enabled",
      "displayName": "Ozone DataNode - Enable SSL",
      "displayType": "checkbox",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_SSL",
      "index": 1
    },
    {
      "name": "ssl.server.keystore.location",
      "displayName": "Ozone DataNode TLS/SSL keystore location",
      "serviceName": "OZONE",
      "filename": "ssl-server-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 2
    },
    {
      "name": "ssl.server.keystore.password",
      "displayName": "Ozone DataNode TLS/SSL keystore password",
      "serviceName": "OZONE",
      "displayType": "password",
      "filename": "ssl-server-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 3
    },
    {
      "name": "ssl.server.keystore.type",
      "displayName":  "Ozone DataNode TLS/SSL keystore type",
      "serviceName": "OZONE",
      "filename": "ssl-server-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 4
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "displayName": "Ozone DataNode TLS/SSL keystore exclude cipher",
      "serviceName": "OZONE",
      "filename": "ssl-server-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 5
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "displayName": "Ozone DataNode TLS/SSL keystore exclude cipher",
      "serviceName": "OZONE",
      "filename": "ssl-server-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 6
    },
    {
      "name": "hadoop.security.credential.provider.path",
      "displayName": "Ozone DataNode TLS/SSL credentials store",
      "serviceName": "OZONE",
      "filename": "ssl-server-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 7
    },
    {
      "name": "ssl.client.truststore.location",
      "displayName": "Ozone DataNode TLS/SSL truststore location",
      "serviceName": "OZONE",
      "filename": "ssl-client-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 8
    },
    {
      "name": "ssl.client.truststore.password",
      "displayName": "Ozone DataNode TLS/SSL truststore password",
      "displayType": "password",
      "serviceName": "OZONE",
      "filename": "ssl-client-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 9
    },
    {
      "name": "ssl.client.truststore.type",
      "displayName": "Ozone DataNode TLS/SSL truststore type",
      "serviceName": "OZONE",
      "filename": "ssl-client-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 10
    },
    {
      "name": "ssl.client.truststore.reload.interval",
      "displayName": "Ozone DataNode TLS/SSL truststore reload interval",
      "displayType": "int",
      "serviceName": "OZONE",
      "filename": "ssl-client-datanode.xml",
      "category": "OZONE_DN_SSL",
      "index": 11
    },
    /* Ozone Recon SSL/TLS */
    {
      "name": "ozone_recon_ssl_enabled",
      "displayName": "Ozone Recon UI - Enable SSL",
      "displayType": "checkbox",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_SSL",
      "index": 1
    },
    {
      "name": "ssl.server.keystore.location",
      "displayName": "Ozone Recon UI TLS/SSL keystore location",
      "serviceName": "OZONE",
      "filename": "ssl-server-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 2
    },
    {
      "name": "ssl.server.keystore.password",
      "displayName": "Ozone Recon UI TLS/SSL keystore password",
      "serviceName": "OZONE",
      "displayType": "password",
      "filename": "ssl-server-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 3
    },
    {
      "name": "ssl.server.keystore.type",
      "displayName":  "Ozone Recon UI TLS/SSL keystore type",
      "serviceName": "OZONE",
      "filename": "ssl-server-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 4
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "displayName": "Ozone Recon UI TLS/SSL keystore exclude cipher",
      "serviceName": "OZONE",
      "filename": "ssl-server-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 5
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "displayName": "Ozone Recon UI TLS/SSL keystore exclude cipher",
      "serviceName": "OZONE",
      "filename": "ssl-server-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 6
    },
    {
      "name": "hadoop.security.credential.provider.path",
      "displayName": "Ozone Recon UI TLS/SSL credentials store",
      "serviceName": "OZONE",
      "filename": "ssl-server-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 7
    },
    {
      "name": "ssl.client.truststore.location",
      "displayName": "Ozone Recon UI TLS/SSL truststore location",
      "serviceName": "OZONE",
      "filename": "ssl-client-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 8
    },
    {
      "name": "ssl.client.truststore.password",
      "displayName": "Ozone Recon UI TLS/SSL truststore password",
      "displayType": "password",
      "serviceName": "OZONE",
      "filename": "ssl-client-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 9
    },
    {
      "name": "ssl.client.truststore.type",
      "displayName": "Ozone Recon UI TLS/SSL truststore type",
      "serviceName": "OZONE",
      "filename": "ssl-client-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 10
    },
    {
      "name": "ssl.client.truststore.reload.interval",
      "displayName": "Ozone Recon UI TLS/SSL truststore reload interval",
      "displayType": "int",
      "serviceName": "OZONE",
      "filename": "ssl-client-recon.xml",
      "category": "OZONE_RECON_SSL",
      "index": 11
    },
    /* Ozone S3G SSL/TLS */
    {
      "name": "ozone_s3g_ssl_enabled",
      "displayName": "Ozone Recon UI - Enable SSL",
      "displayType": "checkbox",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_SSL",
      "index": 1
    },
    {
      "name": "ssl.server.keystore.location",
      "displayName": "Ozone S3 Gateway TLS/SSL keystore location",
      "serviceName": "OZONE",
      "filename": "ssl-server-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 2
    },
    {
      "name": "ssl.server.keystore.password",
      "displayName": "Ozone S3 Gateway TLS/SSL keystore password",
      "serviceName": "OZONE",
      "displayType": "password",
      "filename": "ssl-server-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 3
    },
    {
      "name": "ssl.server.keystore.type",
      "displayName":  "Ozone S3 Gateway TLS/SSL keystore type",
      "serviceName": "OZONE",
      "filename": "ssl-server-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 4
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "displayName": "Ozone S3 Gateway TLS/SSL keystore exclude cipher",
      "serviceName": "OZONE",
      "filename": "ssl-server-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 5
    },
    {
      "name": "ssl.server.exclude.cipher.list",
      "displayName": "Ozone S3 Gateway TLS/SSL keystore exclude cipher",
      "serviceName": "OZONE",
      "filename": "ssl-server-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 6
    },
    {
      "name": "hadoop.security.credential.provider.path",
      "displayName": "Ozone S3 Gateway TLS/SSL credentials store",
      "serviceName": "OZONE",
      "filename": "ssl-server-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 7
    },
    {
      "name": "ssl.client.truststore.location",
      "displayName": "Ozone S3 Gateway TLS/SSL truststore location",
      "serviceName": "OZONE",
      "filename": "ssl-client-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 8
    },
    {
      "name": "ssl.client.truststore.password",
      "displayName": "Ozone S3 Gateway TLS/SSL truststore password",
      "displayType": "password",
      "serviceName": "OZONE",
      "filename": "ssl-client-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 9
    },
    {
      "name": "ssl.client.truststore.type",
      "displayName": "Ozone S3 Gateway TLS/SSL truststore type",
      "serviceName": "OZONE",
      "filename": "ssl-client-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 10
    },
    {
      "name": "ssl.client.truststore.reload.interval",
      "displayName": "Ozone S3 Gateway TLS/SSL truststore reload interval",
      "displayType": "int",
      "serviceName": "OZONE",
      "filename": "ssl-client-s3g.xml",
      "category": "OZONE_S3G_SSL",
      "index": 11
    },
    
  /* Ozone Manager LOG4J Settings */
    {
      "name": "ozone_manager_log_level",
      "displayName": "Ozone Manager - Log Level",
      "displayType": "list",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_OM_LOG4J",
      "index": 1
    },
    {
      "name": "ozone_security_log_number_of_backup_files",
      "displayName": "Ozone Manager security log - maximum number of files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-om.xml",
      "category": "OZONE_OM_LOG4J",
      "index": 2
    },
    {
      "name": "ozone_security_log_max_backup_size",
      "displayName": "Ozone Manager security log - maximum file size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-om.xml",
      "category": "OZONE_OM_LOG4J",
      "index": 3
    },
    {
      "name": "ozone_log_number_of_backup_files",
      "displayName": "Ozone Manager log - maximum number of files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-om.xml",
      "category": "OZONE_OM_LOG4J",
      "index": 4
    },
    {
      "name": "ozone_log_max_backup_size",
      "displayName": "Ozone Manager log - maximum file size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-om.xml",
      "category": "OZONE_OM_LOG4J",
      "index": 5
    },
    {
      "name": "content",
      "displayName": "Ozone Manager log - content",
      "displayType": "multiLine",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-om.xml",
      "category": "OZONE_OM_LOG4J",
      "index": 6
    },
    /* Ozone SCM */
    {
      "name": "ozone_scm_log_level",
      "displayName": "Ozone Storage Container Manager - Log Level",
      "displayType": "list",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_SCM_LOG4J",
      "index": 1
    },
    {
      "name": "ozone_security_log_number_of_backup_files",
      "displayName": "Ozone Storage Container Manager security log - maximum number of files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-scm.xml",
      "category": "OZONE_SCM_LOG4J",
      "index": 2
    },
    {
      "name": "ozone_security_log_max_backup_size",
      "displayName": "Ozone Storage Container Manager security log - maximum file size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-scm.xml",
      "category": "OZONE_SCM_LOG4J",
      "index": 3
    },
    {
      "name": "ozone_log_number_of_backup_files",
      "displayName": "Ozone Storage Container Manager log - maximum number of files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-scm.xml",
      "category": "OZONE_SCM_LOG4J",
      "index": 4
    },
    {
      "name": "ozone_log_max_backup_size",
      "displayName": "Ozone Storage Container Manager log - maximum file size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-scm.xml",
      "category": "OZONE_SCM_LOG4J",
      "index": 5
    },
    {
      "name": "content",
      "displayName": "Ozone Storage Container Manager log - content",
      "serviceName": "OZONE",
      "displayType": "multiLine",
      "filename": "ozone-log4j-scm.xml",
      "category": "OZONE_SCM_LOG4J",
      "index": 6
    },
    /* Ozone S3G */
    {
      "name": "ozone_s3g_log_level",
      "displayName": "Ozone S3 Gateway - Log Level",
      "displayType": "list",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_S3G_LOG4J",
      "index": 1
    },
    {
      "name": "ozone_security_log_number_of_backup_files",
      "displayName": "Ozone S3 Gateway security log - maximum number of files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-s3g.xml",
      "category": "OZONE_S3G_LOG4J",
      "index": 2
    },
    {
      "name": "ozone_security_log_max_backup_size",
      "displayName": "Ozone S3 Gateway security log - maximum file size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-s3g.xml",
      "category": "OZONE_S3G_LOG4J",
      "index": 3
    },
    {
      "name": "ozone_log_number_of_backup_files",
      "displayName": "Ozone S3 Gateway log - maximum number of files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-s3g.xml",
      "category": "OZONE_S3G_LOG4J",
      "index": 4
    },
    {
      "name": "ozone_log_max_backup_size",
      "displayName": "Ozone S3 Gateway log - maximum file size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-s3g.xml",
      "category": "OZONE_S3G_LOG4J",
      "index": 5
    },
    {
      "name": "content",
      "displayName": "Ozone S3 Gateway log - content",
      "displayType": "multiLine",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-s3g.xml",
      "category": "OZONE_S3G_LOG4J",
      "index": 6
    },
    /* Ozone Datanode */
    {
      "name": "ozone_datanode_log_level",
      "displayName": "Ozone DataNode - Log Level",
      "displayType": "list",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_DN_LOG4J",
      "index": 1
    },
    {
      "name": "ozone_security_log_number_of_backup_files",
      "displayName": "Ozone DataNode security log - maximum number of files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-datanode.xml",
      "category": "OZONE_DN_LOG4J",
      "index": 2
    },
    {
      "name": "ozone_security_log_max_backup_size",
      "displayName": "Ozone DataNode security log - maximum file size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-datanode.xml",
      "category": "OZONE_DN_LOG4J",
      "index": 3
    },
    {
      "name": "ozone_log_number_of_backup_files",
      "displayName": "Ozone DataNode log - maximum number of files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-datanode.xml",
      "category": "OZONE_DN_LOG4J",
      "index": 4
    },
    {
      "name": "ozone_log_max_backup_size",
      "displayName": "Ozone DataNode log - maximum file size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-datanode.xml",
      "category": "OZONE_DN_LOG4J",
      "index": 5
    },
    {
      "name": "content",
      "displayName": "Ozone DataNode log - content",
      "displayType": "multiLine",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-datanode.xml",
      "category": "OZONE_DN_LOG4J",
      "index": 6
    },
    /* Ozone Recon */
    {
      "name": "ozone_recon_log_level",
      "displayName": "Ozone Recon - Log Level",
      "displayType": "list",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_RECON_LOG4J",
      "index": 1
    },
    {
      "name": "ozone_security_log_number_of_backup_files",
      "displayName": "Ozone Recon security log - maximum number of files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-recon.xml",
      "category": "OZONE_RECON_LOG4J",
      "index": 2
    },
    {
      "name": "ozone_security_log_max_backup_size",
      "displayName": "Ozone Recon security log - maximum file size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-recon.xml",
      "category": "OZONE_RECON_LOG4J",
      "index": 3
    },
    {
      "name": "ozone_log_number_of_backup_files",
      "displayName": "Ozone Recon log - maximum number of files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-recon.xml",
      "category": "OZONE_RECON_LOG4J",
      "index": 4
    },
    {
      "name": "ozone_log_max_backup_size",
      "displayName": "Ozone Recon log - maximum file size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-recon.xml",
      "category": "OZONE_RECON_LOG4J",
      "index": 5
    },
    {
      "name": "content",
      "displayName": "Ozone Recon log - content",
      "displayType": "multiLine",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-recon.xml",
      "category": "OZONE_RECON_LOG4J",
      "index": 6
    },
        /* Ozone Client */
    {
      "name": "ozone_gateway_log_level",
      "displayName": "Ozone Client Gateway - Log Level",
      "displayType": "list",
      "serviceName": "OZONE",
      "filename": "ozone-env.xml",
      "category": "OZONE_GATEWAY_LOG4J",
      "index": 1
    },
    {
      "name": "ozone_security_log_number_of_backup_files",
      "displayName": "Ozone Client Gateway security log - maximum number of files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-properties.xml",
      "category": "OZONE_GATEWAY_LOG4J",
      "index": 2
    },
    {
      "name": "ozone_security_log_max_backup_size",
      "displayName": "Ozone Client Gateway security log - maximum file size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-properties.xml",
      "category": "OZONE_GATEWAY_LOG4J",
      "index": 3
    },
    {
      "name": "ozone_log_number_of_backup_files",
      "displayName": "Ozone Client Gateway log - maximum number of files",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-properties.xml",
      "category": "OZONE_GATEWAY_LOG4J",
      "index": 4
    },
    {
      "name": "ozone_log_max_backup_size",
      "displayName": "Ozone Client Gateway log - maximum file size",
      "serviceName": "OZONE",
      "filename": "ozone-log4j-properties.xml",
      "category": "OZONE_GATEWAY_LOG4J",
      "index": 5
    },
    {
      "name": "content",
      "displayName": "Ozone Client Gateway log - content",
      "displayType": "multiLine",
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