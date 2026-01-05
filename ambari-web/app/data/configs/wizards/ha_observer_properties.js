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

module.exports =
{
  "haConfig": {
    serviceName: 'MISC',
    displayName: 'MISC',
    configCategories: [
      App.ServiceConfigCategory.create({ name: 'HDFS', displayName: 'HDFS'}),
      App.ServiceConfigCategory.create({ name: 'HBASE', displayName: 'HBase'}),
      App.ServiceConfigCategory.create({ name: 'ACCUMULO', displayName: 'Accumulo'}),
      App.ServiceConfigCategory.create({ name: 'AMBARI_METRICS', displayName: 'Ambari Metrics'}),
      App.ServiceConfigCategory.create({ name: 'HAWQ', displayName: 'HAWQ'}),
      App.ServiceConfigCategory.create({ name: 'OZONE', displayName: 'OZONE'}),
      App.ServiceConfigCategory.create({ name: 'RANGER', displayName: 'Ranger'})
    ],
    sites: ['core-site', 'hdfs-site', 'hbase-site', 'accumulo-site', 'ams-hbase-site', 'hawq-site', 'hdfs-client', 'ozone-env','ozone-site','ranger-env', 'ranger-knox-plugin-properties', 'ranger-kms-audit',  'ranger-ozone-audit', 'ranger-ozone-plugin-properties','ranger-storm-plugin-properties', 'ranger-hbase-plugin-properties', 'ranger-hdfs-plugin-properties', 'ranger-hive-plugin-properties','ranger-ozone-plugin-properties','ranger-nifi-plugin-properties','ranger-nifi-registry-plugin-properties', 'ranger-kafka-audit', 'ranger-knox-audit', 'ranger-hdfs-audit', 'ranger-hive-audit', 'ranger-atlas-audit', 'ranger-storm-audit', 'ranger-hbase-audit','ranger-nifi-audit','ranger-nifi-registry-audit', 'ranger-yarn-audit','ranger-ozone-audit'],
    configs: [
    /**********************************************HDFS***************************************/
      {
        "name": "dfs.namenode.state.context.enabled",
        "displayName": "dfs.namenode.state.context.enabled",
        "description": "Enable NameNode to maintain and update server state and id.",
        "isReconfigurable": false,
        "recommendedValue": "true",
        "value": "true",
        "displayType": "checkbox",
        "category": "HDFS",
        "filename": "hdfs-site",
        "serviceName": 'MISC'
      },
      {
        "name": "dfs.ha.tail-edits.in-progress",
        "displayName": "dfs.ha.tail-edits.in-progress",
        "isReconfigurable": false,
        "description": "This enables fast edit log tailing through in-progress edit logs and also other mechanisms such as RPC-based edit log fetching.",
        "recommendedValue": "true",
        "value": "true",
        "displayType": "checkbox",
        "category": "HDFS",
        "filename": "hdfs-site",
        "serviceName": 'MISC'
      },
      {
        "name": "dfs.ha.tail-edits.period",
        "displayName": "dfs.ha.tail-edits.period",
        "description": "his determines the staleness of Observer NameNode w.r.t the Active",
        "isReconfigurable": false,
        "recommendedValue": "0ms",
        "value": "0ms",
        "category": "HDFS",
        "filename": "hdfs-site",
        "serviceName": 'MISC'
      },
      {
        "name": "dfs.ha.tail-edits.period.backoff-max",
        "displayName": "dfs.ha.tail-edits.period.backoff-max",
        "description": "This determines the behavior of a Standby/Observer when it attempts to tail edits from the JournalNodes and finds no edits available.",
        "isReconfigurable": false,
        "recommendedValue": "10s",
        "value": "10s",
        "category": "HDFS",
        "filename": "hdfs-site",
        "serviceName": 'MISC'
      },
      {
        "name": "dfs.journalnode.edit-cache-size.bytes",
        "displayName": "dfs.journalnode.edit-cache-size.bytes",
        "description": "This is the size, in bytes, of the in-memory cache for storing edits on the JournalNode side.",
        "isReconfigurable": false,
        "recommendedValue": "1048576",
        "value": "1048576",
        "category": "HDFS",
        "filename": "hdfs-site",
        "serviceName": 'MISC'
      },
      {
        "name": "dfs.namenode.accesstime.precision",
        "displayName": "dfs.namenode.accesstime.precision",
        "description": "If enabled, this will turn a getBlockLocations call into a write call, as it needs to hold write lock to update the time for the opened file",
        "isReconfigurable": false,
        "recommendedValue": "0",
        "value": "0",
        "category": "HDFS",
        "filename": "hdfs-site",
        "serviceName": 'MISC'
      }
    ]
  }
};
