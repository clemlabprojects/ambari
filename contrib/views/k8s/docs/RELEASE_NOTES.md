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

### Authentication

- The service wizard warns when Trino, Superset or OpenMetadata would be published through an Ingress (a Route on
  OpenShift) without a security profile that requires sign-in, and deploys only once you confirm it. Without one,
  Trino accepts any user name a client sends.
- With OIDC, Trino accepts only the JWTs issued for its own OIDC client (their audience must be the client id), not
  every token of the realm. KDPS adds that audience to the tokens of the clients it registers: Trino's own, and
  Superset's when Superset connects to a Trino deployed by KDPS. A Trino deploy also adds its audience to the tokens of
  the Superset releases already connecting to it in OIDC mode, before Trino restarts, so they keep working without a
  redeploy; the deploy log names any it could not update, to redeploy (Upgrade/Config, no change needed). With a
  security profile whose OIDC client you register yourself (External source), Trino requires the profile's new JWT
  Audience, or its Client ID when that is blank: set it to the audience your identity provider issues (an application
  ID URI such as `api://...`, for instance) and make sure the applications calling Trino get tokens with it.

### OIDC client secrets

- The **Rotate Secrets** button is removed from Admin > OIDC. It only ran OIDC provisioning again, which reads each
  client's current secret back from Keycloak, so no client secret was ever changed.
- To rotate the secret of an OIDC client, regenerate it in Keycloak (Clients > *client* > Credentials > Regenerate
  Secret), then:
  - for a cluster service, click **Run OIDC Provisioning** in Admin > OIDC, which stores the new secret in Ambari and
    in the service configuration, and restart the affected services. OIDC sign-in to the service fails from the
    regeneration until its restart. The button is shown only on a Kerberized cluster; on a cluster without Kerberos,
    send the same provisioning request through the REST API
    (`PUT /api/v1/clusters/<cluster>?configure_oidc_only=true` with `{"Clusters":{"security_type":"NONE"}}`), then
    restart the services marked Restart Required.
  - for a KDPS release, redeploy it (Upgrade/Config, no change needed), which writes the new secret into the release's
    Kubernetes Secret, and restart its pods if the redeploy did not. The release's **Re-register OIDC client** action
    does not replace the redeploy here.
- The full procedure is in the Ambari OIDC guide,
  [Rotate the client secret of a service's OIDC client](../../../../ambari-server/docs/security/sso/oidc.md#rotate-a-service-oidc-client-secret).

### Keycloak prerequisites

- KDPS registers the OIDC clients of its releases through the Keycloak admin API, with the settings of the cluster's
  OIDC service (`oidc-env`). Before deploying a release with OIDC sign-in:
  - Keycloak must be reachable at `oidc_admin_url` from the Ambari server (or the Ambari pod), with its TLS certificate
    trusted by Ambari's truststore, and the release's pods must reach the issuer URL and trust its CA.
  - The service realm (`oidc_realm`) must exist; KDPS creates clients in it, never the realm.
  - The Keycloak admin principal needs the `manage-clients` and `view-clients` roles on the service realm.
  - The admin credential must be in Ambari's persistent credential store (`ambari-server setup-security`): KDPS does
    not read the temporary store, so a credential kept only there is not found, and it is lost when Ambari restarts.
    A confidential admin client with a service account (`oidc_admin_client_secret`) is the alternative.
- The full list, the order to follow and the errors a missing prerequisite produces are in the Ambari OIDC guide,
  [Keycloak prerequisites](../../../../ambari-server/docs/security/sso/oidc.md#keycloak-prerequisites).

### Single sign-on for cluster services

- `ambari-server setup-sso-oidc` no longer asks whether Ambari should manage OIDC single sign-on for cluster services
  such as Ranger. That setting was never applied to any service. The `--sso-oidc-manage-services` and
  `--sso-oidc-enabled-services` options are still accepted but only print a warning, and the stored values are cleared.
- Single sign-on for Ranger Admin goes through Knox SSO (`ambari-server setup-sso` with `--sso-manage-services` and
  `--sso-enabled-services`), which can federate to Keycloak.
