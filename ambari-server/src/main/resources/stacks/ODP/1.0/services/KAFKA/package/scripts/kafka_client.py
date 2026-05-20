"""
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

"""

from resource_management.libraries.script.script import Script
from resource_management.core.exceptions import ClientComponentHasNoStatus


class KafkaClient(Script):
    """
    Lightweight CLIENT-category component that simply ensures the kafka
    package (and its odp-select managed /usr/<stack>/current/kafka-broker
    symlink chain) is installed on the host. Required on edge/gateway nodes
    that need the kafka-topics.sh / kafka-console-{producer,consumer}.sh /
    kafka-consumer-groups.sh CLI tools but do not run a broker daemon.

    Configuration is intentionally minimal: the same kafka RPM provides all
    CLI tools and reads /etc/kafka/conf/kafka_client_jaas.conf for the
    Kerberos JAAS context. That JAAS file is materialised from the
    kafka_client_jaas_conf config dictionary listed in metainfo, so no
    explicit configure() work is required here.
    """

    def install(self, env):
        self.install_packages(env)

    def configure(self, env):
        # No additional client-side configuration required. The kafka RPM
        # postinstall maintains /usr/<stack>/current/kafka-broker and the
        # broker config dir at /etc/kafka/conf is shared with this client.
        pass

    def status(self, env):
        raise ClientComponentHasNoStatus()


if __name__ == "__main__":
    KafkaClient().execute()
