# Service Definition (`service.json`) – Developer Guide

This document describes how to author a KDPS `service.json` so the Ambari K8S view can render the install wizard, collect values, and drive Helm deployments.

## Top-level layout
- `label`: Human-friendly name.
- `chart`: Helm chart name (without repo prefix).
- `repoId`: Default Helm repo id to use (must exist or be discoverable).
- `version` (optional): Chart version pin.
- `dependencies` (optional): Array of child artifacts (e.g., webhooks) to deploy before the main chart.
- `mounts`, `requiredConfigMaps`, `dynamicValues`, `ranger` (optional): See “Advanced features”.
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

Other field properties:
- `required`: Boolean validation.
- `defaultValue`: Initial value.
- `excludeFromValues`: If true, the field value is **not** sent to Helm; use for helper/lookup fields.
- `condition`: `{ "field": "<otherField>", "value": <expected> }` to show/hide.
- `options`: For `select`.

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

## Dynamic values and required ConfigMaps
- `dynamicValues`: lets you generate values server-side based on templating/external discovery.
- `requiredConfigMaps`: instructs the backend to create/update ConfigMaps before deploy.

## Discovery and bootstrap notes
- Monitoring discovery/auto-bootstrap: The backend will attempt to ensure kube-prometheus-stack exists (using configured/first repo or default Harbor settings). Discovery returns `namespace`, `release`, `url`, plus `state/message` of the bootstrap.
- Service discovery (Ambari): `service-select` with `serviceType` fetches from Ambari APIs. Ensure `client.api.url`/`ambari.cluster.preferred` are set if auto-discovery fails.

## Excluding helper fields from values
Any field with `excludeFromValues: true` is not sent to Helm. Use this for lookups (service-select, monitoring discovery) and helper inputs. Bindings should reference the desired variable or a sibling field that you populate from the helper.

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
