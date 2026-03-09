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
from resource_management.core.logger import Logger
from resource_management.libraries.functions import stack_select
from resource_management.libraries.functions.constants import StackFeature
from resource_management.libraries.functions.stack_features import check_stack_feature

class StateStore(ImpalaBase):
    # Call setup.sh to install the service
    def install(self, env):

        # Install packages listed in metainfo.xml
        self.install_packages(env)
        self.installImpala(env)
        # Execute("yum -y install openssl-devel cyrus-sasl cyrus-sasl-gssapi cyrus-sasl-devel")
        self.configure(env)

    def configure(self, env, upgrade_type=None):
        self.configureImpala(env, upgrade_type=upgrade_type)

    # Call start.sh to start the service
    def start(self, env, upgrade_type=None):
        self.configure(env, upgrade_type=upgrade_type)
        self.start_impala_service("impala-state-store")

    def failover_to_this_host(self, env):
        import params
        env.set_params(params)
        self.configure(env)
        self.failover_impala_service(
            "impala-state-store",
            "-statestore_force_active=true",
            params.enable_statestored_ha,
            params.impala_state_store_hosts,
            "statestored",
        )

    # Called to stop the service using the pidfile
    def stop(self, env, upgrade_type=None):
        self.stop_impala_service("impala-state-store")

    # Called to get status of the service using the pidfile
    def status(self, env):
        self.status_impala_service("impala-state-store")

    def pre_upgrade_restart(self, env, upgrade_type=None):
        import params
        env.set_params(params)

        if params.version and check_stack_feature(StackFeature.ROLLING_UPGRADE, params.version):
            Logger.info("Executing Impala StateStore Stack Upgrade pre-restart")
            stack_select.select_packages(params.version)


if __name__ == "__main__":
    StateStore().execute()
