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

## 3. Tag sync on an Ambari-managed context

Ranger's tag import (`importServiceTags`) requires the sys-admin role. The KDPS view never holds the
Ranger admin password, so on a managed context it asks the Ambari server to provision a dedicated
internal user `kdps-tagsync-<release>` (role `ROLE_SYS_ADMIN`) with a password the view generates and
rotates on every deploy. Only that credential lands in the release namespace, as the Secret
`<release>-tagsync-ranger`; `tagSync.ranger.url` comes from the context or from
`admin-properties/policymgr_external_url`. External contexts (CDP, manual) keep using the Ranger
credentials they expose. A context without Ranger is refused at submit.

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
(reset an existing internal user's password and roles), `tagService` (tag service of the repository).

## 7. Known limits

* The TagSync OpenMetadata source maps to one component type (Trino). Tags on a Hive OpenMetadata
  service keep producing an HTTP 400 for the non-existent `<service>_trino` target; harmless.
* Masking policies target exactly one column; Ranger refuses wildcards there.
* KDPS cannot list existing policies on a managed context (no Ranger read credential); use the Ranger
  console for that.
