<!---
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at
       http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
--->

# Apache Ambari Metrics
Apache Ambari subproject

[![Build Status](https://builds.apache.org/buildStatus/icon?job=Ambari-Metrics-master-Commit)](https://builds.apache.org/view/A/view/Ambari/job/Ambari-Metrics-master-Commit/)
![license](http://img.shields.io/badge/license-Apache%20v2-blue.svg)

**Ambari Metrics System** ("AMS") is a system for collecting, aggregating, serving and visualizing daemon and system metrics in Ambari-managed clusters.

The original JIRA Epic for Ambari Metrics System can be found here: https://issues.apache.org/jira/browse/AMBARI-5707 
First official release of AMS was with Ambari 2.0.0. With metrics repro split, the aim is to be able to release the sub-project with separate cadence than Ambari.


| Term | Definition |
------ | -----------
Metrics Collector | The standalone server that collects metrics, aggregates metrics, serves metrics from the Hadoop service sinks and the Metrics Monitor.
Metrics Monitor | Installed on each host in the cluster to collect system-level metrics and forward to the Metrics Collector.        
Metrics Hadoop Sinks  | Plug-ins into the various Hadoop components sinks to send Hadoop metrics to the Metrics Collector.                 

The Metrics Collector is daemon that receives data from registered publishers (the Monitors and Sinks). 
The Collector itself is build using Hadoop technologies such as HBase Phoenix and ATS. 
The Collector can store data on the local filesystem (referred to as "embedded mode") or use an external HDFS (referred to as "distributed mode").
It is a fully distributed collection and aggregation system starting from 2.7.0

Please refer to the wiki for more detailed info: https://cwiki.apache.org/confluence/display/AMBARI/Metrics
