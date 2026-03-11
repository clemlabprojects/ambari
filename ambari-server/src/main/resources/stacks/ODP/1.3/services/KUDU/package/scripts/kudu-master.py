#!/usr/bin/env ambari-python-wrap
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

from kudu import Kudu
from setup_ranger_kudu import setup_ranger_kudu


class KuduMaster(Kudu):
    def install(self, env):
        self.install_packages(env)
        self.configure(env)

    def configure(self, env):
        Kudu.configure(self, env)
        setup_ranger_kudu('kudu-master')

    def start(self, env):
        self.configure(env)
        self.start_kudu('master')

    def stop(self, env):
        self.stop_kudu('master')

    def status(self, env):
        self.status_kudu('master')

    def add_kudu_master(self, env):
        self.add_kudu_master_to_cluster(env)


if __name__ == '__main__':
    KuduMaster().execute()
