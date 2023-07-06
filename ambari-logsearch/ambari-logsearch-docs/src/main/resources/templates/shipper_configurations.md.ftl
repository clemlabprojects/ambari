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

## Log Feeder Shipper Descriptor

### Top Level Descriptors

Input, Filter and Output configurations are defined in 3 (at least) different files. (note: there can be multiple input configuration files, but only 1 output and global configuration)

input.config-myservice.json example:
```json
{
  "input" : [
  ],
  "filter" : [
  ]
}
```
output.config.json example:
```json
{
  "output" : [
  ]
}
```
global.config.json example:
```json
{
  "global" : {
    "source" : "file",
    "add_fields":{
      "cluster":"cl1"
    },
    "tail" : "true"
  }
}
```
| `Path` | `Description` | `Default` | `Examples` |
|---|---|---|---|
<#if shipperConfigs.topLevelConfigSections??>
    <#list shipperConfigs.topLevelConfigSections as section>
|`${section.path}`|${section.description}|${section.defaultValue}|${section.examples}|
    </#list>
</#if>
|`/output`|A list of output descriptors|`{}`|<ul><li>`{"output": [{"is_enabled" : "true", "destination": "solr", "myfield": "myvalue"}]`</li></ul>|
|`/global`|A map that contains field/value pairs|`EMPTY`|<ul><li>`{"global": {"myfield": "myvalue"}}`</li></ul>|

### Input Descriptor

Input configurations (for monitoring logs) can be defined in the input descriptor section.
Example:
```json
{
  "input" : [
    {
      "type": "simple_service",
      "rowtype": "service",
      "path": "/var/logs/my/service/service_sample.log",
      "group": "Ambari",
      "cache_enabled": "true",
      "cache_key_field": "log_message",
      "cache_size": "50"
    },
    {
      "type": "simple_service_json",
      "rowtype": "service",
      "path": "/var/logs/my/service/service_sample.json",
      "properties": {
        "myKey1" : "myValue1",
        "myKey2" : "myValue2"
      }
    },
    {
      "type": "simple_audit_service",
      "rowtype": "audit",
      "path": "/var/logs/my/service/service_audit_sample.log",
      "is_enabled": "true",
      "add_fields": {
        "logType": "AmbariAudit",
        "enforcer": "ambari-acl",
        "repoType": "1",
        "repo": "ambari",
        "level": "INFO"
      }
    },
    {
     "type": "wildcard_log_service",
     "rowtype": "service",
     "path": "/var/logs/my/service/*/service_audit_sample.log",
     "init_default_fields" : "true",
     "detach_interval_min": "50",
     "detach_time_min" : "300",
     "path_update_interval_min" : "10",
     "max_age_min" : "800"
    },
    {
      "type": "service_socket",
      "rowtype": "service",
      "port": 61999,
      "protocol" : "tcp",
      "secure" : "false",
      "source" : "socket",
      "log4j": "true"
    },
    {
      "type": "docker_service",
      "rowtype": "service",
      "docker" : "true",
      "default_log_levels" : [
         "FATAL", "ERROR", "WARN", "INFO", "DEBUG"
      ]
    }
  ]
}
```
| `Path` | `Description` | `Default` | `Examples` |
|---|---|---|---|
<#if shipperConfigs.inputConfigSections??>
    <#list shipperConfigs.inputConfigSections as section>
|`${section.path}`|${section.description}|${section.defaultValue}|${section.examples}|
    </#list>
</#if>

### Filter Descriptor

Filter configurations can be defined in the filter descriptor section.
- Sample 1 for example (json - simple_service_json):
```json
{"level":"WARN","file":"ClientCnxn.java","thread_name":"zkCallback-6-thread-10-SendThread(c6402.ambari.apache.org:2181)","line_number":1102,"log_message":"Session 0x355e0023b38001d for server null, unexpected error, closing socket connection and attempting reconnect\njava.net.SocketException: Network is unreachable\n\tat sun.nio.ch.Net.connect0(Native Method)\n\tat sun.nio.ch.Net.connect(Net.java:454)\n\tat sun.nio.ch.Net.connect(Net.java:446)\n\tat sun.nio.ch.SocketChannelImpl.connect(SocketChannelImpl.java:648)\n\tat org.apache.zookeeper.ClientCnxnSocketNIO.registerAndConnect(ClientCnxnSocketNIO.java:277)\n\tat org.apache.zookeeper.ClientCnxnSocketNIO.connect(ClientCnxnSocketNIO.java:287)\n\tat org.apache.zookeeper.ClientCnxn$SendThread.startConnect(ClientCnxn.java:967)\n\tat org.apache.zookeeper.ClientCnxn$SendThread.run(ClientCnxn.java:1003)\n","logger_name":"org.apache.zookeeper.ClientCnxn","logtime":"1468406756757"}
```
- Sample 2 for example (grok - simple_service):
```text
2016-07-13 10:45:49,640 [WARN] Sample log line 1 - warn level
that is a multiline
2016-07-13 10:45:49,640 [ERROR] Sample log line 2 - error level
2016-07-13 10:45:50,351 [INFO] Sample log line 3 - info level
```
- Sample 3 for example (grok + key/value - ambari_audit):
```text
2016-10-03T16:26:13.333Z, User(admin), RemoteIp(192.168.64.1), Operation(User login), Roles(
    Ambari: Ambari Administrator
), Status(Success)
2016-10-03T16:26:54.834Z, User(admin), RemoteIp(192.168.64.1), Operation(Repository update), RequestType(PUT), url(http://c6401.ambari.apache.org:8080/api/v1/stacks/HDP/versions/2.5/operating_systems/redhat6/repositories/HDP-UTILS-1.1.0.21), ResultStatus(200 OK), Stack(HDP), Stack version(2.5), OS(redhat6), Repo id(HDP-UTILS-1.1.0.21), Base URL(http://public-repo-1.hortonworks.com/HDP-UTILS-1.1.0.21/repos/centos6)
2016-10-03T16:26:54.845Z, User(admin), RemoteIp(192.168.64.1), Operation(Repository update), RequestType(PUT), url(http://c6401.ambari.apache.org:8080/api/v1/stacks/HDP/versions/2.5/operating_systems/redhat7/repositories/HDP-2.5), ResultStatus(200 OK), Stack(HDP), Stack version(2.5), OS(redhat7), Repo id(HDP-2.5), Base URL(http://public-repo-1.hortonworks.com/HDP/centos7/2.x/updates/2.5.0.0/)
```
Example:
```json
{
  "input" : [
  ],
  "filter": [
    {
      "filter": "json",
      "conditions": {
        "fields": {
          "type": [
            "simple_service_json"
          ]
        }
      }
    },
    {
      "filter": "grok",
      "deep_extract": "false",
      "conditions":{
        "fields":{
          "type":[
            "simple_service",
            "simple_audit_service",
            "docker_service"
          ]
        }
      },
      "log4j_format":"# can be anything - only use it as marker/helper as it does not supported yet",
      "multiline_pattern":"^(%{TIMESTAMP_ISO8601:logtime})",
      "message_pattern":"(?m)^%{TIMESTAMP_ISO8601:logtime}%{SPACE}\\[%{LOGLEVEL:level}\\]%{SPACE}%{GREEDYDATA:log_message}}",
      "post_map_values":{
        "logtime":{
          "map_date":{
            "target_date_pattern":"yyyy-MM-dd HH:mm:ss,SSS"
          }
        }
      }
    },
    {
      "filter": "grok",
      "conditions": {
        "fields": {
          "type": [
            "ambari_audit"
          ]
        }
      },
      "log4j_format": "%d{ISO8601} %-5p %c{2} (%F:%M(%L)) - %m%n",
      "multiline_pattern": "^(%{TIMESTAMP_ISO8601:evtTime})",
      "message_pattern": "(?m)^%{TIMESTAMP_ISO8601:evtTime},%{SPACE}%{GREEDYDATA:log_message}",
      "post_map_values": {
        "evtTime": {
          "map_date": {
            "target_date_pattern": "yyyy-MM-dd'T'HH:mm:ss.SSSXX"
          }
        }
      }
    },
    {
      "filter": "keyvalue",
      "sort_order": 1,
      "conditions": {
        "fields": {
          "type": [
            "ambari_audit"
          ]
        }
      },
      "source_field": "log_message",
      "field_split": ", ",
      "value_borders": "()",
      "post_map_values": {
        "User": {
          "map_field_value": {
            "pre_value": "null",
            "post_value": "unknown"
          },
          "map_field_name": {
            "new_field_name": "reqUser"
          }
        }
      }
    }
  ]
}
```
| `Path` | `Description` | `Default` | `Examples` |
|---|---|---|---|
<#if shipperConfigs.filterConfigSections??>
    <#list shipperConfigs.filterConfigSections as section>
|`${section.path}`|${section.description}|${section.defaultValue}|${section.examples}|
    </#list>
</#if>

### Mapper Descriptor

Mapper configurations are defined inside filters, it can alter fields.
Example:
```json
{
  "input": [
  ],
  "filter": [
    {
      "filter": "keyvalue",
      "sort_order": 1,
      "conditions": {
        "fields": {
          "type": [
            "ambari_audit"
          ]
        }
      },
      "source_field": "log_message",
      "field_split": ", ",
      "value_borders": "()",
      "post_map_values": {
        "Status": {
          "map_field_value": {
            "pre_value": "null",
            "post_value": "unknown"
          },
          "map_field_name": {
            "new_field_name": "ws_status"
          }
        },
        "StatusWithRepeatedKeys": [
          {
            "map_field_value": {
              "pre_value": "Failed",
              "post_value": "0"
            }
          },
          {
            "map_field_value": {
              "pre_value": "Failed to queue",
              "post_value": "0"
            }
          }
        ]
      }
    }
  ]
}
```
| `Path` | `Description` | `Default` | `Examples` |
|---|---|---|---|
<#if shipperConfigs.postMapValuesConfigSections??>
    <#list shipperConfigs.postMapValuesConfigSections as section>
|`${section.path}`|${section.description}|${section.defaultValue}|${section.examples}|
    </#list>
</#if>

### Output Descriptor

Output configurations can be defined in the output descriptor section. (it can support any extra - output specific - key value pairs)
Example:

```json
{
  "output": [
    {
      "is_enabled": "true",
      "comment": "Output to solr for service logs",
      "collection" : "hadoop_logs",
      "destination": "solr",
      "zk_connect_string": "localhost:9983",
      "type": "service",
      "conditions": {
        "fields": {
          "rowtype": [
            "service"
          ]
        }
      }
    },
    {
      "comment": "Output to solr for audit records",
      "is_enabled": "true",
      "collection" : "audit_logs",
      "destination": "solr",
      "zk_connect_string": "localhost:9983",
      "type": "audit",
      "conditions": {
        "fields": {
          "rowtype": [
            "audit"
          ]
        }
      }
    }
  ]
}
```
| `Path` | `Description` | `Default` | `Examples` |
|---|---|---|---|
|`/output/[]/conditions`|The conditions of which input to filter.|`EMPTY`||
|`/output/[]/conditions/fields`|The fields in the input element of which's value should be met.|`EMPTY`|<ul><li>`"fields"{"type": ["hdfs_audit", "hdfs_datanode"]}`</li></ul>|
|`/output/[]/conditions/fields/type`|The acceptable values for the type field in the input element.|`EMPTY`|<ul><li>`"ambari_server"`</li><li>`"spark_jobhistory_server", "spark_thriftserver", "livy_server"`</li></ul>|
|`/output/[]/is_enabled`|A flag to show if the output should be used.|true|<ul><li>`true`</li><li>`false`</li></ul>|
<#if shipperConfigs.outputConfigSections??>
  <#list shipperConfigs.outputConfigSections as section>
|`${section.path}`|${section.description}|${section.defaultValue}|${section.examples}|
  </#list>
</#if>