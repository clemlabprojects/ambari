<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# ODP ZooKeeper client and server logging

## Configuration in Ambari

Under ZooKeeper > Configs > Advanced, edit these content templates:

| Config type | Generated file | Default behavior |
| --- | --- | --- |
| `zookeeper-logback` | `logback.xml` | Console logging for clients |
| `zookeeper-logback-server` | `logback-server.xml` | Console and rolling-file logging for the daemon |

Both are Jinja templates. They are rendered only when the installed repository
version supports the existing `zookeeper_support_logback` stack feature.
Log4j-based versions retain their existing behavior.

The default templates reuse the existing `zookeeper_log_level`,
`zk_log_dir`, `zookeeper_filename`,
`zookeeper_log_max_backup_size` and
`zookeeper_log_number_of_backup_files` variables.
The backup-size property is expressed in MB, as declared in the existing UI.
Custom templates can choose other layouts, destinations and logger levels.

Do not make client logging depend on write access to the daemon log directory.
The default client template does not even instantiate a file appender.

## Existing clusters

The final Ambari upgrade catalog adds missing `content` defaults for these two
config types. It does not overwrite existing content or reset the saved
`zookeeper-env/content` template. The shared upgrade helper uses each cluster's
stack definitions and skips properties belonging to services not installed.

The agent also has equivalent packaged templates as a fallback when a command
does not yet contain either new config type. This makes configuration safe
before the database defaults have been added; it is not a replacement for
registering the stack metadata on Ambari Server.

During configuration, Ambari renders the server file, appends a server-only
logging selector to the saved environment template, and renders the client
file. The selector uses `SERVER_JVMFLAGS`, not `JVMFLAGS` or
`CLIENT_JVMFLAGS`. Heap options, JAAS options and the saved environment content
are retained. Repeated rendering/sourcing does not duplicate the selector.

An explicit `-Dlogback.configurationFile=` in `SERVER_JVMFLAGS` or common
`JVMFLAGS` takes precedence: Ambari does not add another selector. In that
case the administrator owns the selected configuration and must keep it
appropriate for the processes inheriting those flags. Client-specific overrides
in `CLIENT_JVMFLAGS` are also retained.

All files remain beneath the selected `config_dir`. This change does not alter
`odp-select`, `odp-conf-select`, configuration symlinks, daemon ownership, or
directory permissions. The same split works when a client shares a host and
configuration directory with a ZooKeeper server.

## Activation and validation

1. Deploy the Ambari fix and follow the supported Ambari upgrade procedure so
   the final upgrade catalog can add missing UI defaults. Merely copying agent
   Python files does not register new UI config types or run this migration.
2. Reconfigure all ZooKeeper client hosts and roll the ZooKeeper servers one at
   a time. Do not restart the entire quorum simultaneously.
3. Run the full ZooKeeper service check, including cross-node reads and cleanup.
4. Run ordinary non-root CLI operations on both client-only and server hosts.
5. Confirm daemon logs still use the expected path and rotate.

No ZooKeeper binary rebuild or changes to the Ansible deployment role are
required for this Ambari fix.

## Local tests

From the repository root, using the Ambari Python test dependencies:

```sh
export PYTHONPATH=ambari-common/src/main/python:ambari-common/src/main/python/ambari_jinja2:ambari-common/src/main/python/ambari_simplejson:ambari-common/src/test/python:ambari-agent/src/main/python:ambari-server/src/test/python:ambari-server/src/main/python
python3 -m unittest discover \
  -s ambari-server/src/test/python/stacks/2.0.6/ZOOKEEPER \
  -p 'test_*.py' -v
```

The new tests cover saved pre-upgrade templates, missing config types, UI
customization, migration metadata, Log4j gating, JVM/JAAS preservation,
administrator overrides, and idempotence.

The optional runtime probe requires JDK 11+ and
`ODP_TEST_LOGBACK_CLASSPATH` containing matching `logback-classic`,
`logback-core` and `slf4j-api` jars. Set `JAVA_HOME` and that classpath before
running the same tests to verify console-only client logging and actual server
log rotation. It uses temporary files and does not connect to a cluster.
Some Logback versions throttle size checks for one minute, so that probe can
take approximately 60 seconds.

`FinalUpgradeCatalogTest` checks that the migration runs during the final
upgrade phase and only adds missing content for the two intended config types.
