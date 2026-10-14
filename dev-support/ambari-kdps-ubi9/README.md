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
| `java-17-openjdk-headless` | Runs the server |
| `python3` + `python3-distro` | The server RPM requires both; both come from the UBI repositories |
| `postgresql` (client) | Schema load, schema version check, and the check for the default administrator password, in the entrypoint |
| `helm` | Release rollback and history. Everything else KDPS does with Helm (install, upgrade, uninstall, chart repositories and registries, `show`) runs on the Helm library embedded in the view (helm-java), not on this binary |
| `git` | The GitOps deployment mode |
| `procps-ng` | `ps`, which `ambari-server stop`, `status`, `restart` and the `setup-*` commands use to find the running server |
| `ambari-server` | From the repository the release build published, with only the admin and KDPS views kept. Installed with `gpgcheck=1` against the Clemlab RPM signing key shipped here as `RPM-GPG-KEY-clemlab` |

No agent, no stack RPMs, no `ambari-server setup` prompts: setup runs silently at build time, and the
database is configured at run time because its address is not known when the image is built.

**Size.** About 0.7 GB compressed per architecture. Everything that writes under the Ambari directories
(the RPM install, `ambari-server setup`, the resource-files housekeeping and the OpenShift group
permissions) runs in a single `RUN`: as separate instructions each step rewrote the ~1 GB of RPM files
into a new layer and the image weighed 3.3 GB. The HDFS Files, Capacity Scheduler, Tez and SQL
Assistant views are removed (`DROP_VIEWS`, ~570 MB); to add one back, mount its jar under
`/opt/ambari-views`, which the entrypoint installs at start.

## Building

`AMBARI_REPO_URL` is required — the `ambari-server` RPM is not on a public mirror, so the build has to
be told where the release pipeline published it. The architecture of the repository must match the
architecture of the image.

```sh
export AMBARI_REPO_URL=https://<build-host>/ambari-release/dist/centos9-aarch64/1.x/BUILDS/2.8.2.0-137/rpms
export AMBARI_VERSION=2.8.2.0-137
./build-image.sh
```

That produces `registry.clemlab.com/clemlabprojects/ambari-kdps:2.8.2.0-137-arm64`.

The build fails when the KDPS view in the installed package does not carry the Helm native library of the
image's architecture (checked in the Dockerfile right after the package install). That happens when the repository holds the other
architecture's package, and without the check the image would serve Kubernetes pages but fail every Helm
deploy, upgrade and release list.

The build also fails when a download is not what it should be:

- the Helm tarball's sha256 differs from `HELM_SHA256_AMD64` / `HELM_SHA256_ARM64` (pinned in the
  Dockerfile for its `HELM_VERSION`, taken from the Helm release page). Building another
  `HELM_VERSION` means passing the official sha256 of that version for the architecture built;
  the build refuses an empty one;
- an Ambari package is not signed with the key in `RPM-GPG-KEY-clemlab`, which is the key the
  release pipeline signs with (fingerprint `4E19 B96C 695E C9DA F015 0B55 3E00 E57A D4D1 82C0`).

Per-architecture native builds are the default, the same convention the ODP images follow: each agent
builds only its own architecture, so nothing runs under emulation. Once both have pushed,
`merge-manifest.sh` assembles the multi-arch manifest under the bare tag. A single-run multi-arch
build is still available for ad-hoc use (`PLATFORMS=linux/amd64,linux/arm64`), but it needs a builder
with both platforms registered, which a plain agent does not have.

### Rebuilding the image at a customer site

Each image build publishes its build context next to the image:

```
https://archive.clemlab.com/ambari-kdps-image/<release>-<build>/ambari-kdps-image-context-<version>.tar.gz
```

It holds this directory (Dockerfile, entrypoint, OpenShift patch, build script, image tests and the
Clemlab RPM signing key) and a `BUILD-INFO` file with the exact arguments Clemlab used, including the
Helm checksums. The table lists every network input of the build; each one can come from the
customer's own mirrors, so the image can be rebuilt without reaching the Internet:

| Network input | Build argument | Default | Customer mirror | Checked against |
|---|---|---|---|---|
| Base image | `BASE_IMAGE` + `UBI_VERSION` | `registry.access.redhat.com/ubi9/ubi:9.6` | the customer registry's UBI copy | the registry |
| UBI packages (Java 17, Python and `python3-distro`, the PostgreSQL client, git, procps-ng) | — | the base image's own repositories | a mirror of the UBI 9 repositories, configured in the base image or the build host | the Red Hat key in the base image |
| `ambari-server` RPM | `AMBARI_REPO_URL` | Clemlab yum repository of the build | a `reposync` copy of it (keeps the signatures) | `RPM-GPG-KEY-clemlab`, shipped in the context |
| Helm CLI | `HELM_URL` | `https://get.helm.sh/helm-v<HELM_VERSION>-linux-<arch>.tar.gz` | any HTTP copy of that tarball | `HELM_SHA256_AMD64` / `HELM_SHA256_ARM64`, pinned in the Dockerfile and recorded in `BUILD-INFO` |

`DROP_VIEWS` (default: Files, Capacity Scheduler, Tez, SQL Assistant; an empty string keeps them all)
downloads nothing. Nothing else is fetched: no Python package index, no script piped from a URL.
`ambari-server setup` does try to download the JCE policy files, which Java 17 does not need, from a
host that no longer answers: it logs an error about them and the build carries on.

```sh
tar -xzf ambari-kdps-image-context-2.8.2.0-154.tar.gz && cd ambari-kdps-image-context
docker build \
  --build-arg AMBARI_VERSION=2.8.2.0-154 \
  --build-arg AMBARI_REPO_URL=https://mirror.client.local/ambari/centos9/2.8.2.0.0-154/rpms/ \
  --build-arg BASE_IMAGE=registry.client.local/ubi9/ubi \
  --build-arg HELM_URL=https://mirror.client.local/helm/helm-v3.22.0-linux-amd64.tar.gz \
  -t registry.client.local/kdps/ambari-kdps:2.8.2.0-154 .
```

### In CI

`Jenkinsfile` in this directory is a standalone pipeline: amd64 on `ubuntu24`, arm64 on `rhel9-arm`,
then the manifest merge. Configure it as a Pipeline job with *Pipeline script from SCM* and script
path `dev-support/ambari-kdps-ubi9/Jenkinsfile`.

It is deliberately **not** a stage in the release `Jenkinsfile` at the repository root. That one
builds the RPMs; this one consumes them, and needs to be re-runnable on its own when only the image
changes — a UBI CVE refresh, a newer helm, an updated view jar. A newer helm is a change of
`HELM_VERSION` together with `HELM_SHA256_AMD64` and `HELM_SHA256_ARM64`, all three parameters of the
job (and defaults of the Dockerfile). Both agents are expected to be logged in to Harbor already,
which is how the ODP image stages work.

Before pushing, `build-image.sh` runs `test-image.sh` on the image it has just built: it checks that
`ambari-server status` and `stop` work, that the entrypoint stops the server cleanly on SIGTERM (and
kills it only after `AMBARI_STOP_TIMEOUT`), that the audit log reaches the container's output, and
that the first administrator password replaces `admin` / `admin` before the chart's readiness check
can pass — and that every way of failing at it stops the container instead — and that the database
schema is loaded, kept, upgraded or refused as [The database schema](#the-database-schema) says. The
server, its API, its schema upgrade and its database are replaced by stand-ins for this, so it takes
a few minutes and needs no database. Run it by hand with `./test-image.sh <image>`.

A stand-in database cannot tell whether the image's DDL really loads in one transaction, or whether
Ambari's schema upgrade starts from the image, finds its tables and changes them. So `build-image.sh`
then runs `test-image-postgres.sh`, which runs the image's own setup, `psql`, schema upgrade and
server against PostgreSQL in a container with no network. It checks that an empty database gets the
whole schema; that a user that cannot create tables, or a load that fails at the DDL's last table,
ends the container with psql's error and leaves nothing of the schema; that a schema of the line
before the image's is refused and left exactly as it was; that `AMBARI_SCHEMA_UPGRADE=true` upgrades
it, and completes an upgrade that stopped part-way, to the same tables, columns, keys and indexes as a
fresh load; and that the server starts on the result. It takes about a minute. It runs
`postgres:16-alpine` unless `POSTGRES_IMAGE` names another (a copy in your own registry, for one);
an agent that has no such image and cannot pull it skips these checks with a warning, rather than
fail the build. Run it by hand with `./test-image-postgres.sh <image>`. It knows how to make a schema
of the line before 2.8.2 out of a 2.8.2 one; an image of a new line fails it until that line's is
added.

## Running

### Environment

| Variable | Default | |
|---|---|---|
| `AMBARI_DB_HOST` | — | **required** |
| `AMBARI_DB_PASSWORD` | — | **required** |
| `AMBARI_SECURITY_MASTER_KEY` | — | **required**. Encrypts the credential store and the kubeconfig the KDPS view stores |
| `AMBARI_ADMIN_PASSWORD` | — | **required** unless `AMBARI_ADMIN_ALLOW_DEFAULT=true`, or the database's `admin` account no longer accepts `admin` / `admin`. Replaces the default `admin` / `admin` on first start; see below |
| `AMBARI_ADMIN_ALLOW_DEFAULT` | `false` | `true` lets the server start without `AMBARI_ADMIN_PASSWORD` and keep `admin` / `admin`. Development only |
| `AMBARI_ADMIN_PASSWORD_TIMEOUT` | `300` | Seconds the server's API gets to accept the administrator password before the container exits |
| `AMBARI_DB_PORT` | `5432` | |
| `AMBARI_DB_NAME` | `ambari` | |
| `AMBARI_DB_USER` | `ambari` | |
| `AMBARI_STOP_TIMEOUT` | `50` | Seconds the server gets to stop on SIGTERM before it is killed. Keep it below the pod's `terminationGracePeriodSeconds` |
| `AMBARI_SCHEMA_UPGRADE` | `false` | `true` upgrades the schema of a database from an earlier Ambari line before the server starts. Back the database up first; see below |

The entrypoint waits for Postgres, configures the server against it, loads the schema into a database
that has none and checks the version of one that has, copies any jar found in `/opt/ambari-views` into
the server's views directory, starts the server, and then replaces the default administrator
password. Every step is idempotent: the pod will be restarted, rescheduled and rolled, and each start
has to converge rather than assume a fresh database.

### The database schema

Each Ambari line — the first three numbers of the version, so every 2.8.2.x build is the 2.8.2 line —
has a schema of its own. The schema records its version in the `metainfo` table, and the server
refuses to start on another line's. The entrypoint reads that version with `psql` before it starts
the server, and compares it with the server's own (the file named by `server.version.file`):

| The database | What happens |
|---|---|
| has no Ambari schema | The image's DDL is loaded in a single transaction that stops at the first error, so the database ends up with all of the schema or none of it. The DDL's own `BEGIN` and `COMMIT` are left out, or its `COMMIT` would end that transaction half-way. When the load fails, psql's error is in the pod logs (for example `permission denied for schema public`: from PostgreSQL 15 on, a user that does not own the database cannot create tables in its `public` schema unless it is granted) and the container exits. |
| has a schema of the same line | The server starts on it. Any 2.8.2.x image runs on a 2.8.2 schema. |
| has a schema of an earlier line | The container exits and says so, naming both versions, unless `AMBARI_SCHEMA_UPGRADE=true`. Nothing is changed, so an image of the earlier line still runs on the database. |
| has a schema of an earlier line, and `AMBARI_SCHEMA_UPGRADE=true` | Ambari's schema upgrade runs before the server starts, and the lines it logs, with every warning or error logged meanwhile, are copied to the pod logs. When it fails, the container exits with the reason; the upgrade runs again on the next start for as long as the variable is set. |
| has a schema of a later line | The container exits: a schema is never downgraded. Run an image of that line, or restore the backup taken before the upgrade. |

The "schema present" sign is the `metainfo` version row, not the presence of some table: because the
load is one transaction, that row exists only once the whole schema does. A database holding tables
but no version — left by an image before this one whose first load stopped part-way — makes the load
fail with `relation ... already exists`; start again from an empty database.

The tables go where the database user's `search_path` puts them: in `public`, unless the database has
a schema named after the user. `ambari-server setup` records a schema named after the database
instead (`server.jdbc.postgres.schema`), and the schema upgrade looks for tables, columns and keys in
that schema only: where the two differ, it takes every table to be missing, fails on creating one
that is already there — so an upgrade that stopped part-way could never complete — and skips the
changes to what it does not find, then reports success. So on every start, before the upgrade and the
server run, the entrypoint records the schema it finds the `metainfo` table in, in place of setup's
(or one given in `AMBARI_EXTRA_PROPERTIES`), and says so in the pod logs when it changes it.

The schema upgrade is the program `ambari-server upgrade` runs (`SchemaUpgradeHelper`), started the
same way, from the server's configuration and libraries, as the container's own user. The command
itself is not used: it refuses to run as anyone but root, and the rest of what it does — merging the
configuration files a package upgrade set aside, replaying management packs, taking ownership of
files — is for a host whose RPM was upgraded in place, not for a new image. The program's own output
goes to `/var/log/ambari-server/ambari-server-upgrade.log`, as it does with the command.

Back the database up before setting `AMBARI_SCHEMA_UPGRADE=true`: an upgrade that fails part-way can
leave the schema half-upgraded, and there is no way back to the earlier line but the backup. Unset it
once the server has started, so that the next move to a new line waits for a backup again; while it
stays set on a schema that is already current, every start says so in the pod logs. The upgrade runs
before the server listens, so it counts against the time a liveness probe gives the container to
start.

### The first administrator password

The DDL creates the `admin` account with the password `admin`, which is in Ambari's public
documentation. Once the server is up, the entrypoint checks whether the account still has that
password — by reading the digest the DDL stored, not by trying `admin` / `admin` at the login, which
would count towards `authentication.local.max.failures` — and if so changes it to
`AMBARI_ADMIN_PASSWORD` through the server's own API on `127.0.0.1` (HTTPS with `-k` when `api.ssl`
is on), retrying while the API is not answering yet. Any other password is left alone, so the step
runs once per database: a restart, or a password since changed in the UI, skips it.

An `admin` account that still has the default password but is disabled, or locked out after
`authentication.local.max.failures` failed sign-ins, is left alone too, with a warning in the pod
logs: the server turns `admin` / `admin` away from it, and the password can only be changed by
signing in. Give it another password from another administrator account before enabling or
unlocking it. Enabled or unlocked with the default password, it accepts `admin` / `admin` until the
next start replaces that with `AMBARI_ADMIN_PASSWORD`.

Without `AMBARI_ADMIN_PASSWORD` (and without `AMBARI_ADMIN_ALLOW_DEFAULT=true`) the entrypoint reads
the same digest before it starts the server. On a new database, or one whose `admin` account still
accepts `admin` / `admin`, it exits with `AMBARI_ADMIN_PASSWORD is not set`; so it does when the
database cannot be read. When the account no longer accepts the default — its password was changed,
or it is disabled or locked out — there is nothing to replace, and the server starts. That is what
lets an installation made before the image asked for a password, whose password was changed by hand,
move to this image without one. Give it the password all the same: a new database, such as a
disaster-recovery copy built empty, needs it, and the `ambari` chart from 0.1.11 does not render
without it.

Only then does it create `/tmp/admin-bootstrapped`, and a readiness check has to require that file:
until it exists the server accepts `admin` / `admin`. The `ambari` chart's readiness probe is

```
test -f /tmp/admin-bootstrapped && curl -fsS -o /dev/null http://127.0.0.1:8080/
```

The password is never written to the output and never on a command line: the request body is built
by python and reaches curl on its standard input. The server process does not inherit
`AMBARI_ADMIN_PASSWORD`. When the server refuses the password (its password policy,
`security.password.policy.regexp`), turns `admin` / `admin` away from an account the database shows
enabled and not locked out, the API does not answer within `AMBARI_ADMIN_PASSWORD_TIMEOUT`, or the
database cannot be read, the entrypoint stops the server and exits non-zero with the reason, rather
than leave it serving `admin` / `admin`.

### Stopping

On SIGTERM the entrypoint signals the server's JVM and waits up to `AMBARI_STOP_TIMEOUT` seconds
for it to exit, then kills it. That is what lets the server's shutdown hooks run: the KDPS view lets
the work in progress finish and Jetty closes its connections. The pod's
`terminationGracePeriodSeconds` must be longer than `AMBARI_STOP_TIMEOUT`, or Kubernetes kills the
container first; the `ambari` chart sets 90 and 50.

### Logs

The container's output carries `ambari-server.log` and the security audit log,
`ambari-audit.log` (sign-ins, refused access, changes to users, groups and privileges). Audit lines
are prefixed with `[ambari-audit] ` so the cluster's log collection can route them on their own. The
files under `/var/log/ambari-server` live in the container and go with it unless a volume is mounted
there: the pod logs, collected by the cluster's log stack, are the record.

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
- **A Route or Ingress** to port 8080 (or 8443 once TLS is configured), behind a readiness check
  that requires `/tmp/admin-bootstrapped` (see above).
- **RBAC for the ServiceAccount.** This is the part that is easy to under-scope: KDPS installs Helm
  releases, so its ServiceAccount needs create/update/delete on everything those charts contain, in
  every namespace it deploys to. In practice that means a Role (or ClusterRole) covering workloads,
  services, configmaps, secrets, PVCs, service accounts, RBAC objects and the CRDs the charts use.
  Start namespace-scoped and widen deliberately; do not reach for `cluster-admin` because it is
  quicker.

### Known gaps to close during bring-up

- TLS is not configured; the server listens on 8080. Terminate at the Route, or run
  `ambari-server setup-security` and mount a keystore.
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
