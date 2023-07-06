/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ambari.logfeeder.common;

public class LogFeederConstants {

  public static final String ALL = "all";
  public static final String LOGFEEDER_FILTER_NAME = "log_feeder_config";
  public static final String LOG_LEVEL_UNKNOWN = "UNKNOWN";

  public static final String CLOUD_PREFIX = "cl-";

  // solr fields
  public static final String SOLR_LEVEL = "level";
  public static final String SOLR_COMPONENT = "type";
  public static final String SOLR_HOST = "host";

  public static final String IN_MEMORY_TIMESTAMP = "in_memory_timestamp";

  public static final String LOGFEEDER_PROPERTIES_FILE = "logfeeder.properties";
  public static final String CLUSTER_NAME_PROPERTY = "cluster.name";
  public static final String TMP_DIR_PROPERTY = "logfeeder.tmp.dir";

  public static final String METRICS_COLLECTOR_PROTOCOL_PROPERTY = "logfeeder.metrics.collector.protocol";
  public static final String METRICS_COLLECTOR_PORT_PROPERTY = "logfeeder.metrics.collector.port";
  public static final String METRICS_COLLECTOR_HOSTS_PROPERTY = "logfeeder.metrics.collector.hosts";
  public static final String METRICS_COLLECTOR_PATH_PROPERTY = "logfeeder.metrics.collector.path";

  public static final String LOG_FILTER_ENABLE_PROPERTY = "logfeeder.log.filter.enable";
  public static final String INCLUDE_DEFAULT_LEVEL_PROPERTY = "logfeeder.include.default.level";
  public static final String SOLR_IMPLICIT_ROUTING_PROPERTY = "logfeeder.solr.implicit.routing";

  public static final String CONFIG_DIR_PROPERTY = "logfeeder.config.dir";
  public static final String CONFIG_FILES_PROPERTY = "logfeeder.config.files";

  public static final String SIMULATE_INPUT_NUMBER_PROPERTY = "logfeeder.simulate.input_number";
  public static final int DEFAULT_SIMULATE_INPUT_NUMBER = 0;
  public static final String SIMULATE_LOG_LEVEL_PROPERTY = "logfeeder.simulate.log_level";
  public static final String DEFAULT_SIMULATE_LOG_LEVEL = "WARN";
  public static final String SIMULATE_NUMBER_OF_WORDS_PROPERTY = "logfeeder.simulate.number_of_words";
  public static final int DEFAULT_SIMULATE_NUMBER_OF_WORDS = 1000;
  public static final String SIMULATE_MIN_LOG_WORDS_PROPERTY = "logfeeder.simulate.min_log_words";
  public static final int DEFAULT_SIMULATE_MIN_LOG_WORDS = 5;
  public static final String SIMULATE_MAX_LOG_WORDS_PROPERTY = "logfeeder.simulate.max_log_words";
  public static final int DEFAULT_SIMULATE_MAX_LOG_WORDS = 5;
  public static final String SIMULATE_SLEEP_MILLISECONDS_PROPERTY = "logfeeder.simulate.sleep_milliseconds";
  public static final int DEFAULT_SIMULATE_SLEEP_MILLISECONDS = 10000;
  public static final String SIMULATE_LOG_IDS_PROPERTY = "logfeeder.simulate.log_ids";

  public static final String SOLR_KERBEROS_ENABLE_PROPERTY = "logfeeder.solr.kerberos.enable";
  public static final boolean DEFAULT_SOLR_KERBEROS_ENABLE = false;
  public static final String DEFAULT_SOLR_JAAS_FILE = "/etc/security/keytabs/logsearch_solr.service.keytab";
  public static final String SOLR_JAAS_FILE_PROPERTY = "logfeeder.solr.jaas.file";

  public static final String CACHE_ENABLED_PROPERTY = "logfeeder.cache.enabled";
  public static final boolean DEFAULT_CACHE_ENABLED = false;
  public static final String CACHE_KEY_FIELD_PROPERTY = "logfeeder.cache.key.field";
  public static final String DEFAULT_CACHE_KEY_FIELD = "log_message";
  public static final String CACHE_SIZE_PROPERTY = "logfeeder.cache.size";
  public static final int DEFAULT_CACHE_SIZE = 100;
  public static final String CACHE_LAST_DEDUP_ENABLED_PROPERTY = "logfeeder.cache.last.dedup.enabled";
  public static final boolean DEFAULT_CACHE_LAST_DEDUP_ENABLED = false;
  public static final String CACHE_DEDUP_INTERVAL_PROPERTY = "logfeeder.cache.dedup.interval";
  public static final long DEFAULT_CACHE_DEDUP_INTERVAL = 1000;

  public static final String CHECKPOINT_FOLDER_PROPERTY = "logfeeder.checkpoint.folder";
  public static final String CHECKPOINT_EXTENSION_PROPERTY = "logfeeder.checkpoint.extension";
  public static final String DEFAULT_CHECKPOINT_EXTENSION = ".cp";

  public static final String DOCKER_CONTAINER_REGISTRY_ENABLED_PROPERTY = "logfeeder.docker.registry.enabled";
  public static final boolean DOCKER_CONTAINER_REGISTRY_ENABLED_DEFAULT = false;

  public static final String USE_LOCAL_CONFIGS_PROPERTY = "logfeeder.configs.local.enabled";
  public static final boolean USE_LOCAL_CONFIGS_DEFAULT = false;

  public static final String USE_SOLR_FILTER_STORAGE_PROPERTY = "logfeeder.configs.filter.solr.enabled";
  public static final boolean USE_SOLR_FILTER_STORAGE_DEFAULT = false;

  public static final String USE_ZK_FILTER_STORAGE_PROPERTY = "logfeeder.configs.filter.zk.enabled";
  public static final boolean USE_ZK_FILTER_STORAGE_DEFAULT = false;

  public static final String MONITOR_SOLR_FILTER_STORAGE_PROPERTY = "logfeeder.configs.filter.solr.monitor.enabled";
  public static final boolean MONITOR_SOLR_FILTER_STORAGE_DEFAULT = true;

  public static final String MONITOR_SOLR_FILTER_INTERVAL_PROPERTY = "logfeeder.configs.filter.solr.monitor.interval";

  public static final String SOLR_ZK_CONNECTION_STRING = "logfeeder.solr.zk_connect_string";
  public static final String SOLR_URLS = "logfeeder.solr.urls";
  public static final String SOLR_CLOUD_DISCOVER = "logfeeder.solr.cloud.client.discover";
  public static final String SOLR_METADATA_COLLECTION = "logfeeder.solr.metadata.collection";

  public static final String CLOUD_STORAGE_MODE = "logfeeder.cloud.storage.mode";
  public static final String CLOUD_STORAGE_DESTINATION = "logfeeder.cloud.storage.destination";
  public static final String CLOUD_STORAGE_UPLOAD_ON_SHUTDOWN = "logfeeder.cloud.storage.upload.on.shutdown";
  public static final String CLOUD_STORAGE_UPLOADER_TIMEOUT_MINUTUES = "logfeeder.cloud.storage.uploader.timeout.minutes";
  public static final String CLOUD_STORAGE_UPLOADER_INTERVAL_SECONDS = "logfeeder.cloud.storage.uploader.interval.seconds";
  public static final String CLOUD_STORAGE_BUCKET = "logfeeder.cloud.storage.bucket";
  public static final String CLOUD_STORAGE_BUCKET_BOOTSTRAP = "logfeeder.cloud.storage.bucket.bootstrap";
  public static final String CLOUD_STORAGE_USE_HDFS_CLIENT = "logfeeder.cloud.storage.use.hdfs.client";
  public static final String CLOUD_STORAGE_USE_FILTERS = "logfeeder.cloud.storage.use.filters";
  public static final String CLOUD_STORAGE_CUSTOM_FS = "logfeeder.cloud.storage.custom.fs";
  public static final String CLOUD_STORAGE_BASE_PATH = "logfeeder.cloud.storage.base.path";

  public static final String CLOUD_ROLLOVER_ARCHIVE_LOCATION = "logfeeder.cloud.rollover.archive.base.dir";
  public static final String CLOUD_ROLLOVER_THRESHOLD_TIME_MIN = "logfeeder.cloud.rollover.threshold.min";
  public static final String CLOUD_ROLLOVER_THRESHOLD_TIME_SIZE = "logfeeder.cloud.rollover.threshold.size";
  public static final String CLOUD_ROLLOVER_MAX_BACKUP_FILES = "logfeeder.cloud.rollover.max.files";
  public static final String CLOUD_ROLLOVER_THRESHOLD_TIME_SIZE_UNIT = "logfeeder.cloud.rollover.threshold.size.unit";
  public static final String CLOUD_ROLLOVER_USE_GZIP = "logfeeder.cloud.rollover.use.gzip";
  public static final String CLOUD_ROLLOVER_IMMEDIATE_FLUSH = "logfeeder.cloud.rollover.immediate.flush";
  public static final String CLOUD_ROLLOVER_ON_SHUTDOWN = "logfeeder.cloud.rollover.on.shutdown";
  public static final String CLOUD_ROLLOVER_ON_STARTUP = "logfeeder.cloud.rollover.on.startup";

  public static final String HDFS_USER = "logfeeder.hdfs.user";

  public static final String HDFS_HOST = "logfeeder.hdfs.host";
  public static final String HDFS_PORT = "logfeeder.hdfs.port";
  public static final String HDFS_FILE_PERMISSIONS = "logfeeder.hdfs.file.permissions";
  public static final String HDFS_KERBEROS = "logfeeder.hdfs.kerberos";
  public static final String HDFS_KERBEROS_KEYTAB = "logfeeder.hdfs.keytab";
  public static final String HDFS_KERBEROS_PRINCIPAL = "logfeeder.hdfs.principal";

  public static final String S3_ENDPOINT = "logfeeder.s3.endpoint";
  public static final String S3_ENDPOINT_DEFAULT = "https://s3.amazonaws.com";
  public static final String S3_REGION = "logfeeder.s3.region";
  public static final String S3_OBJECT_ACL = "logfeeder.s3.object.acl";
  public static final String S3_PATH_STYLE_ACCESS = "logfeeder.s3.path.style.access";
  public static final String S3_MULTIOBJECT_DELETE_ENABLE = "logfeeder.s3.multiobject.delete.enable";
  public static final String S3_SECRET_KEY = "logfeeder.s3.secret.key";
  public static final String S3_ACCESS_KEY = "logfeeder.s3.access.key";
  public static final String S3_SECRET_KEY_FILE = "logfeeder.s3.secret.key.file";
  public static final String S3_ACCESS_KEY_FILE = "logfeeder.s3.access.key.file";
  public static final String S3_USE_FILE = "logfeeder.s3.credentials.file.enabled";
  public static final String S3_USE_HADOOP_CREDENTIAL_PROVIDER = "logfeeder.s3.credentials.hadoop.enabled";
  public static final String S3_HADOOP_CREDENTIAL_SECRET_REF = "logfeeder.s3.credentials.hadoop.secret.ref";
  public static final String S3_HADOOP_CREDENTIAL_ACCESS_REF = "logfeeder.s3.credentials.hadoop.access.ref";

}
