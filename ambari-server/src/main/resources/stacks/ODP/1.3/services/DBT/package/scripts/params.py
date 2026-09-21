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

# The connection, flat. Every value the profile template can need is read here and handed to it,
# because a template is rendered from a flat namespace: there is no configuration object to walk.
# Adding a field to the profile means adding it here and using it there, and nothing else.
dbt_adapter_type = str(default("/configurations/dbt-env/dbt_adapter_type", "trino")).strip().lower()
dbt_host = str(default("/configurations/dbt-env/dbt_host", "")).strip()
dbt_port = int(default("/configurations/dbt-env/dbt_port", 8443))
dbt_database = str(default("/configurations/dbt-env/dbt_database", "")).strip()
dbt_schema = str(default("/configurations/dbt-env/dbt_schema", "analytics")).strip()
dbt_threads = int(default("/configurations/dbt-env/dbt_threads", 4))
dbt_connection_user = str(default("/configurations/dbt-env/dbt_connection_user", "dbt")).strip()

dbt_auth_method = str(default("/configurations/dbt-env/dbt_auth_method", "kerberos")).strip().lower()
dbt_password = default("/configurations/dbt-env/dbt_password", "")
dbt_jwt_token = default("/configurations/dbt-env/dbt_jwt_token", "")
dbt_kerberos_service_name = str(default("/configurations/dbt-env/dbt_kerberos_service_name", "trino")).strip()
dbt_spark_method = str(default("/configurations/dbt-env/dbt_spark_method", "thrift")).strip().lower()
dbt_ssl_verify = str(default("/configurations/dbt-env/dbt_ssl_verify", "")).strip()

security_enabled = config['configurations']['cluster-env']['security_enabled']

# Derived, so the template does not have to work it out: every authenticated mode reaches the
# warehouse over TLS, and only an unauthenticated connection may be plain.
dbt_http_scheme = "http" if dbt_auth_method == "none" else "https"

# The profile itself is a template an operator can edit, the way hue.ini is. Ambari renders it with
# everything above and rewrites profiles.yml on every configure, so the template is the one place
# where the shape of the connection is decided — including for the adapters the default template
# does not spell out.
# Read with a default rather than directly: a service installed before this configuration existed
# has no such type yet, and an unhelpful KeyError deep in the configure step is a poor way to say
# so. The check in dbt_client.py turns the empty value into an explanation.
dbt_profiles_content = default("/configurations/dbt-profiles-template/content", "")

dbt_env_content = config['configurations']['dbt-env']['content']
