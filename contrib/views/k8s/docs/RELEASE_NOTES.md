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

# KDPS release notes

## Ambari 2.8.2.0

### Services not in this release

These services were offered by pre-release builds and are no longer in the service catalog.

- **SQL Assistant**: not ready for production use. Its model server would be reachable without authentication
  through the Route or Ingress the wizard turned on by default. It needs to download its model from the internet, which
  an air-gapped cluster cannot do. It also cannot query a Trino that uses TLS or a Kerberized HiveServer2. The SQL
  Assistant Ambari view is not packaged either.
- **dbt**: not ready for production use. It cannot be installed on a Kerberized cluster, whatever the authentication
  mode. Its Trino password or token would be kept in the chart values, and committed to Git in GitOps mode. Its
  documentation site would be published without login or TLS. To run dbt, use your own tooling against the Trino
  that KDPS deploys.

### Services with limits

- **GitLab CE** is offered on plain Kubernetes only, for development clusters. On OpenShift it is not in the
  catalog, and a deploy sent to the API is refused with "GitLab is not supported on OpenShift in this release; register
  your existing Git server under Git Repositories." The GitLab chart runs its pods under fixed user IDs that
  OpenShift's default security policy (the `restricted-v2` SCC) rejects, so the install could never finish there.
  GitOps deployments work with your existing Git server once it is registered under Git Repositories.
