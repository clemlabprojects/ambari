# Apache Ambari Log Search
[![Build Status](https://builds.apache.org/buildStatus/icon?job=Ambari-LogSearch-master-Commit)](https://builds.apache.org/view/A/view/Ambari/job/Ambari-LogSearch-master-Commit/)
![license](http://img.shields.io/badge/license-Apache%20v2-blue.svg)

Log Search is a sub-project of [Apache Ambari](https://github.com/apache/ambari)

## Introduction

Log aggregation, analysis, and visualization for Ambari managed (or any other) services.

## Features

- Parse / aggregate and ship logs
- Send and index logs in Solr
- Store logs (structured or unstructured format) in Cloud Storage (S3 / GCS / ADLS / WASB)
- Full-text Search in logs (if the logs are shipped to Solr)
- JWT/SSO support
- Support testing the log parsing on the UI

## Architecture

- Log Feeder: agent component on all hosts to monitor and ship logs.
- Log Search Portal: REST API + UI for rendering logs
- Solr (Optional - default): storage for logs, used by both Log Search Portal and Log Feeder
- ZooKeeper (Optional - default): configuration service for Solr, Log Search and Log Feeder
- HDFS / S3 / GCS / ADLS: storage for logs (write only), used by Log Feeder [cloud mode](docs/cloud_mode.md)

![Log Search Architecture Overview](docs/images/architecture_overview.jpg)

## Contents

- [1. Installation](docs/installation.md)
- [2. Collections](docs/collections.md)
- [3. Adding new logs to monitor](docs/add_new_input.md) 
- [4. Development guide](docs/development.md)
- [5. Using Log Feeder in Cloud mode](docs/cloud_mode.md)

## Contributing

https://cwiki.apache.org/confluence/display/AMBARI/How+to+Contribute

(That is the ambari contribution guide, everything is the same here except use ambari-logsearch repository instead of ambari)

## License

- http://ambari.apache.org/license.html
- See more at [Ambari repository](https://github.com/apache/ambari)
