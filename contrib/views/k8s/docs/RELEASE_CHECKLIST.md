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

# KDPS view release checklist

What to run before a KDPS view release is packaged. The automated suites also run on every push and pull request
(GitHub workflow `kdps-view-test`), but a release is built from one exact commit: run them again on it.

## 1. Unit tests (Java and UI) on the release commit

From the repository root, on the exact commit that will be packaged:

```bash
mvn -B -Dviews -pl contrib/views/k8s -am install -DskipTests -Drat.skip=true -Dambari.grafana.arch=amd64
mvn -B -Dviews -pl contrib/views/k8s test -Drat.skip=true -Dambari.grafana.arch=amd64
```

`-Dviews` is required: without it `contrib/views` is not part of the build and Maven stops with an error about the
selected project. The first command installs the modules the view depends on, without their tests; the second runs
the view's own tests.

`mvn test` on the view runs both suites: the Java tests (surefire) and the UI's jest tests (`yarn test:ci`). Either
one failing fails the build. Use `-Dambari.grafana.arch=arm64` on an aarch64 machine. Packaging builds pass
`-DskipTests`, which skips both suites.

Run it on a machine with Docker. `GitClientOpenSshServerTest` and `FluxGitOpsBackendSshHostKeyTest` check that a
GitOps deploy refuses an unknown or changed SSH host key; they start an SSH server in a container and, without
Docker, are reported as skipped while the build stays green. The service-advisor tests are skipped the same way
without `python3`. Check that nothing was skipped:

```bash
grep -h '^Tests run' contrib/views/k8s/target/surefire-reports/*.txt | grep -v 'Skipped: 0,'
```

This must print nothing.

## 2. Live-cluster test with an account limited to its own namespace

Run section 1 first: this command builds only the view, so it needs the modules installed by its first command.

```bash
mvn -B -Dviews -pl contrib/views/k8s -Pintegration verify -Drat.skip=true -Dambari.grafana.arch=amd64
```

`verify` runs the unit suites of section 1 again before the live-cluster test.

Needs Docker: `ProjectScopedAccountIT` starts a k3s cluster in a container. It creates a namespace and a
ServiceAccount that holds the built-in `admin` ClusterRole in that namespace only (a RoleBinding, no cluster right),
then runs KDPS with that account's kubeconfig:

- the namespace, ConfigMap, Deployment and event listings fall back to the account's namespace when the cluster
  refuses the cluster-wide listing;
- a chart is installed, upgraded, listed (in its namespace and across all namespaces) and uninstalled, and nothing is
  left behind;
- nothing outside the namespace is changed: a cluster-scoped call by KDPS or by Helm is refused by the cluster and
  fails the test.

Without Docker the test is reported as skipped, so check that the report says `Tests run: 2, Skipped: 0`. To run it
against another cluster instead of k3s, set `KDPS_IT_KUBECONFIG` to the path of a kubeconfig with cluster-admin
rights on a cluster the test may change: it creates and deletes a `kdps-it-*` namespace. `KUBECONFIG` is ignored on
purpose.

## 3. OpenShift checks (by hand, on OpenShift Local or a test cluster)

k3s has no OpenShift APIs, so these are checked by hand before each release. Use an OpenShift user that is an
`admin` of one or two projects and holds no cluster role, connected to KDPS as the view's cluster connection.

| Area | Check | Expected |
|------|-------|----------|
| Projects | Open the namespace picker and the Releases page. | Only the user's projects are listed; no error about listing namespaces. |
| Projects | Open the dashboard. | CPU, memory and pods are shown for the user's projects (measured against their quotas when they have some). |
| Install | Deploy a service into an existing project. | The deploy does not try to create the project and succeeds. |
| SCC | Deploy a service that needs `anyuid` or `nonroot-v2`, without the `kdps-grant-scc` grant. | The deploy stops before installing and names the grant the platform team has to make. |
| SCC | Make the grant, deploy again. | The service accounts get the SCC and the pods start. |
| Monitoring | Deploy with metrics and autoscaling turned on, with user-workload monitoring enabled on the cluster. | The ServiceMonitor is created in the project; the KEDA triggers use the project-scoped Thanos querier port and the workers scale. |
| Monitoring | Same deploy with an account that may not create ServiceMonitors. | The deploy stops before installing and names the missing right. |
| Uninstall | Uninstall the services deployed above. | Nothing is left in the project; no cluster-scoped object was created. |

Record the cluster version, the commit tested and the result of each line in the release ticket.
