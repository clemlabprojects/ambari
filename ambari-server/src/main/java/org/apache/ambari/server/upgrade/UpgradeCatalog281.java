/*
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
package org.apache.ambari.server.upgrade;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Injector;


/**
 * The {@link UpgradeCatalog281} upgrades Ambari from 2.7.11 to 2.8.1.
 */
public class UpgradeCatalog281 extends AbstractUpgradeCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(UpgradeCatalog281.class);

    private static final String REQUEST_SCHEDULE_TABLE_NAME = "requestschedule";
    private static final String REQUEST_SCHEDULE_BATCH_TOLERATION_LIMIT_PER_BATCH_COLUMN_NAME = "batch_toleration_limit_per_batch";
    @Inject
    public UpgradeCatalog281(Injector injector) {
        super(injector);
    }

    @Override
    public String getSourceVersion() {
        return "2.7.11";
    }

    @Override
    public String getTargetVersion() {
        return "2.8.1";
    }

    protected static final String AMS_ENV_CONFIG = "ams-env";
    protected static final String AMS_HBASE_ENV_CONFIG = "ams-hbase-env";

    protected static final String INFRA_SOLR_ENV_CONFIG = "infra-solr-env";
    protected static final String INFRA_SOLR_ENV_GC_TUNE = "infra_solr_gc_tune";

    protected static final String INFRA_SOLR_ENV_GC_LOG_OPTS = "infra_solr_gc_log_opts";

    protected static final String INFRA_SOLR_ENV_CONTENT = "content";
    @Override
    protected void executeDDLUpdates() throws AmbariException, SQLException {
    }

    @Override
    protected void executePreDMLUpdates() throws AmbariException, SQLException {
    }

    @Override
    protected void executeDMLUpdates() throws AmbariException, SQLException {
        removeAmbariMetricsEnvJDK8Options();
        removeAmbariInfraSolrEnvJDK8Options();
    }

    protected void removeAmbariMetricsEnvJDK8Options() throws AmbariException {
            AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
            Clusters clusters = ambariManagementController.getClusters();
            if (clusters != null) {
                Map<String, Cluster> clusterMap = getCheckedClusterMap(clusters);
                if (clusterMap != null && !clusterMap.isEmpty()) {
                    for (final Cluster cluster : clusterMap.values()) {
                        Set<String> installedServices = cluster.getServices().keySet();

                        // Technically, ambari env as part of the stack should be based in java_home which is made for stack JDK version
                        // and its properties should be updated during cluster upgrade
                        // because Ambari Metrics is part of Apache Ambari, we need to update its env to support JDK 17 prior to stack upgrade
                        // which can be done during schema upgrade during ambari-server upgrade command.


                            Map<String, String> newAMSEnvProperties = new HashMap<>();
                            Map<String, String> newAMSEnvHBaseProperties = new HashMap<>();
                            String amsEnvConfig = 
                                "\n# Set environment variables here.\n\n"
                                + "# AMS instance name\n"
                                + "export AMS_INSTANCE_NAME={{hostname}}\n\n"
                                + "# The java implementation to use. Java 1.6 required.\n"
                                + "export JAVA_HOME={{java64_home}}\n\n"
                                + "# Collector Log directory for log4j\n"
                                + "export AMS_COLLECTOR_LOG_DIR={{ams_collector_log_dir}}\n\n"
                                + "# Monitor Log directory for outfile\n"
                                + "export AMS_MONITOR_LOG_DIR={{ams_monitor_log_dir}}\n\n"
                                + "# Collector pid directory\n"
                                + "export AMS_COLLECTOR_PID_DIR={{ams_collector_pid_dir}}\n\n"
                                + "# Monitor pid directory\n"
                                + "export AMS_MONITOR_PID_DIR={{ams_monitor_pid_dir}}\n\n"
                                + "# AMS HBase pid directory\n"
                                + "export AMS_HBASE_PID_DIR={{hbase_pid_dir}}\n\n"
                                + "# AMS Collector heapsize\n"
                                + "export AMS_COLLECTOR_HEAPSIZE={{metrics_collector_heapsize}}\n\n"
                                + "# HBase Tables Initialization check enabled\n"
                                + "export AMS_HBASE_INIT_CHECK_ENABLED={{ams_hbase_init_check_enabled}}\n\n"
                                + "# AMS Collector options\n"
                                + "{% if java_version <= 8 %}\n"
                                + "export AMS_COLLECTOR_OPTS=\"-Djava.library.path=/usr/lib/ams-hbase/lib/hadoop-native\"\n"
                                + "{% else %}\n"
                                + "export AMS_COLLECTOR_OPTS=\"-Djava.library.path=/usr/lib/ams-hbase/lib/hadoop-native "
                                + "--add-opens java.base/java.lang=ALL-UNNAMED\"\n"
                                + "{% endif %}\n"
                                + "{% if security_enabled %}\n"
                                + "export AMS_COLLECTOR_OPTS=\"$AMS_COLLECTOR_OPTS -Djava.security.auth.login.config="
                                + "{{ams_collector_jaas_config_file}}\"\n"
                                + "{% endif %}\n\n"
                                + "# AMS Collector GC options\n"
                                + "{% if java_version <= 8 %}\n"
                                + "export AMS_COLLECTOR_GC_OPTS=\"-XX:+UseConcMarkSweepGC -verbose:gc -XX:+PrintGCDetails "
                                + "-XX:+PrintGCDateStamps -Xloggc:{{ams_collector_log_dir}}/collector-gc.log-`date +'%Y%m%d%H%M'`\"\n"
                                + "export AMS_COLLECTOR_OPTS=\"$AMS_COLLECTOR_OPTS $AMS_COLLECTOR_GC_OPTS\"\n"
                                + "{% else %}\n"
                                + "export AMS_COLLECTOR_GC_OPTS=\" -verbose:gc -XX:+PrintGCDetails "
                                + "-Xloggc:{{ams_collector_log_dir}}/collector-gc.log-`date +'%Y%m%d%H%M'`\"\n"
                                + "export AMS_COLLECTOR_OPTS=\"$AMS_COLLECTOR_OPTS $AMS_COLLECTOR_GC_OPTS\"\n"
                                + "{% endif %}\n\n"
                                + "# Metrics collector host will be blacklisted for specified number of seconds if metric monitor "
                                + "failed to connect to it.\n"
                                + "export AMS_FAILOVER_STRATEGY_BLACKLISTED_INTERVAL="
                                + "{{failover_strategy_blacklisted_interval}}\n\n"
                                + "# Extra Java CLASSPATH elements for Metrics Collector. Optional.\n"
                                + "export COLLECTOR_ADDITIONAL_CLASSPATH={{ams_classpath_additional}}\n";

                            String amsHBaseEnv =
                                "\n# Set environment variables here.\n\n"
                                        + "# The java implementation to use. Java 1.6+ required.\n"
                                        + "export JAVA_HOME={{java64_home}}\n\n"
                                        + "# HBase Configuration directory\n"
                                        + "export HBASE_CONF_DIR=${HBASE_CONF_DIR:-{{hbase_conf_dir}}}\n\n"
                                        + "# Extra Java CLASSPATH elements. Optional.\n"
                                        + "additional_cp={{hbase_classpath_additional}}\n"
                                        + "if [  -n \"$additional_cp\" ];\n"
                                        + "then\n"
                                        + "  export HBASE_CLASSPATH=${HBASE_CLASSPATH}:$additional_cp\n"
                                        + "else\n"
                                        + "  export HBASE_CLASSPATH=${HBASE_CLASSPATH}\n"
                                        + "fi\n\n"
                                        + "export HBASE_CLASSPATH=${HBASE_CLASSPATH}:/usr/lib/ambari-metrics-collector/"
                                        + "hadoop-shaded-guava-1.1.1.jar\n\n"
                                        + "# The maximum amount of heap to use for hbase shell.\n"
                                        + "export HBASE_SHELL_OPTS=\"-Xmx256m\"\n\n"
                                        + "# Extra Java runtime options.\n"
                                        + "{% if java_version <= 8 %}\n"
                                        + "export HBASE_OPTS=\"-XX:+UseConcMarkSweepGC -XX:ErrorFile={{hbase_log_dir}}/"
                                        + "hs_err_pid%p.log -Djava.io.tmpdir={{hbase_tmp_dir}}\"\n"
                                        + "export SERVER_GC_OPTS=\"-verbose:gc -XX:+PrintGCDetails -XX:+PrintGCDateStamps "
                                        + "-Xloggc:{{hbase_log_dir}}/gc.log-`date +'%Y%m%d%H%M'`\"\n"
                                        + "{% else %}\n"
                                        + "export HBASE_OPTS=\"-XX:ErrorFile={{hbase_log_dir}}/hs_err_pid%p.log "
                                        + "-Djava.io.tmpdir={{hbase_tmp_dir}}\"\n"
                                        + "export SERVER_GC_OPTS=\"-verbose:gc -Xlog:gc:{{hbase_log_dir}}/"
                                        + "gc.log-`date +'%Y%m%d%H%M'`\"\n"
                                        + "{% endif %}\n\n"
                                        + "# Uncomment and adjust to enable JMX exporting\n"
                                        + "# export HBASE_JMX_BASE=\"-Dcom.sun.management.jmxremote.ssl=false "
                                        + "-Dcom.sun.management.jmxremote.authenticate=false\"\n\n"
                                        + "{% if java_version <= 8 %}\n"
                                        + "export HBASE_MASTER_OPTS=\" -XX:PermSize=64m -XX:MaxPermSize={{hbase_master_maxperm_size}} "
                                        + "-Xms{{hbase_heapsize}} -Xmx{{hbase_heapsize}} -Xmn{{hbase_master_xmn_size}} "
                                        + "-XX:CMSInitiatingOccupancyFraction=70 -XX:+UseCMSInitiatingOccupancyOnly\"\n"
                                        + "export HBASE_REGIONSERVER_OPTS=\"-XX:MaxPermSize=128m -Xmn{{regionserver_xmn_size}} "
                                        + "-XX:CMSInitiatingOccupancyFraction=70 -XX:+UseCMSInitiatingOccupancyOnly "
                                        + "-Xms{{regionserver_heapsize}} -Xmx{{regionserver_heapsize}}\"\n"
                                        + "{% else %}\n"
                                        + "export HBASE_MASTER_OPTS=\" -Xms{{hbase_heapsize}} -Xmx{{hbase_heapsize}} "
                                        + "-Xmn{{hbase_master_xmn_size}}\"\n"
                                        + "export HBASE_REGIONSERVER_OPTS=\" -Xmn{{regionserver_xmn_size}} "
                                        + "-Xms{{regionserver_heapsize}} -Xmx{{regionserver_heapsize}}\"\n"
                                        + "{% endif %}\n\n"
                                        + "# File naming hosts on which HRegionServers will run.\n"
                                        + "export HBASE_REGIONSERVERS=${HBASE_CONF_DIR}/regionservers\n\n"
                                        + "# Where log files are stored.\n"
                                        + "export HBASE_LOG_DIR={{hbase_log_dir}}\n\n"
                                        + "# The directory where pid files are stored.\n"
                                        + "export HBASE_PID_DIR={{hbase_pid_dir}}\n\n"
                                        + "# Tell HBase whether it should manage its own instance of Zookeeper or not.\n"
                                        + "export HBASE_MANAGES_ZK=false\n\n"
                                        + "{% if security_enabled %}\n"
                                        + "export HBASE_OPTS=\"$HBASE_OPTS -Djava.security.auth.login.config={{client_jaas_config_file}}\"\n"
                                        + "export HBASE_MASTER_OPTS=\"$HBASE_MASTER_OPTS "
                                        + "-Djava.security.auth.login.config={{master_jaas_config_file}} "
                                        + "-Djavax.security.auth.useSubjectCredsOnly=false\"\n"
                                        + "export HBASE_REGIONSERVER_OPTS=\"$HBASE_REGIONSERVER_OPTS "
                                        + "-Djava.security.auth.login.config={{regionserver_jaas_config_file}} "
                                        + "-Djavax.security.auth.useSubjectCredsOnly=false\"\n"
                                        + "export HBASE_ZOOKEEPER_OPTS=\"$HBASE_ZOOKEEPER_OPTS "
                                        + "-Djava.security.auth.login.config={{ams_zookeeper_jaas_config_file}}\"\n"
                                        + "{% endif %}\n\n"
                                        + "_HADOOP_NATIVE_LIB=\"/usr/lib/ams-hbase/lib/hadoop-native/\"\n"
                                        + "export HBASE_OPTS=\"$HBASE_OPTS -Djava.library.path=${_HADOOP_NATIVE_LIB}\"\n"
                                        + "export HADOOP_HOME={{ams_hbase_home_dir}}\n"
                                        + "export HBASE_HOME={{ams_hbase_home_dir}}\n";


                        newAMSEnvProperties.put("content", amsEnvConfig);
                        newAMSEnvHBaseProperties.put("content",amsHBaseEnv);
                        updateConfigurationPropertiesForCluster(cluster, AMS_ENV_CONFIG, newAMSEnvProperties, true, false);
                        updateConfigurationPropertiesForCluster(cluster, AMS_HBASE_ENV_CONFIG, newAMSEnvHBaseProperties, true, false);


                    }
                }
            }
        }


    protected void removeAmbariInfraSolrEnvJDK8Options() throws AmbariException {
        AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
        Clusters clusters = ambariManagementController.getClusters();
        if (clusters != null) {
            Map<String, Cluster> clusterMap = getCheckedClusterMap(clusters);
            if (clusterMap != null && !clusterMap.isEmpty()) {
                for (final Cluster cluster : clusterMap.values()) {
                    Set<String> installedServices = cluster.getServices().keySet();

                    // Technically, ambari env as part of the stack should be based in java_home which is made for stack JDK version
                    // and its properties should be updated during cluster upgrade
                    // because Ambari Metrics is part of Apache Ambari, we need to update its env to support JDK 17 prior to stack upgrade
                    // which can be done during schema upgrade during ambari-server upgrade command.
                    Map<String, String> newInfraSolrEnv = new HashMap<>();
                    String infraSolrEnvGCTune = "-XX:NewRatio=3 -XX:SurvivorRatio=4 -XX:TargetSurvivorRatio=90 -XX:MaxTenuringThreshold=8";
                    String infraSolrEnvGCLogOPTS = "";
                    String infraSolrEnvContent =
                            "#!/bin/bash\n" +
                            "# Licensed to the Apache Software Foundation (ASF) under one or more\n" +
                            "# contributor license agreements. See the NOTICE file distributed with\n" +
                            "# this work for additional information regarding copyright ownership.\n" +
                            "# The ASF licenses this file to You under the Apache License, Version 2.0\n" +
                            "# (the \"License\"); you may not use this file except in compliance with\n" +
                            "# the License. You may obtain a copy of the License at\n" +
                            "#\n" +
                            "# http://www.apache.org/licenses/LICENSE-2.0\n" +
                            "#\n" +
                            "# Unless required by applicable law or agreed to in writing, software\n" +
                            "# distributed under the License is distributed on an \"AS IS\" BASIS,\n" +
                            "# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
                            "# See the License for the specific language governing permissions and\n" +
                            "# limitations under the License.\n" +
                            "\n" +
                            "# By default the script will use JAVA_HOME to determine which java\n" +
                            "# to use, but you can set a specific path for Solr to use without\n" +
                            "# affecting other Java applications on your server/workstation.\n" +
                            "SOLR_JAVA_HOME={{java64_home}}\n" +
                            "\n" +
                            "# Increase Java Min/Max Heap as needed to support your indexing / query needs\n" +
                            "SOLR_JAVA_MEM=\"-Xms{{infra_solr_min_mem}}m -Xmx{{infra_solr_max_mem}}m\"\n" +
                            "\n" +
                            "SOLR_JAVA_STACK_SIZE=\"-Xss{{infra_solr_java_stack_size}}m\"\n" +
                            "\n" +
                            "GC_LOG_OPTS=\"{{infra_solr_gc_log_opts}}\"\n" +
                            "\n" +
                            "GC_TUNE=\"{{infra_solr_gc_tune}}\"\n" +
                            "\n" +
                            "# Set the ZooKeeper connection string if using an external ZooKeeper ensemble\n" +
                            "# e.g. host1:2181,host2:2181/chroot\n" +
                            "# Leave empty if not using SolrCloud\n" +
                            "ZK_HOST=\"{{zookeeper_quorum}}{{infra_solr_znode}}\"\n" +
                            "\n" +
                            "# Set the ZooKeeper client timeout (for SolrCloud mode)\n" +
                            "ZK_CLIENT_TIMEOUT=\"60000\"\n" +
                            "\n" +
                            "# By default the start script uses \"localhost\"; override the hostname here\n" +
                            "# for production SolrCloud environments to control the hostname exposed to " +
                            "cluster state\n" +
                            "SOLR_HOST=`hostname -f`\n" +
                            "\n" +
                            "# By default the start script uses UTC; override the timezone if needed\n" +
                            "#SOLR_TIMEZONE=\"UTC\"\n" +
                            "\n" +
                            "# Set to true to activate the JMX RMI connector to allow remote JMX client " +
                            "applications\n" +
                            "# to monitor the JVM hosting Solr; set to \"false\" to disable that behavior\n" +
                            "# (false is recommended in production environments)\n" +
                            "ENABLE_REMOTE_JMX_OPTS=\"{{infra_solr_jmx_enabled}}\"\n" +
                            "\n" +
                            "# The script will use SOLR_PORT+10000 for the RMI_PORT or you can set it here\n" +
                            "RMI_PORT={{infra_solr_jmx_port}}\n" +
                            "\n" +
                            "# Anything you add to the SOLR_OPTS variable will be included in the java\n" +
                            "# start command line as-is, in ADDITION to other options. If you specify the\n" +
                            "# -a option on start script, those options will be appended as well. Examples:\n" +
                            "#SOLR_OPTS=\"$SOLR_OPTS -Dsolr.autoSoftCommit.maxTime=3000\"\n" +
                            "#SOLR_OPTS=\"$SOLR_OPTS -Dsolr.autoCommit.maxTime=60000\"\n" +
                            "#SOLR_OPTS=\"$SOLR_OPTS -Dsolr.clustering.enabled=true\"\n" +
                            "SOLR_OPTS=\"$SOLR_OPTS -Djava.rmi.server.hostname={{hostname}}\"\n" +
                            "{% if infra_solr_extra_java_opts -%}\n" +
                            "SOLR_OPTS=\"$SOLR_OPTS {{infra_solr_extra_java_opts}}\"\n" +
                            "{% endif %}\n" +
                            "\n" +
                            "# Location where the bin/solr script will save PID files for running instances\n" +
                            "# If not set, the script will create PID files in $SOLR_TIP/bin\n" +
                            "SOLR_PID_DIR={{infra_solr_piddir}}\n" +
                            "\n" +
                            "# Path to a directory where Solr creates index files, the specified directory\n" +
                            "# must contain a solr.xml; by default, Solr will use server/solr\n" +
                            "SOLR_HOME={{infra_solr_datadir}}\n" +
                            "\n" +
                            "# Solr provides a default Log4J configuration properties file in server/resources\n" +
                            "# however, you may want to customize the log settings and file appender location\n" +
                            "# so you can point the script to use a different log4j.properties file\n" +
                            "LOG4J_PROPS={{infra_solr_conf}}/log4j2.xml\n" +
                            "\n" +
                            "# Location where Solr should write logs to; should agree with the file appender\n" +
                            "# settings in server/resources/log4j.properties\n" +
                            "SOLR_LOGS_DIR={{infra_solr_log_dir}}\n" +
                            "\n" +
                            "# Sets the port Solr binds to, default is 8983\n" +
                            "SOLR_PORT={{infra_solr_port}}\n" +
                            "\n" +
                            "# Be sure to update the paths to the correct keystore for your environment\n" +
                            "{% if infra_solr_ssl_enabled %}\n" +
                            "SOLR_SSL_KEY_STORE={{infra_solr_keystore_location}}\n" +
                            "SOLR_SSL_KEY_STORE_PASSWORD={{infra_solr_keystore_password}}\n" +
                            "SOLR_SSL_TRUST_STORE={{infra_solr_truststore_location}}\n" +
                            "SOLR_SSL_TRUST_STORE_PASSWORD={{infra_solr_truststore_password}}\n" +
                            "SOLR_SSL_NEED_CLIENT_AUTH=false\n" +
                            "SOLR_SSL_WANT_CLIENT_AUTH=false\n" +
                            "{% endif %}\n" +
                            "\n" +
                            "# Uncomment to set a specific SSL port (-Djetty.ssl.port=N); if not set\n" +
                            "# and you are using SSL, then the start script will use SOLR_PORT for the SSL port\n" +
                            "#SOLR_SSL_PORT=\n" +
                            "\n" +
                            "{% if security_enabled -%}\n" +
                            "SOLR_JAAS_FILE={{infra_solr_jaas_file}}\n" +
                            "SOLR_KERB_KEYTAB={{infra_solr_web_kerberos_keytab}}\n" +
                            "SOLR_KERB_PRINCIPAL={{infra_solr_web_kerberos_principal}}\n" +
                            "SOLR_OPTS=\"$SOLR_OPTS -Dsolr.hdfs.security.kerberos.principal=" +
                            "{{infra_solr_kerberos_principal}}\"\n" +
                            "SOLR_OPTS=\"$SOLR_OPTS {{zk_security_opts}}\"\n" +
                            "\n" +
                            "SOLR_AUTH_TYPE=\"kerberos\"\n" +
                            "SOLR_AUTHENTICATION_OPTS=\" -DauthenticationPlugin=org.apache.solr.security." +
                            "KerberosPlugin -Djava.security.auth.login.config=$SOLR_JAAS_FILE -Dsolr.kerberos." +
                            "principal=${SOLR_KERB_PRINCIPAL} -Dsolr.kerberos.keytab=${SOLR_KERB_KEYTAB} " +
                            "-Dsolr.kerberos.cookie.domain=${SOLR_HOST}\"\n" +
                            "{% endif %}\n" +
                            "\n" +
                            "{% if java_version == 8 %}\n" +
                            "export GC_TUNE=\"$GC_TUNE -XX:+UseConcMarkSweepGC -XX:+UseParNewGC " +
                            "-XX:ConcGCThreads=4 -XX:ParallelGCThreads=4 -XX:+CMSScavengeBeforeRemark " +
                            "-XX:PretenureSizeThreshold=64m -XX:+UseCMSInitiatingOccupancyOnly " +
                            "-XX:CMSInitiatingOccupancyFraction=50 -XX:CMSMaxAbortablePrecleanTime=6000 " +
                            "-XX:+CMSParallelRemarkEnabled -XX:+ParallelRefProcEnabled\"\n" +
                            "export GC_LOG_OPTS=\"$GC_LOG_OPTS  -verbose:gc -XX:+PrintHeapAtGC " +
                            "-XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintGCTimeStamps " +
                            "-XX:+PrintTenuringDistribution -XX:+PrintGCApplicationStoppedTime " +
                            "-XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=15 -XX:GCLogFileSize=200M " +
                            "-Xloggc:{{infra_solr_log_dir}}/solr_gc.log\"\n" +
                            "{% elif java_version == 11 %}\n" +
                            "export GC_TUNE=\"$GC_TUNE -XX:ParallelGCThreads=4 -XX:+ParallelRefProcEnabled\"\n" +
                            "export GC_LOG_OPTS=\"$GC_LOG_OPTS -Xlog:gc*,gc+heap=info,gc+age=info:file=" +
                            "{{infra_solr_log_dir}}/gc.log:time,uptime,level,tags:filecount=15,filesize=200M\"\n" +
                            "{% else %}\n" +
                            "export GC_TUNE=\"$GC_TUNE -XX:ParallelGCThreads=4 -XX:+ParallelRefProcEnabled\"\n" +
                            "export GC_LOG_OPTS=\"$GC_LOG_OPTS -Xlog:gc*,gc+heap=info,gc+age=info:file=" +
                            "{{infra_solr_log_dir}}/gc.log:time,uptime,level,tags:filecount=15,filesize=200M\"\n" +
                            "{% endif %}";
                    newInfraSolrEnv.put(INFRA_SOLR_ENV_GC_TUNE, infraSolrEnvGCTune);
                    newInfraSolrEnv.put(INFRA_SOLR_ENV_GC_LOG_OPTS, infraSolrEnvGCLogOPTS);
                    newInfraSolrEnv.put(INFRA_SOLR_ENV_CONTENT, infraSolrEnvContent);
                    updateConfigurationPropertiesForCluster(cluster, INFRA_SOLR_ENV_CONFIG, newInfraSolrEnv, true, false);



                }
            }
        }
    }
}
