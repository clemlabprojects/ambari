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
    @Override
    protected void executeDDLUpdates() throws AmbariException, SQLException {
    }

    @Override
    protected void executePreDMLUpdates() throws AmbariException, SQLException {
    }

    @Override
    protected void executeDMLUpdates() throws AmbariException, SQLException {
        removeAmbariMetricsEnvJDK8Options();
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
                            String amvEnvConfig = "\n# Set environment variables here.\n\n"
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
                                + "export AMS_COLLECTOR_OPTS=\"-Djava.library.path=/usr/lib/ams-hbase/lib/hadoop-native\"\n"
                                + "{% if security_enabled %}\n"
                                + "export AMS_COLLECTOR_OPTS=\"$AMS_COLLECTOR_OPTS -Djava.security.auth.login.config="
                                + "{{ams_collector_jaas_config_file}}\"\n"
                                + "{% endif %}\n\n"
                                + "# AMS Collector GC options\n"
                                + "export AMS_COLLECTOR_GC_OPTS=\"-XX:+UseConcMarkSweepGC -verbose:gc -XX:+PrintGCDetails "
                                + "-XX:+PrintGCDateStamps -Xloggc:{{ams_collector_log_dir}}/collector-gc.log-`date +'%Y%m%d%H%M'`\"\n"
                                + "export AMS_COLLECTOR_OPTS=\"$AMS_COLLECTOR_OPTS $AMS_COLLECTOR_GC_OPTS\"\n\n"
                                + "# Metrics collector host will be blacklisted for specified number of seconds if metric monitor "
                                + "failed to connect to it.\n"
                                + "export AMS_FAILOVER_STRATEGY_BLACKLISTED_INTERVAL={{failover_strategy_blacklisted_interval}}\n\n"
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


                        newAMSEnvProperties.put("content", amvEnvConfig);
                        newAMSEnvHBaseProperties.put("content",amsHBaseEnv);
                        updateConfigurationPropertiesForCluster(cluster, AMS_ENV_CONFIG, newAMSEnvProperties, true, false);
                        updateConfigurationPropertiesForCluster(cluster, AMS_HBASE_ENV_CONFIG, newAMSEnvHBaseProperties, true, false);


                    }
                }
            }
        }


}
