# Ambari (standalone) with the KDPS view, on UBI 9

A container image that runs Ambari with **no cluster and no agents**, purely as a host for the
Kubernetes Data Platform Services view.

It exists for one situation: the Kubernetes or OpenShift API is not reachable from outside the
cluster, so KDPS cannot deploy into it from an Ambari server sitting on a VM. Running Ambari *inside*
the cluster removes the problem — the view talks to the API over the pod network, and authenticates
with its own ServiceAccount token, which the fabric8 client picks up on its own. No kubeconfig is
shipped and no API endpoint has to be exposed.

The data platforms this Ambari manages are reached as KDPS **platform contexts**: a remote Ambari, a
Cloudera Manager, or a manually described Hadoop cluster. That is what makes a cluster-less Ambari
useful rather than odd.

> Status: the files here are complete but have not yet been built or run end to end. Treat the first
> build as a bring-up, not a regression run.

## What is in the image

| Component | Why |
|---|---|
| `registry.access.redhat.com/ubi9/ubi` | Base, as required for the customer's OpenShift platform |
| `java-11-openjdk-headless` | Runs the server |
| `python3` + `distro` | The server RPM requires both; `python3-distro` is not in the UBI repositories, so pip supplies it |
| `postgresql` (client) | Schema load and the readiness check in the entrypoint |
| `helm` | KDPS shells out to the binary rather than embedding a Helm library |
| `git` | The GitOps deployment mode |
| `ambari-server` | From the repository the release build published |

No agent, no stack RPMs, no `ambari-server setup` prompts: setup runs silently at build time, and the
database is configured at run time because its address is not known when the image is built.

## Building

`AMBARI_REPO_URL` is required — the `ambari-server` RPM is not on a public mirror, so the build has to
be told where the release pipeline published it. The architecture of the repository must match the
architecture of the image.

```sh
export AMBARI_REPO_URL=http://<build-host>/ambari-release/dist/centos9-aarch64/1.x/BUILDS/2.8.2.0-137/rpms
export AMBARI_VERSION=2.8.2.0-137
./build-image.sh
```

That produces `registry.clemlab.com/clemlabprojects/ambari-kdps:2.8.2.0-137-arm64`.

Per-architecture native builds are the default, the same convention the ODP images follow: each agent
builds only its own architecture, so nothing runs under emulation. Once both have pushed,
`merge-manifest.sh` assembles the multi-arch manifest under the bare tag. A single-run multi-arch
build is still available for ad-hoc use (`PLATFORMS=linux/amd64,linux/arm64`), but it needs a builder
with both platforms registered, which a plain agent does not have.

### In CI

`Jenkinsfile` in this directory is a standalone pipeline: amd64 on `ubuntu24`, arm64 on `rhel9-arm`,
then the manifest merge. Configure it as a Pipeline job with *Pipeline script from SCM* and script
path `dev-support/ambari-kdps-ubi9/Jenkinsfile`.

It is deliberately **not** a stage in the release `Jenkinsfile` at the repository root. That one
builds the RPMs; this one consumes them, and needs to be re-runnable on its own when only the image
changes — a UBI CVE refresh, a newer helm, an updated view jar. Both agents are expected to be logged
in to Harbor already, which is how the ODP image stages work.

## Running

### Environment

| Variable | Default | |
|---|---|---|
| `AMBARI_DB_HOST` | — | **required** |
| `AMBARI_DB_PASSWORD` | — | **required** |
| `AMBARI_DB_PORT` | `5432` | |
| `AMBARI_DB_NAME` | `ambari` | |
| `AMBARI_DB_USER` | `ambari` | |

The entrypoint waits for Postgres, configures the server against it, loads the DDL only if the
`clusters` table is absent, copies any jar found in `/opt/ambari-views` into the server's views
directory, and then starts the server. Every step is idempotent: the pod will be restarted,
rescheduled and rolled, and each start has to converge rather than assume a fresh database.

### OpenShift

The image is built for the **restricted-v2 SCC** and needs no exception: it runs as an arbitrary UID
in group 0, everything it writes to is group-writable, and there is no `USER root` and no
`runAsUser: 0`. Do not add one — an image that needs uid 0 cannot run under the restricted SCC, which
is the whole reason this image exists in that form.

One consequence to be aware of: the assigned UID has no `passwd` entry, and Ambari's Python calls
`getpwuid()` during setup. The entrypoint appends an entry when `/etc/passwd` is writable, and logs a
clear warning when it is not. If your platform makes `/etc/passwd` read-only, project one in instead:

```yaml
    - name: passwd
      mountPath: /etc/passwd
      subPath: passwd
```

What the deployment needs around it:

- **Postgres** — external, or a small in-cluster instance. It holds all Ambari state; the pod is
  disposable.
- **A PVC for `/var/lib/ambari-server/resources/views`** if you want view work directories to survive
  a restart. Not strictly required — the view re-extracts — but it makes restarts much faster.
- **A Route or Ingress** to port 8080 (or 8443 once TLS is configured).
- **RBAC for the ServiceAccount.** This is the part that is easy to under-scope: KDPS installs Helm
  releases, so its ServiceAccount needs create/update/delete on everything those charts contain, in
  every namespace it deploys to. In practice that means a Role (or ClusterRole) covering workloads,
  services, configmaps, secrets, PVCs, service accounts, RBAC objects and the CRDs the charts use.
  Start namespace-scoped and widen deliberately; do not reach for `cluster-admin` because it is
  quicker.

### Known gaps to close during bring-up

- TLS is not configured; the server listens on 8080. Terminate at the Route, or run
  `ambari-server setup-security` and mount a keystore.
- The admin password is Ambari's default until you change it. Do that before exposing the Route.
- Two replicas would both try to load the schema and both try to serve; run one.

## Pushing to git for GitOps

KDPS's GitOps mode writes manifests to a repository that Flux or Argo then reconciles. The question is
what holds the credential that pushes.

The answer that fits this image is **neither Ansible nor a separate baremetal KDPS**: the pod already
has `git`, and a deploy key mounted as a Secret is the smallest thing that works. The push happens
where the manifests are generated, the credential never leaves the cluster, and there is no second
system to keep in step.

Ansible is the right tool for the bootstrap *around* it — creating the repository, seeding the
Flux/Argo pointer at it, and installing the deploy key as a Secret — because those are one-time,
out-of-cluster actions. It is the wrong tool for the per-deployment push, which would mean an Ansible
run triggered by every wizard completion.

A baremetal KDPS pushing on behalf of the in-cluster one is the option to avoid: two KDPS instances
that both believe they own the release history is exactly the split-brain this image was meant to
remove.
