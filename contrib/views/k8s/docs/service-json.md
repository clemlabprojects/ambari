<!---
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Service Definition (`service.json`) – Developer Guide

This document describes how to author a KDPS `service.json` so the Ambari K8S view can render the install wizard, collect values, and drive Helm deployments.

## Top-level layout
- `label`: Human-friendly name.
- `chart`: Helm chart name (without repo prefix).
- `repoId`: Default Helm repo id to use (must exist or be discoverable).
- `version` (optional): Chart version pin.
- `dependencies` (optional): Array of child artifacts (e.g., webhooks) to deploy before the main chart.
- `mounts`, `requiredConfigMaps`, `dynamicValues`, `ranger` (optional): See “Advanced features”.
- `retiredValues` (optional): Values an earlier version of the definition wrote and this one no longer does, keyed by
  dotted path, with the reason. An upgrade or a redeploy starts from the release's values, so the wizard and the server
  remove these from every deploy (a chart upgrade from the Releases page and a catalog saved on the Trino Catalogs page
  included); for a list of configuration lines, `{ "reason": ..., "linesStartingWith": ["key="] }`
  removes only those lines. Keys starting with `_` are comments. Use it when a binding stops copying a secret somewhere
  visible (a plain environment variable, a line of a configuration file kept in a ConfigMap). A value that only moves
  takes a `valueAliases` entry as well (old path to new path): the server applies the aliases first, so a redeploy
  without the wizard keeps it (the Superset SECRET_KEY moved from `extraEnv` to `extraSecretEnv` that way).
- `secretChartVersions` (optional): The chart version from which the chart keeps a secret in a Kubernetes Secret, keyed
  by the dotted path that carries it (a value, a form field, or a value the security profile adds):
  `{ "range": ">=0.12.49", "label": "the security profile's LDAP bind password" }`. With an older chart, a deploy from
  the wizard that carries it is refused (the operator picks a later chart under 'Version (override)'), and a resync or a
  security refresh writes a warning into its operation log. Keys starting with `_` are comments. Use it rather than
  raising `requiredChartVersion`, which would refuse the resync of every release still on an older chart.
- `openshiftScc` (optional): OpenShift security context constraints the release's service accounts need; see “OpenShift SCCs”.
- `generatedSecrets` (optional): Secrets of credentials KDPS creates for the release before Helm installs it (an
  administrator password, the passwords of bundled databases). See "Generated credentials" below.
- `form`: Array of form fields shown in the wizard.
- `variables`: Values derived from the form (and other sources) by `renderConfig`.
- `bindings`: Mapping rules that push variables/mounts into Helm values.
- `renderConfig`: Optional templates or additional transforms.

## Form field types
Basic types: `string`, `number`, `boolean`, `select`, `group` (for nesting).

Discovery/select types:
- `service-select`: Pulls options from Ambari (`serviceType` required, e.g. `"HIVE_METASTORE"`).
- `hadoop-discovery`: Alias of `service-select` for Hadoop services.
- `k8s-discovery`: Pulls options from Kubernetes by label (`lookupLabel` required).
- `monitoring-discovery`: Calls `/discovery/monitoring/prometheus` to auto-fill monitoring namespace/release.
- `secret-discovery`: Lists Secrets, by `lookupLabel` or, without one, the Secrets of the release namespace. With
  `"multiple": true` several can be picked and the value is their list (Trino's catalog credential Secrets).

Other field properties:
- `required`: Boolean validation.
- `defaultValue`: Initial value.
- `excludeFromValues`: If true, the field value is **not** sent to Helm; use for helper/lookup fields.
- `sensitive`: For a text field that holds a secret without being typed `password` (an S3 access key, free-text
  properties that may carry a connection password). Like a `password` or `content` field (a pasted file such as a TLS
  private key), its value is never logged and is not kept with the deploy's operation once it is planned.
- `seedFromValue`: For an `excludeFromValues` field whose effect is a chart value (a switch or an input that bindings copy into the values): the values path holding it, e.g. `"ranger.tagSync.enabled"`. On an upgrade the wizard fills the field from the deployed release's value at that path instead of the field's default, and the auto-enable defaults leave it alone, also when the release has no value there (it then runs the chart default), so an unchanged upgrade re-sends what runs.
- `seedUnlessEquals`: With `seedFromValue`, a `${path}` template of the value the chart gets when the field is blank (e.g. `"https://${ingress.host}/api"`). A deployed value equal to it is not copied, so the field stays blank and the derived value keeps following its inputs.
- `seedFromValues`: Value paths an upgrade fills the field from when it is blank, first non-blank wins. For an
  excluded field whose value a binding copies elsewhere: the Superset SECRET_KEY is kept out of the values and sent
  in `extraSecretEnv.SUPERSET_SECRET_KEY`, so an upgrade fills it back from there and the key does not change.
- `condition`: `{ "field": "<otherField>", "value": <expected> }` to show/hide.
- `capability`: On a field or a `group`, shows it on one platform only: `"kubernetes"` (anything but a confirmed
  OpenShift cluster) or `"openshift"` (only once the cluster is confirmed to be OpenShift). On the other platform the
  field is hidden and its value, default included, is left out of the Helm values. Bindings still read it, and a
  binding may still set the same path (OpenMetadata turns its ServiceMonitor on for OpenShift that way). A path that a
  field shown on the platform declares as well is kept.
- `keepWhenHidden`: With `capability`, still sends the value where the field is hidden: for a field the wizard fills
  in by itself (the ingress class it detects), or a value the chart uses on both platforms (Trino's scrape interval).
- `options`: For `select`.
- `placeholder`: Hint shown in an empty `string` field.
- `pattern`, `validationHint`: A regular expression the whole value must match, and the message the field shows
  while it does not (the field's `help` then goes under the input). Every `releaseName` field declares Helm's rule
  for release names (at most 53 lower-case letters, digits, `-` and `.`, starting and ending with a letter or digit).
  The wizard only warns: the server refuses an invalid release, namespace, chart or chart version on its own.
- `omitWhenBlank`: If true and the field is left blank, the key is left out of the Helm values so the chart's own
  default applies, instead of sending an empty string an older chart may not understand.
- `resourceCheck`: `{ "kind": "javaHeap" | "memoryRequest" | "cpuRequest", "limitField": "<otherField>" }` compares
  the value with the limit held by another field. `javaHeap` refuses a Java heap size Java cannot read (`6Gi`,
  `5.5G`) or one of 85% of the memory limit or more (blank passes); `memoryRequest` / `cpuRequest` refuse a value
  that is not a Kubernetes quantity or is higher than its limit. The field shows the error inline and the wizard
  refuses the deploy, YAML editor included, until it is fixed. The field's `help` is shown under the input.
  A `javaHeap` check can add `"blankSizedByChartFrom": "<chart version>"`, the first chart version that sizes a
  blank heap from the memory limit itself. When the deploy targets an older chart (Upgrade / Config keeps the
  release's chart version, or a Version override names an older one) or a version the wizard cannot read, a blank
  heap is sent as 75% of the memory limit instead of being left to that chart's own fixed default.
  Blank values are resolved on the final values, after the YAML editor is merged: a key cleared in the editor
  (`""` or `key:`) is treated like a blank field, and a heap filled in is sized from the memory limit actually sent.
  As a last guard, the deploy is refused if an empty heap would still reach a chart older than that version.

The backend model (`model/FormField.java`) must declare every property the wizard reads: properties it does not
declare are dropped from the `/services` JSON and never reach the UI.

### Example (monitoring + Hive override)
```json
{
  "name": "monitoring.discovery",
  "label": "Discover monitoring stack",
  "type": "monitoring-discovery",
  "excludeFromValues": true
},
{ "name": "monitoring.namespace", "type": "string", "defaultValue": "monitoring" },
{ "name": "monitoring.release",   "type": "string", "defaultValue": "kube-prometheus-stack" },
{
  "name": "ui_hive_metastore_uri",
  "type": "service-select",
  "serviceType": "HIVE_METASTORE",
  "excludeFromValues": true
},
{
  "name": "ui_hive_metastore_uri_override",
  "type": "string",
  "defaultValue": "",
  "help": "If set, overrides the discovered metastore URI."
}
```
Frontend behavior: selecting discovery fills `monitoring.namespace/release`; typing the Hive override also updates the base field so bindings pick it up.

## Variables (`variables` section)
Defines named vars used by bindings/templates.
- `name`: Variable name.
- `from`: Source. Common sources:
  - `{ "type": "form", "field": "<formFieldName>" }`
  - `{ "type": "template", "template": "http://${monitoring.release}.${monitoring.namespace}.svc:9090" }`
  - `{ "type": "mountPath", "mountKey": "<key>", "suffix": "/data" }`
  - `{ "type": "lookup", "path": "<jsonPath>" }` (when pulling from discovery objects)
- Optional `defaultValue`.

## Bindings (`bindings` section)
Push variables/mounts into Helm values.
- `role` (optional): Scope to specific chart role (e.g., coordinator).
- `field` (optional): Connects to mount bindings (see Mounts).
- `targets`: Array of mutations:
  - `path`: JSON path in values (supports `[]` for append).
  - `op`: `set` (default), or special `kind` for volumes/mounts.
  - `value`: Literal value.
  - `from`: Source object (same shape as in variables).
- `skipIfVarEmpty`: Skip entire binding if the named variable is empty/null. Useful for optional catalogs (e.g., Hive).

### Example (Hive catalog only when URI is set)
```json
{
  "name": "hive-catalog",
  "skipIfVarEmpty": "hiveMetastoreUri",
  "targets": [
    {
      "path": "additionalCatalogs.hive",
      "op": "set",
      "from": {
        "type": "template",
        "template": "connector.name=hive\nhive.metastore.uri=${hiveMetastoreUri}\nhive.security=allow-all\n"
      }
    }
  ]
}
```

## Mounts
Declarative PVC/volume requests the backend can materialize.
- `mounts` array entries typically include `key`, `size`, `class`, `mode`.
- Bindings can reference mounts using `mountKey` and `kind: volume` or `kind: mount`.
- `mountKey` + `suffix` lets you build paths relative to the created mount.

## Dependencies
Use `dependencies` for pre-install components (e.g., webhooks). The backend will run them before the main deploy.

## Ranger
`ranger` object is parsed by the backend to inject Ranger-related overrides (service/user/policy configs). Keep the structure intact; the backend merges it during deployment.

A `ranger-tagsync-settings` entry marks a service that can register itself as a Ranger TagSync source (OpenMetadata). The registration runs only when the release's values turn it on at `enabled_helm_prop`: the wizard sends the `ranger` block for every release attached to a platform context, so the block alone never means "register". With the switch off, on a platform KDPS configures, the deploy still runs the step as a check ("Ranger: check TagSync configuration") that only reads `ranger-tagsync-site` and warns about what earlier KDPS versions left there (the Atlas tag source turned off while Atlas is installed, an OpenMetadata source with a bot token stored in clear); it writes nothing and restarts nothing. With the switch on, a deploy is refused before Helm runs unless the values at `airflow_enabled_helm_prop` are `true` and `endpoint_helm_prop` is set. The check is skipped when the registration would not run: a GitOps deploy, or a platform context with auto-configuration off. The registration never changes the cluster's other tag sources (Atlas included; it warns when the Atlas source is off while Atlas is installed, also when it cannot go on), and stores the OpenMetadata bot token as a password property (Ambari's Ranger service then keeps it on the Ranger TagSync host in a credential store, not in `ranger-tagsync-site.xml`, which Ranger TagSync prints in its log on each start). OpenMetadata creates the bot with a token that never expires and does not let KDPS rotate it: such a token is registered with a warning that says how to replace it with one that expires; an expired token is refused. It writes only when something changed, and restarts Ranger TagSync only right after it wrote: Ambari does not say which configuration change a pending restart is for, so a pending restart is reported, not made.

## OpenShift SCCs
`openshiftScc` lists `{ "scc": "<name>", "serviceAccounts": [...] }` entries for pods that the default `restricted-v2` SCC refuses (a pinned UID/GID, root). Name only the service accounts that need the SCC, never the whole project; `{{releaseName}}` is expanded. Ignored on plain Kubernetes. On OpenShift:
- A direct deploy first checks whether each service account may already use its SCC (LocalSubjectAccessReview, then PodSecurityPolicySubjectReview for grants in the SCC's own users/groups). When all may, nothing is written.
- Otherwise it grants them only when the account may (`bind` on ClusterRole `system:openshift:scc:<scc>`, or `use` of the SCC, in the project), through one RoleBinding per release and SCC: `kdps-scc-<scc>-<release>` (hashed to 63 characters), labelled `app.kubernetes.io/managed-by=ambari-kdps`, `ambari.clemlab.com/release` and `ambari.clemlab.com/scc`. It only ever adds subjects, never deletes, and leaves the shared `kdps-scc-<scc>` binding of earlier versions alone.
- A service account that may use its SCC only through a binding KDPS made for another release, or the shared binding of earlier versions, gets the release's own binding as well when the account may grant it. When it may not, the deploy goes on and its log warns, naming that binding and the `oc adm policy add-scc-to-user` command that gives the release a grant of its own.
- When the account may not, the deploy stops with both remedies: the platform team's `oc adm policy add-scc-to-user` command, or the one-time project grant that lets the account bind the SCC ClusterRoles (ClusterRole `kdps-grant-scc`).
- A GitOps deploy started from KDPS runs the same preparation as a direct deploy (AMBARI-742): it checks, grants when the account may, and otherwise stops before anything is committed to Git.
- Uninstall deletes the release's labelled RoleBindings once the release is gone, and lists `oc adm policy remove-scc-from-user` commands for grants KDPS did not make. The other KDPS releases of the project (from their recorded service) keep what they use: their service accounts are never in those commands, the shared `kdps-scc-<scc>` binding is suggested for deletion only once none of them depends on it, and a binding of the uninstalled release that is another release's only grant is first handed over to that release's own binding, when the account may grant it, or else kept, with the commands that grant it to that release and then delete it.

## Dynamic values and required ConfigMaps
- `dynamicValues`: lets you generate values server-side based on templating/external discovery.
- `requiredConfigMaps`: instructs the backend to create/update ConfigMaps before deploy.

## Discovery and bootstrap notes
- Monitoring discovery/auto-bootstrap: The backend will attempt to ensure kube-prometheus-stack exists (using configured/first repo or default Harbor settings). Discovery returns `namespace`, `release`, `url`, plus `state/message` of the bootstrap.
- Service discovery (Ambari): `service-select` with `serviceType` fetches from Ambari APIs. Ensure `client.api.url`/`ambari.cluster.preferred` are set if auto-discovery fails.

## Excluding helper fields from values
Any field with `excludeFromValues: true` is not sent to Helm. Use this for lookups (service-select, monitoring discovery) and helper inputs. Bindings should reference the desired variable or a sibling field that you populate from the helper.

A variable reads such a field by its own name (`"from": {"type": "form", "field": "<field name>"}`), not by the chart path the binding writes to: the form state holds the field under its name. Give it a `seedFromValue` when a binding copies it into the values, or an upgrade shows the default and overwrites what runs.

## Replayable actions
`replayableActions` lists post-deploy steps the operator can run again from the release row on the Releases page (`key`, `label`, `endpoint`). Add `"warnUnlessValueTrue": {"path": "<values path>", "message": "..."}` to ask the operator to confirm first when that value of the deployed release is not `true` (e.g. re-registering Ranger TagSync on a release that has it turned off).

## Secrets
A password or key must reach the pods from a Kubernetes Secret, never from a ConfigMap or a plain `value:` of an
environment variable: anyone allowed to read ConfigMaps or Deployments in the namespace (the OpenShift `view` role)
could read it there. Bind such fields to a values key the chart keeps in a Secret (Superset `extraSecretEnv`, Trino
`polarisCredential`, the security profile's `global.security.auth.*` credentials), mark the field
`excludeFromValues` when the chart does not read it under its own name, and add `seedFromValues` so an upgrade keeps
it. Do not template a secret into a configuration line (`additionalConfigProperties[]`): reference an environment
variable read from a Secret instead (`${ENV:NAME}` in Trino). When only a later chart keeps the value in a Secret,
declare that version in `secretChartVersions`, and say so in the field's help.

### Generated credentials
A chart default such as admin/admin, or a database password written in the chart's values, is the same on every
release. Declare such credentials in `generatedSecrets` instead: before the Helm install (or the GitOps commit, or a
chart upgrade from the Releases page), KDPS creates each Secret in the release namespace with random values (letters
and digits), reads them back on every later deploy, and passes only the Secret's name to the chart. The Secret is not
part of the Helm release, so an uninstall leaves it and a reinstall on the kept volumes finds the passwords its
databases were initialized with.

```json
"generatedSecrets": [
  {
    "key": "database",
    "secretNameTemplate": "{{releaseName}}-superset-db",
    "label": "the passwords of the Superset PostgreSQL database",
    "chartVersion": ">=0.12.50",
    "dataVolume": "data-{{releaseName}}-postgresql-0",
    "keys": {
      "password": {
        "adoptFrom": [{ "secret": "{{releaseName}}-postgresql", "key": "password" }],
        "keptByVolume": true,
        "formerDefault": "superset"
      },
      "postgres-password": { "adoptFrom": [{ "secret": "{{releaseName}}-postgresql", "key": "postgres-password" }] },
      "connection": { "template": "postgresql://superset:${password}@{{releaseName}}-postgresql:5432/superset" }
    },
    "values": { "postgresql.auth.existingSecret": "{{secretName}}" },
    "notice": "..."
  }
]
```
- `secretNameTemplate`: the Secret's name (`{{releaseName}}`, `{{namespace}}`). Pick a name the chart does not use.
- `keys`: each key gets a random value (`length`, 32 by default) the first time, unless `adoptFrom` finds one first:
  the key of another Secret, e.g. the one the chart created for its database before it read this one, so a release
  installed before keeps the password its database runs with. A `template` key is made of the others instead
  (`${key}` is the URL-encoded value of that key) and follows them.
- `dataVolume`: the database's volume claim. The database keeps the passwords it was initialized with, so when the
  claim exists and a key is found neither in the Secret nor where `adoptFrom` points:
  - a `keptByVolume` key (the password the release signs in with) takes its `formerDefault`, the value the chart used
    to set for every release before it read the Secret: a release installed before, uninstalled (which deletes the
    chart's own Secret) and installed again still runs with it. Without a `formerDefault` the deploy is refused: a new
    password would lock the release out of its database. The operation log warns, on every deploy, while the Secret
    holds a `formerDefault`: anyone who reads the chart knows it.
  - any other key (e.g. `postgres-password`, which the release never signs in with) is generated, with a warning that
    the database keeps the value it was initialized with.

  When KDPS cannot read the claim (its account may not get `persistentvolumeclaims` in the namespace, or the API did
  not answer), it cannot tell a new install from a database initialized before: a `keptByVolume` key found nowhere
  refuses the deploy, and any other key is generated with a warning.

  A refused deploy, like one whose Secret cannot be written, runs none of its steps: its operation ends failed with
  the reason, and it cannot be resumed. Deploy again once the cause is fixed.
- `values`: chart values the deploy sets (dotted paths, list indexes allowed for lists the chart does not set itself):
  `{{secretName}}`, and `{{generated}}`, e.g. to set the password of an account created before
  (`init.adminUser.resetPassword`). It is `true` from the deploy that generated a value until a deploy that carried it
  succeeds (the Secret carries the annotation `ambari.clemlab.com/pending-apply` meanwhile), so a deploy that failed,
  was refused or waits for its GitOps merge does not leave the chart's default password in place. Later deploys get
  `false` and leave the account alone. Use it only when the chart sets the value on every deploy that carries it and
  the application never gives the account its default back by itself. Otherwise set the value to `true`, so every
  deploy sets it: OpenMetadata's administrator Job runs in basic authentication only, and OpenMetadata clears the
  administrators' passwords while it runs with another mode, then gives them its default once basic authentication
  is back.
- `chartVersion`: the chart versions that read the Secret. With an older chart nothing is created and the operation
  log warns that the release keeps the credentials of its chart.
- `when`: a values path that must be true, e.g. `dependencies.enabled` for a bundled component.
- `notice`: what the operator is told in the operation log and when the deploy ends (`{{secretName}}`,
  `{{namespace}}`, `{{readCommand}}`, the command that prints the first key), e.g. where to read an administrator
  password. Never put a value in it.

## Common patterns
- **Conditional display:** Use `condition` to hide/show fields based on another field’s value.
- **Optional catalogs/secrets:** Combine `variables` + `skipIfVarEmpty` to avoid emitting empty sections.
- **Overrides vs. discovery:** Provide a discovery field plus a manual override; in bindings, prefer the override when non-empty.
- **Mount-backed paths:** Use `mountKey` and `mountPath` sources to build consistent data/log paths without hardcoding.

## Testing checklist
- Form renders without React hook order issues (watch conditions).
- Submit payload includes all required fields; helper fields marked `excludeFromValues` don’t leak.
- Bindings produce the expected `values.yaml` (check the preview in the wizard).
- Optional sections disappear when inputs are empty (`skipIfVarEmpty`).
- Discovery endpoints reachable (Ambari/K8s/monitoring); handle offline cases gracefully.

## Adding a new service
1. Copy an existing `service.json` as a base.
2. Set `label`, `chart`, `repoId`, and defaults.
3. Define `form` fields (group logically).
4. Map `variables` from form/discovery.
5. Add `bindings` to populate Helm values; use `skipIfVarEmpty` for optional pieces.
6. Declare `mounts`, `dependencies`, `ranger`, `requiredConfigMaps`, `dynamicValues` if needed.
7. Verify with the wizard preview and a dry-run install.
