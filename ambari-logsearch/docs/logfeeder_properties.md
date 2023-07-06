<!---
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->
## Log Feeder Configurations

| `Name` | `Description` | `Default` | `Examples` |
|---|---|---|---|
|`cluster.name`|The name of the cluster the Log Feeder program runs in.|`EMPTY`|<ul><li>`cl1`</li></ul>|
|`hadoop.security.credential.provider.path`|The jceks file that provides passwords.|`EMPTY`|<ul><li>`jceks://file/etc/ambari-logsearch-logfeeder/conf/logfeeder.jceks`</li></ul>|
|`logfeeder.cache.dedup.interval`|Maximum number of milliseconds between two identical messages to be filtered out.|1000|<ul><li>`500`</li></ul>|
|`logfeeder.cache.enabled`|Enables the usage of a cache to avoid duplications.|false|<ul><li>`true`</li></ul>|
|`logfeeder.cache.key.field`|The field which's value should be cached and should be checked for repetitions.|log_message|<ul><li>`some_field_prone_to_repeating_value`</li></ul>|
|`logfeeder.cache.last.dedup.enabled`|Enable filtering directly repeating log entries irrelevant of the time spent between them.|false|<ul><li>`true`</li></ul>|
|`logfeeder.cache.size`|The number of log entries to cache in order to avoid duplications.|100|<ul><li>`50`</li></ul>|
|`logfeeder.checkpoint.extension`|The extension used for checkpoint files.|.cp|<ul><li>`ckp`</li></ul>|
|`logfeeder.checkpoint.folder`|The folder where checkpoint files are stored.|`EMPTY`|<ul><li>`/usr/lib/ambari-logsearch-logfeeder/conf/checkpoints`</li></ul>|
|`logfeeder.cloud.rollover.archive.base.dir`|Location where the active and archives logs will be stored. Beware, it could require a large amount of space, use mounted disks if it is possible.|/tmp|<ul><li>`/var/lib/ambari-logsearch-logfeeder/data`</li></ul>|
|`logfeeder.cloud.rollover.immediate.flush`|Immediately flush temporal cloud logs (to active location).|true|<ul><li>`false`</li></ul>|
|`logfeeder.cloud.rollover.max.files`|The number of max backup log files for rolled over logs.|10|<ul><li>`50`</li></ul>|
|`logfeeder.cloud.rollover.on.shutdown`|Rollover temporal cloud log files on shutdown|true|<ul><li>`false`</li></ul>|
|`logfeeder.cloud.rollover.on.startup`|Rollover temporal cloud log files on startup|true|<ul><li>`false`</li></ul>|
|`logfeeder.cloud.rollover.threshold.min`|Rollover cloud log files after an interval (minutes)|60|<ul><li>`1`</li></ul>|
|`logfeeder.cloud.rollover.threshold.size`|Rollover cloud log files after the log file size reach this limit|80|<ul><li>`1024`</li></ul>|
|`logfeeder.cloud.rollover.threshold.size.unit`|Rollover cloud log file size unit (e.g: KB, MB etc.)|MB|<ul><li>`KB`</li></ul>|
|`logfeeder.cloud.rollover.use.gzip`|Use GZip on archived logs.|true|<ul><li>`false`</li></ul>|
|`logfeeder.cloud.storage.base.path`|Base path prefix for storing logs (cloud storage / hdfs), could be an absolute path or URI. (if URI used, that will override the default.FS with HDFS client)|/apps/logsearch|<ul><li>`/user/logsearch/mypath`</li><li>`s3a:///user/logsearch`</li></ul>|
|`logfeeder.cloud.storage.bucket`|Amazon S3 bucket.|logfeeder|<ul><li>`logs`</li></ul>|
|`logfeeder.cloud.storage.bucket.bootstrap`|Create bucket on startup.|true|<ul><li>`false`</li></ul>|
|`logfeeder.cloud.storage.custom.fs`|If it is not empty, override fs.defaultFS for HDFS client. Can be useful to write data to a different bucket (from other services) if the bucket address is read from core-site.xml|`EMPTY`|<ul><li>`s3a://anotherbucket`</li></ul>|
|`logfeeder.cloud.storage.destination`|Type of storage that is the destination for cloud output logs.|none|<ul><li>`hdfs`</li><li>`s3`</li></ul>|
|`logfeeder.cloud.storage.mode`|Option to support sending logs to cloud storage. You can choose between supporting only cloud storage, non-cloud storage or both|default|<ul><li>`default`</li><li>`cloud`</li><li>`hybrid`</li></ul>|
|`logfeeder.cloud.storage.upload.on.shutdown`|Try to upload archived files on shutdown|false|<ul><li>`true`</li></ul>|
|`logfeeder.cloud.storage.uploader.interval.seconds`|Second interval, that is used to check against there are any files to upload to cloud storage or not.|60|<ul><li>`10`</li></ul>|
|`logfeeder.cloud.storage.uploader.timeout.minutes`|Timeout value for uploading task to cloud storage in minutes.|60|<ul><li>`10`</li></ul>|
|`logfeeder.cloud.storage.use.filters`|Use filters for inputs (with filters the output format will be JSON)|false|<ul><li>`true`</li></ul>|
|`logfeeder.cloud.storage.use.hdfs.client`|Use hdfs client with cloud connectors instead of the core clients for shipping data to cloud storage|false|<ul><li>`true`</li></ul>|
|`logfeeder.config.dir`|The directory where shipper configuration files are looked for.|/usr/lib/ambari-logsearch-logfeeder/conf|<ul><li>`/usr/lib/ambari-logsearch-logfeeder/conf`</li></ul>|
|`logfeeder.config.files`|Comma separated list of the config files containing global / output configurations.|`EMPTY`|<ul><li>`global.json,output.json`</li><li>`/usr/lib/ambari-logsearch-logfeeder/conf/global.config.json`</li></ul>|
|`logfeeder.configs.filter.solr.enabled`|Use solr as a log level filter storage|false|<ul><li>`true`</li></ul>|
|`logfeeder.configs.filter.solr.monitor.enabled`|Monitor log level filters (in solr) periodically - used for checking updates.|true|<ul><li>`false`</li></ul>|
|`logfeeder.configs.filter.solr.monitor.interval`|Time interval (in seconds) between monitoring input config filter definitions from Solr.|30|<ul><li>`60`</li></ul>|
|`logfeeder.configs.filter.zk.enabled`|Use zk as a log level filter storage (works only with local config)|false|<ul><li>`true`</li></ul>|
|`logfeeder.configs.local.enabled`|Monitor local input.config-*.json files (do not upload them to zookeeper or solr)|false|<ul><li>`true`</li></ul>|
|`logfeeder.docker.registry.enabled`|Enable to monitor docker containers and store their metadata in an in-memory registry.|false|<ul><li>`true`</li></ul>|
|`logfeeder.hdfs.file.permissions`|Default permissions for created files on HDFS|640|<ul><li>`600`</li></ul>|
|`logfeeder.hdfs.host`|HDFS Name Node host.|`EMPTY`|<ul><li>`mynamenodehost`</li></ul>|
|`logfeeder.hdfs.kerberos`|Enable kerberos support for HDFS|false|<ul><li>`true`</li></ul>|
|`logfeeder.hdfs.keytab`|Kerberos keytab location for Log Feeder for communicating with secure HDFS. |/etc/security/keytabs/logfeeder.service.keytab|<ul><li>`/etc/security/keytabs/mykeytab.keytab`</li></ul>|
|`logfeeder.hdfs.port`|HDFS Name Node port|`EMPTY`|<ul><li>`9000`</li></ul>|
|`logfeeder.hdfs.principal`|Kerberos principal for Log Feeder for communicating with secure HDFS. |logfeeder/_HOST|<ul><li>`mylogfeeder/myhost1@EXAMPLE.COM`</li></ul>|
|`logfeeder.hdfs.user`|Overrides HADOOP_USER_NAME variable at runtime|`EMPTY`|<ul><li>`hdfs`</li></ul>|
|`logfeeder.include.default.level`|Comma separated list of the default log levels to be enabled by the filtering.|`EMPTY`|<ul><li>`FATAL,ERROR,WARN`</li></ul>|
|`logfeeder.log.filter.enable`|Enables the filtering of the log entries by log level filters.|false|<ul><li>`true`</li></ul>|
|`logfeeder.metrics.collector.hosts`|Comma separtaed list of metric collector hosts.|`EMPTY`|<ul><li>`c6401.ambari.apache.org,c6402.ambari.apache.org`</li></ul>|
|`logfeeder.metrics.collector.path`|The path used by metric collectors.|`EMPTY`|<ul><li>`/ws/v1/timeline/metrics`</li></ul>|
|`logfeeder.metrics.collector.port`|The port used by metric collectors.|`EMPTY`|<ul><li>`6188`</li></ul>|
|`logfeeder.metrics.collector.protocol`|The protocol used by metric collectors.|`EMPTY`|<ul><li>`http`</li><li>`https`</li></ul>|
|`logfeeder.s3.access.key`|Amazon S3 secret access key.|`EMPTY`|<ul><li>`MySecretAccessKey`</li></ul>|
|`logfeeder.s3.access.key.file`|Amazon S3 secret access key file (that contains only the key).|`EMPTY`|<ul><li>`/my/path/access_key`</li></ul>|
|`logfeeder.s3.credentials.file.enabled`|Enable to get Amazon S3 secret/access keys from files.|`EMPTY`|<ul><li>`true`</li></ul>|
|`logfeeder.s3.credentials.hadoop.access.ref`|Amazon S3 access key reference in Hadoop credential store..|logfeeder.s3.access.key|<ul><li>`logfeeder.s3.access.key`</li></ul>|
|`logfeeder.s3.credentials.hadoop.enabled`|Enable to get Amazon S3 secret/access keys from Hadoop credential store API.|`EMPTY`|<ul><li>`true`</li></ul>|
|`logfeeder.s3.credentials.hadoop.secret.ref`|Amazon S3 secret access key reference in Hadoop credential store..|logfeeder.s3.secret.key|<ul><li>`logfeeder.s3.secret.key`</li></ul>|
|`logfeeder.s3.endpoint`|Amazon S3 endpoint.|https://s3.amazonaws.com|<ul><li>`https://s3.amazonaws.com`</li></ul>|
|`logfeeder.s3.multiobject.delete.enable`|When enabled, multiple single-object delete requests are replaced by a single 'delete multiple objects'-request, reducing the number of requests.|true|<ul><li>`false`</li></ul>|
|`logfeeder.s3.object.acl`|Amazon S3 ACLs for new objects.|private|<ul><li>`logs`</li></ul>|
|`logfeeder.s3.path.style.access`|Enable S3 path style access will disable the default virtual hosting behaviour (DNS).|false|<ul><li>`true`</li></ul>|
|`logfeeder.s3.region`|Amazon S3 region.|`EMPTY`|<ul><li>`us-east-2`</li></ul>|
|`logfeeder.s3.secret.key`|Amazon S3 secret key.|`EMPTY`|<ul><li>`MySecretKey`</li></ul>|
|`logfeeder.s3.secret.key.file`|Amazon S3 secret key file (that contains only the key).|`EMPTY`|<ul><li>`/my/path/secret_key`</li></ul>|
|`logfeeder.simulate.input_number`|The number of the simulator instances to run with. O means no simulation.|0|<ul><li>`10`</li></ul>|
|`logfeeder.simulate.log_ids`|The comma separated list of log ids for which to create the simulated log entries.|The log ids of the installed services in the cluster|<ul><li>`ambari_server,zookeeper,infra_solr,logsearch_app`</li></ul>|
|`logfeeder.simulate.log_level`|The log level to create the simulated log entries with.|WARN|<ul><li>`INFO`</li></ul>|
|`logfeeder.simulate.max_log_words`|The maximum number of words in a simulated log entry.|5|<ul><li>`8`</li></ul>|
|`logfeeder.simulate.min_log_words`|The minimum number of words in a simulated log entry.|5|<ul><li>`3`</li></ul>|
|`logfeeder.simulate.number_of_words`|The size of the set of words that may be used to create the simulated log entries with.|1000|<ul><li>`100`</li></ul>|
|`logfeeder.simulate.sleep_milliseconds`|The milliseconds to sleep between creating two simulated log entries.|10000|<ul><li>`5000`</li></ul>|
|`logfeeder.solr.cloud.client.discover`|On startup, with a Solr Cloud client, the Solr nodes will be discovered, then LBHttpClient will be built from that.|false|<ul><li>`true`</li></ul>|
|`logfeeder.solr.implicit.routing`|Use implicit routing for Solr Collections.|false|<ul><li>`true`</li></ul>|
|`logfeeder.solr.jaas.file`|The jaas file used for solr.|/etc/security/keytabs/logsearch_solr.service.keytab|<ul><li>`/usr/lib/ambari-logsearch-logfeeder/conf/logfeeder_jaas.conf`</li></ul>|
|`logfeeder.solr.kerberos.enable`|Enables using kerberos for accessing solr.|false|<ul><li>`true`</li></ul>|
|`logfeeder.solr.metadata.collection`|Metadata collection name that could contain log level filters or input configurations.|`EMPTY`|<ul><li>`logsearch_metadata`</li></ul>|
|`logfeeder.solr.urls`|Comma separated solr urls (with protocol and port), override logfeeder.solr.zk_connect_string config|`EMPTY`|<ul><li>`https://localhost1:8983/solr,https://localhost2:8983`</li></ul>|
|`logfeeder.solr.zk_connect_string`|Zookeeper connection string for Solr.|`EMPTY`|<ul><li>`localhost1:2181,localhost2:2181/mysolr_znode`</li></ul>|
|`logfeeder.tmp.dir`|The tmp dir used for creating temporary files.|java.io.tmpdir|<ul><li>`/tmp/`</li></ul>|
|`logsearch.config.zk_acls`|ZooKeeper ACLs for handling configs. (read & write)|world:anyone:cdrwa|<ul><li>`world:anyone:r,sasl:solr:cdrwa,sasl:logsearch:cdrwa`</li></ul>|
|`logsearch.config.zk_connect_string`|ZooKeeper connection string.|`EMPTY`|<ul><li>`localhost1:2181,localhost2:2181/znode`</li></ul>|
|`logsearch.config.zk_connection_retry_time_out_ms`|The maximum elapsed time for connecting to ZooKeeper in milliseconds. 0 means retrying forever.|`EMPTY`|<ul><li>`1200000`</li></ul>|
|`logsearch.config.zk_connection_time_out_ms`|ZooKeeper connection timeout in milliseconds|`EMPTY`|<ul><li>`30000`</li></ul>|
|`logsearch.config.zk_root`|ZooKeeper root node where the shippers are stored. (added to the connection string)|`EMPTY`|<ul><li>`/logsearch`</li></ul>|
|`logsearch.config.zk_session_time_out_ms`|ZooKeeper session timeout in milliseconds|`EMPTY`|<ul><li>`60000`</li></ul>|
