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

# Ranger policies and tag flows for Trino (KDPS)

How tags set in OpenMetadata or Atlas end up as Ranger rules on a KDPS Trino release, and how an
operator creates Ranger policies from KDPS without opening the Ranger console.

## 1. The chain

```
OpenMetadata tag ──TagSync (OpenMetadata source)──▶ Ranger tag store ──▶ tag policies ──▶ Trino plugin
Atlas classification ──TagSync (Atlas source)──────▶ Ranger tag store ──┘
```

Ranger stores a tagged *resource* with the shape of the component it belongs to. For Trino that is
`catalog / schema / table / column`. OpenMetadata names every asset `service.database.schema.table.column`,
so the mapper that turns an OpenMetadata asset into a Ranger resource only works when the OpenMetadata
service **is a Trino service**: `trino-sec.tpch.sf1.customer.name` becomes
`catalog=tpch, schema=sf1, table=customer, column=name`. A Hive service (`hive-clemlabtest.default.customer`)
would produce `catalog=default`, which matches nothing in Trino.

That is why the OpenMetadata wizard registers the Trino release itself (section 2).

## 2. OpenMetadata wizard — "Enable Trino base ingestion"

Group *Base Metadata Ingestion (Hive / Trino)*:

| Field | Meaning |
|---|---|
| Enable Trino base ingestion | KDPS registers the coordinator selected under *Trino Coordinator* as an OpenMetadata **Trino** service and schedules a metadata ingestion. |
| OM database service name (Trino) | Blank = `trino-<release>` (a release already named `trino…` is used as-is). |
| Trino ingestion schedule | Cron, default every 12 h. |
| Catalog / schema include patterns | Optional regexes. The `system` catalog is always excluded. |

What KDPS does at deploy time:

1. Resolves the KDPS Trino release behind the coordinator Service and refuses at submit when that
   release has no KDPS service user (`kdps.serviceUser.enabled`, chart ≥ 1.43.13) or HTTPS off.
2. Writes the TagSync mapping into the release values: `ranger.tagSync.trinoIngestionServiceFqn`
   (the OpenMetadata service) and `ranger.tagSync.rangerTrinoServiceName` (the Trino release's Ranger
   repository, `ranger.serviceName`, default `<release>-<namespace>`). Ranger TagSync then uploads the
   tags to the right repository instead of a non-existent `<service>_trino`.
3. Grants the `kdps` service user metadata-only rights on the Trino repository through the Ambari
   `ranger_policy` action: `queryid execute`; `catalog show,use`; `schema show`; `table show`;
   `column show` (without it Ranger hides every column and tables arrive without columns);
   `information_schema` and `system.metadata` `select`. No data read is granted.
4. Registers the OpenMetadata service (basic auth with the service user over **verified** HTTPS: the
   coordinator certificate comes from the Ambari internal CA, which the ingestion pods trust) and the
   pipeline, deploys it and triggers the first run.

Releases page → *Re-register Trino ingestion* / *Re-register Hive ingestion* replay these steps from
the deployed values.

The Hive base-ingestion service name defaults to `hive-<cluster>` (the platform context's cluster),
and the Atlas federation target list follows it when left blank.

## 3. The Atlas tag sync account of a Trino release

The tag projector of a Trino release ("Sync Atlas tags to Trino", chart `tagSync`) writes tags into
Ranger with an account of the release's own: `kdps-tagsync-<namespace>_<release>_<hash>`, an
internal Ranger user with a password KDPS generates on every deploy that carries Ranger wiring (below).
The projector runs, and the release gets an account, only when tag sync is on **and** access control is
`Ranger` (`accessControl.type: ranger`), the same test as the chart's CronJob; with access control
disabled, an account an earlier deploy gave the release is taken back.
`<hash>` is a short hash of the KDPS instance (a random id KDPS keeps in its settings), of the
Kubernetes API server URL and of the platform context. Releases of the same name deployed by two KDPS
that use the same Ranger therefore get different accounts, even when both reach their cluster at the
same URL (`https://kubernetes.default.svc` in-cluster, `https://api.crc.testing:6443` on every CRC) and
name their platform contexts alike; and a release moved to another platform context gets another
account even when both contexts reach the same Ranger. The account is recorded on the platform context
whose Ranger it was created on: a deploy naming a context deleted from KDPS falls back to the managed
cluster, and so does its account. The managed cluster is the same context whether a deploy names the
default context or none. The underscores
cannot occur in a namespace or a release name, so no two releases share a name. The account's Ranger
description names the release it belongs to, and KDPS neither resets nor disables an account whose
description names something else.

Only that account reaches the release's project, as the Secret `<release>-tagsync-ranger` (`user`,
`password`); `tagSync.ranger.url` and `tagSync.ranger.existingSecret` point the chart at it. The
platform context's Ranger administrator credentials never leave KDPS. KDPS also records, in the view,
which account it gave each release and on which platform context: that record, not the Secret, decides
what is disabled later, so editing or deleting the Secret in the project changes nothing.

* **Managed context**: the Ambari server, which holds the Ranger administrator, creates or resets the
  account (`ranger_plugin_repository` in user-only mode). `tagSync.ranger.url` comes from the context or
  from `admin-properties/policymgr_external_url`.
* **External context** (CDP, manual) with Ranger administrator credentials: the view creates or resets
  the account in that Ranger directly. With auto-configuration off for the context, KDPS creates no
  account and the deploy is refused unless the values name the operator's own Secret (below).
* **Your own account**: values naming another Secret in `tagSync.ranger.existingSecret` (keys `user` and
  `password`) make KDPS create nothing; it only fills in `tagSync.ranger.url` when it is empty. Give
  that Secret a name other than `<release>-tagsync-ranger`, which KDPS keeps for the accounts it
  creates. A Secret of that name that KDPS did not write (it lacks KDPS's `managed-by: ambari-k8s-view`
  annotation and `app.kubernetes.io/managed-by: kdps` label) is still treated as yours: KDPS neither
  replaces nor deletes it.
* A context without Ranger is refused at submit.

Only a deploy that carries the Ranger wiring of the service definition gives the release its account
and a new password: **Upgrade / Config** on the Releases page, or the install wizard, with a platform
context selected. **Resync / restart**, **Refresh security profile**, **Worker autoscaling** and an upgrade
from the YAML editor carry none: while the values keep tag sync on with KDPS's Secret, the release keeps
its account and password as they are; with tag sync off, or values naming your own Secret, the account
is taken back (below).

**The account is a Ranger administrator.** Ranger lets only `ROLE_SYS_ADMIN` import tags
(`importServiceTags`, and every other tag-store write); it has no narrower role and no per-repository
right for it, so the account holds that role. Anyone who can read Secrets in the release's project can
sign in to the platform context's Ranger with it and do anything a Ranger administrator can, such as
change any policy or create other users. Grant the right to read Secrets in that project only to people
you would make Ranger administrators, or leave tag sync off. A new password on every deploy, and
disabling the account when the release stops using it, end access through this account; they do not
undo what was done with it.

**When KDPS takes the account back.** The account is disabled (status disabled, role `ROLE_USER` only,
a password nobody is told; Ranger keeps it, so its audit trail still names it) and the Secret deleted:

* on **uninstall**;
* on a **deploy that gives the projector no KDPS account**: tag sync turned off, values naming your own
  Secret, or a context with auto-configuration off (the deploy is then refused, and the message says
  what was taken back); the deploy log says what was done;
* when a **first install is refused** after the account was created, or **fails or is canceled before
  the release is installed** (no Helm release, nothing committed to Git), since there is then no release
  to uninstall; resuming the failed deploy gives the account again, with a new password. While a Helm
  call of a canceled deploy still runs, KDPS waits for it (up to 30 minutes) before deciding. A deploy
  interrupted by a restart of the Ambari server is failed when the view starts again, and its account
  taken back the same way. Only the deploy the account was last given to takes it back: when the release
  is deployed again in the meantime, the new deploy keeps it, and takes it back should it end without
  installing the release;
* when a deploy gives the release an account under another name (another platform context, another
  Kubernetes API server URL), the previous one is disabled.

When the account cannot be disabled (Ranger unreachable, no administrator access, an Ambari request
that timed out), the deploy or the uninstall still completes and says which account to disable by hand:
in the deploy log, or in the `tagSyncCleanup` note of the uninstall, shown on the Releases page. KDPS
keeps the account recorded, tries again on the next deploy or uninstall of the release, and says so
again each time until it succeeds. An account on a platform context deleted from KDPS, which KDPS can
no longer reach, or one not found where KDPS created it, is reported once.

**Releases deployed by earlier KDPS versions.** Their `<release>-tagsync-ranger` Secret holds either the
account `kdps-tagsync-<release>` (managed context), or, on an external context, **the context's own
Ranger administrator account and password**. Redeploy each such release with **Upgrade / Config** on
the Releases page, its platform context selected, whatever its tag sync setting: with tag sync on, the
Secret then holds the release's new account; otherwise it is deleted. Either way the old
`kdps-tagsync-<release>` account is disabled, and a copied administrator account is reported in the
deploy log (or in the refusal of a context with auto-configuration off).
Then **change the password of that administrator account in Ranger and on the platform context**:
anyone who could read Secrets in the project may have kept a copy. Uninstalling such a release instead
disables the old `kdps-tagsync-<release>` account, or, for a copied administrator account, deletes the
Secret and gives the same advice in its `tagSyncCleanup` note. The `kdps-tagsync-<release>` account was
shared by every release of that name: redeploy the others too, since it no longer logs in once one of
them has been redeployed. Resync / restart, Refresh security profile and Worker autoscaling leave the
Secret of a release that still syncs tags as it is, and their deploy log says to use Upgrade / Config.
The old `kdps-tagsync-<release>` account is disabled on the Ranger of the platform context the release
was deployed on before the redeploy, even when Upgrade / Config moves it to another context; if that
Ranger has no such account, the deploy log says so, and the account must be disabled by hand where it is.

**Releases uninstalled with an earlier KDPS version.** Uninstalling a release never deleted its
`<release>-tagsync-ranger` Secret before this version, and nothing KDPS does now finds those leftovers
(only a new install of the same name in the same project does). For every Trino release that ever had
tag sync on:

1. List the leftover Secrets in every project, for example `oc get secret -A | grep tagsync-ranger`
   (or `kubectl get secret -A | grep tagsync-ranger`), and delete those of releases that no longer exist.
2. In the Ranger of the managed cluster, disable the `kdps-tagsync-<release>` users (no underscore in
   the name: earlier versions created them) of releases that are no longer installed (Ranger → Settings →
   Users: set them disabled and remove their administrator role).
3. Change the Ranger administrator password of **every external platform context that ever had a Trino
   release with tag sync on**, in Ranger and then on the platform context in KDPS, whether or not those
   releases are still installed: earlier versions copied it into their projects.

## 4. Tag services

Tags flow *into a tag service*, and a tag policy applies to every repository linked to that tag
service. Ranger links a repository created without `tagService` to a default service named `tag`,
which nothing else uses. KDPS therefore links every Trino repository it creates (Ambari path and
direct-REST path) to **the Hive repository's tag service** (`<cluster>_tag` on an Ambari cluster), and
moves an existing repository still sitting on `tag` on its next deploy. `RangerPlugin/tagService` on
the Ambari API overrides the choice.

## 5. Creating policies from KDPS

Releases page → *Ranger policy…* on a Trino release opens a form:

* **Applies to**: a Trino resource (`catalog / schema / table / column`, `*` allowed) or everything
  carrying a **tag** (the tag as Ranger stores it — OpenMetadata `PII.Sensitive` arrives as `Sensitive`).
* **Policy**: allow access (pick access types), mask a column (Ranger mask types; `CUSTOM` takes a
  Trino expression over `{col}`), or filter rows (SQL predicate).
* **User / Groups**: one Ranger user and/or a comma-separated group list (`public` = everyone).

Resource policies are created on the release's repository; tag policies on its tag service, with the
component-prefixed spellings Ranger expects there (`trino:select`, `trino:MASK_HASH`). On a managed
context the request goes through the Ambari `ranger_policy` action; on an external context straight to
the context's Ranger. A policy name that already exists is appended to (for masks and filters, the
item of the same principals is replaced). Trino applies new policies within its refresh interval.

REST equivalent: `POST …/helm/releases/{ns}/{release}/actions/ranger-policy` with the body described
in `CommandService.createReleaseRangerPolicy`.

## 6. Ambari API additions

`POST /api/v1/clusters/{c}/ranger_policy` — `RangerPolicy/policyType` (0 access, 1 mask, 2 row filter),
`maskType`, `maskConditionExpr`, `maskValueExpr`, `rowFilterExpr`, `groups`; `userName` is optional when
`groups` is given; `accessTypes` defaults to `select` for masks and filters.

`POST /api/v1/clusters/{c}/ranger_plugin_repository` — `RangerPlugin/userOnly` (create or reset a user
without touching a repository), `userRoles` (comma-separated, default `ROLE_USER`), `resetPassword`
(reset an existing internal user's password and roles), `tagService` (tag service of the repository),
`disableUser` (with `userOnly`: disable an internal user, leave it only `ROLE_USER` and a random
password; no `pluginUserPassword` needed; a user Ranger does not have completes with `"found": false`;
external users and Ambari's own Ranger administrator are refused), `userDescription` (with `userOnly`:
the description a created user gets; an existing user whose description differs is neither reset nor
disabled).

## 7. Known limits

* The TagSync OpenMetadata source maps to one component type (Trino). Tags on a Hive OpenMetadata
  service keep producing an HTTP 400 for the non-existent `<service>_trino` target; harmless.
* Masking policies target exactly one column; Ranger refuses wildcards there.
* KDPS cannot list existing policies on a managed context (no Ranger read credential); use the Ranger
  console for that.
* The Atlas tag sync account of a Trino release is a Ranger administrator (Ranger has no narrower right
  to write tags), and it is in the release's project: see section 3.
