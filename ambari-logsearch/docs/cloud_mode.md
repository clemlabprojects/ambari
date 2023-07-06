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

### Log Feeder: Cloud Mode

Log Feeder is responsible to ship logs to any data storage. In Log Search case, it is a search engine (Solr), and that can be used by Log Search server to visualize logs.
Although there is a way to send logs to cloud storage / HDFS as well, of course in that case Solr is not used, so you won't be able to visualize the data. Log Feeder has 3 main modes:

- `DEFAULT`: logs are shipped to Solr (or whatever is configured as output)
- `CLOUD`: logs are shipped to Cloud storage or HDFS (logs as text files, or in json format)
- `HYBRID`: logs are shipped to Solr and Cloud storage (or HDFS) as well (parallel mode) 

The cloud mode can be set by `logfeeder.cloud.storage.mode` property in `logfeeder.properties`.

#### How it works ?

In cloud mode, instead of shipping monitored logs to a specific location (directly) like into Solr, first it will ship the all monitored logs to new files to a temporal folder. Those logs are archived periodically, (you can check the configuration options about the log rollover in [logfeeder.properties](#logfeeder_properties.md) with `logfeeder.cloud.rollover.*` prefixes) and in every minutes, there is a background thread that will try to upload those archived logs to HDFS or cloud storage. 

HDFS client is responsible to do the upload and you can provide a `core-site.xml` on the classpath (the HDFS client can be configured to use different filesystems like s3a,wasb,gcs etc.), but if you do not have any `core-site.xml` on the classpath, you can provide `fs.*` properties in `logfeeder.properties` configuration. 

It is common, on specific clusters, there are both HDFS and cloud storage are used, so it is not valid to use the same filesystem that `core-site.xml` uses, that is why we need an option to override the filesystem valide (`fs.defaultFS`), that property is `logfeeder.cloud.storage.base.path` (e.g.: `s3a://mybucket/my/path`)

Overall - as a minimum - it is enough to provide this 2 properties to enable cloud storage mode:

- `logfeeder.cloud.storage.mode`: CLOUD
- `logfeeder.cloud.storage.base.path`: s3a://mybucket/apps/logsearch
