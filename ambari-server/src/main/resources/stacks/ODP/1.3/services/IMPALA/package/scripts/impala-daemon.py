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

from impala_base import ImpalaBase

class ImpalaDaemon(ImpalaBase):
    # Call setup.sh to install the service
    def install(self, env):

        # Install packages listed in metainfo.xml
        self.install_packages(env)
        self.installImpala(env)
        self.configure(env)

    def configure(self, env):
        self.configureImpala(env)

    # Call start.sh to start the service
    def start(self, env):
        self.configure(env)
        self.start_impala_service("impala-daemon")

    # Called to stop the service using the pidfile
    def stop(self, env):
        self.stop_impala_service("impala-daemon")

    # Called to get status of the service using the pidfile
    def status(self, env):
        self.status_impala_service("impala-daemon")


if __name__ == "__main__":
    ImpalaDaemon().execute()
