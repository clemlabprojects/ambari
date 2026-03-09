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
from resource_management.core.exceptions import ClientComponentHasNoStatus
from resource_management.libraries.functions.stack_features import check_stack_feature
from resource_management.libraries.functions.constants import StackFeature
from resource_management.core.logger import Logger
from resource_management.libraries.functions import stack_select

class ImpalaCLient(ImpalaBase):
    # Call setup.sh to install the service
    def install(self, env):

        # Install packages listed in metainfo.xml
        self.install_packages(env)
        self.installImpala(env)
        self.configure(env)

    def configure(self, env, upgrade_type=None):
        self.configureImpala(env, name='client', upgrade_type=upgrade_type)

    def status(self, env):
        raise ClientComponentHasNoStatus()

    def pre_upgrade_restart(self, env, upgrade_type=None):
        import params
        env.set_params(params)

        if params.version and check_stack_feature(StackFeature.ROLLING_UPGRADE, params.version):
            Logger.info("Executing Impala Client Stack Upgrade pre-restart")
            stack_select.select_packages(params.version)


if __name__ == "__main__":
    ImpalaCLient().execute()
