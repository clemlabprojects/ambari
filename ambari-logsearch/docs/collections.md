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

### Collections

By default there are 3 main storage layer abstractions in Log Search:

- Service logs (default name: `hadoop_logs`)
- Audit logs (default name: `audit_logs`)
- Metadata (default name: `logsearch_metadata`)

Service logs collection is responsible to store most of the logs (by type), except audit related data, for those use the audit logs collection. 
The metadata collection is used to store Log Search UI related (dynamic) configurations/settings. 

### Schema fields

Minimal required service log fields in Solr to make it work with the UI:

- id (string, unique - identifier for Solr doc)
- log_message
- type (string - log type)
- logtime (timestamp)
- seq_num (numeric - sequence number for log events, useful to not sort only by date)
- level (string - log level for logs, e.g.: WARN)
- host (string)
- cluster (string)

see more: [Service logs schema](https://github.com/apache/ambari-logsearch/blob/master/ambari-logsearch-server/src/main/configsets/hadoop_logs/conf/managed-schema)

Minimal required audit log fields in Solr to make it work with the UI:

- id (string, unique - identifier for Solr doc)
- evtTime (timestamp)
- repo (string - represents the audit source type)
- seq_num (numeric - sequence number for log events, useful to not sort only by date)

see more: [Audit logs schema](https://github.com/apache/ambari-logsearch/blob/master/ambari-logsearch-server/src/main/configsets/audit_logs/conf/managed-schema)

Fields for metadata:
- id (string, unique - identifier for Solr doc)
- name (string - metadata identifier)
- username (string - for identify user related data)
- type (string - type of the metadata)
- value (string - can be anything)

### Customize field names on the Log Search UI

Field name labels on the UI can be customized in `logsearch.properties`, see: [AMBARI-22842](https://issues.apache.org/jira/browse/AMBARI-22842)