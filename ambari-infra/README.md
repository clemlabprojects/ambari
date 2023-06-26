# Apache Ambari Infra
[![Build Status](https://builds.apache.org/buildStatus/icon?job=Ambari-Infra-master-Commit)](https://builds.apache.org/view/A/view/Ambari/job/Ambari-Infra-master-Commit/)
![license](http://img.shields.io/badge/license-Apache%20v2-blue.svg)

Core shared service used by Ambari managed components. (Infra Solr and Infra Manager)

Ambari Infra is a sub-project of [Apache Ambari](https://github.com/apache/ambari)

## Development

Requires JDK 8

### Build RPM package

```bash
# requires rpm-build on Mac OS X
make rpm
```

### Build Deb package

```bash
make deb
```

## License

- http://ambari.apache.org/license.html 
- See more at [Ambari repository](https://github.com/apache/ambari)
