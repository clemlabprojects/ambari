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
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.format import format
from resource_management.libraries.script.script import Script
from resource_management.libraries.functions.version import format_stack_version
from resource_management.libraries.functions import stack_select

config = Script.get_config()
stack_root = Script.get_stack_root()
stack_version_unformatted = str(config['clusterLevelParams']['stack_version'])
stack_version_formatted = format_stack_version(stack_version_unformatted)

# Where the packaged virtual environment lands. dbt is a command, not a daemon, so the whole
# service is this directory plus the profile written next to it.
#
# odp-select owns this symlink and Ambari re-points it on install and upgrade; the package also
# lays down a default one so it is usable the moment it is installed.
dbt_home = format("{stack_root}/current/dbt-client")

dbt_user = config['configurations']['dbt-env']['dbt_user']
user_group = config['configurations']['cluster-env']['user_group']
dbt_conf_dir = config['configurations']['dbt-env']['dbt_conf_dir']
dbt_log_dir = config['configurations']['dbt-env']['dbt_log_dir']

dbt_profile_name = str(config['configurations']['dbt-env']['dbt_profile_name']).strip() or "trino"
dbt_target = str(config['configurations']['dbt-env']['dbt_target']).strip() or "prod"

trino_host = str(default("/configurations/dbt-env/dbt_trino_host", "")).strip()
trino_port = int(default("/configurations/dbt-env/dbt_trino_port", 8443))
trino_catalog = str(default("/configurations/dbt-env/dbt_trino_catalog", "iceberg")).strip()
trino_schema = str(default("/configurations/dbt-env/dbt_trino_schema", "analytics")).strip()
trino_threads = int(default("/configurations/dbt-env/dbt_trino_threads", 4))
trino_user = str(default("/configurations/dbt-env/dbt_trino_user", "dbt")).strip()
trino_service_name = str(default("/configurations/dbt-env/dbt_trino_service_name", "trino")).strip()

auth_method = str(default("/configurations/dbt-env/dbt_auth_method", "kerberos")).strip().lower()
trino_password = default("/configurations/dbt-env/dbt_trino_password", "")
trino_jwt_token = default("/configurations/dbt-env/dbt_trino_jwt_token", "")
ssl_verify = str(default("/configurations/dbt-env/dbt_ssl_verify", "")).strip()

security_enabled = config['configurations']['cluster-env']['security_enabled']

# Every authenticated mode reaches Trino over TLS; only an unauthenticated connection may be plain.
http_scheme = "http" if auth_method == "none" else "https"

# The profile rendered below mirrors, field for field, what the Kubernetes deployment writes, so a
# project behaves the same whether it runs here or there.
dbt_env_content = config['configurations']['dbt-env']['content']
