# Service Definitions (KDPS services)

Service descriptors live under `src/main/resources/KDPS/services/<NAME>/service.json` plus optional configs in `configurations/*.json`.

Key fields:
- label, chart, version, pattern, secretName
- dependencies: map of dependency charts and CRDs
- mounts: list of mount specs { key, defaultMountPath, supportedTypes, defaults }
- variables: [{ name, from|template }]
- bindings: targets to project variables/form data into Helm values. Supports `skipIfVarEmpty`, `skipIfExists`, `kind` (volume/mount), templates, and form/var sources.
- endpoints: templated URLs for UI display
- ranger, requiredConfigMaps, dynamicValues: passed through to backend
- form: dynamic form fields (types: string, number, boolean, select, password, group, service-select, k8s-discovery). `defaultValue` seeds form values. Conditions hide/show fields.

Propagation rules:
- Form values → variables (per `variables`).
- Bindings run on the form + variables to build Helm values and preview.
- `excludeFromValues` on form fields prevents sending that field to values.yaml.
- `skipIfVarEmpty`/`skipIfExists` let you avoid overwriting user input or injecting empty data.

Example (ServiceMonitor label):
- Form has `monitoring.release`.
- Binding `service-monitor-label` sets `serviceMonitor.labels.release` from that form field with `skipIfExists: true`, so user overrides survive.

Add a new service:
1) Create `service.json` with chart, dependencies, mounts, variables, bindings, endpoints, form.
2) Optional config files in `configurations/` if needed by backend.
3) Restart/rebuild; StackDefinitionService will expose it at `/stack/services`.
