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

### GitOps deployments

- Every change of a GitOps release now lands on the base branch that Flux reconciles (main unless you choose another
  one). A direct commit is pushed to that branch; the "Working branch" field is only offered for pull requests, and a
  direct commit naming another branch is refused.
- In pull request mode, each change is added to the release's pull request while it is open. Once that pull request is
  merged or closed, the next change opens a new pull request into the base branch from a new branch. A change that no
  longer goes through the release's open pull request (a switch to direct commit, another base branch or another
  working branch) closes that pull request first, so merging it later cannot bring back what it proposed. Pull requests are
  opened on GitHub (github.com and GitHub Enterprise), GitLab (including a custom port or path) and Gitea; any other
  Git server is refused with a clear error, and pull request mode needs the access token of the Git server.
- Uninstalling a release deployed in pull request mode opens a pull request that removes it. The release keeps running,
  shown as "Removal pending", until that pull request is merged; uninstall it again then to finish removing it from the
  cluster and KDPS.
- Releases deployed in direct commit mode by an earlier version with an empty working branch were committed to a branch
  of their own, which Flux never read. Their next deploy commits them to main.
- A direct commit is refused when the Git server does not have the base branch (for example a repository whose branch
  is master, deployed to with the default main), instead of creating a branch Flux does not read. A pull request whose
  working branch is the base branch itself is refused too: the change would reach that branch without review.
- Uninstalling a release whose pull request is still open closes that pull request, so merging it later cannot bring
  the release back. If its removal pull request is closed without being merged, the release is no longer shown as
  "Removal pending".
- An uninstall is refused, and the release kept, when KDPS cannot tell where Flux reads it: its base branch was deleted
  on the Git server, or it was deployed in pull request mode by an earlier version, which did not record its base
  branch, and main does not hold it. Deploy the release once more, naming its base branch, then uninstall it.
