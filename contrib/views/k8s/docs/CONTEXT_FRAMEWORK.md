# Platform Context Framework

A definition-driven framework that lets KDPS services integrate with ODP platform services
(Atlas, Ranger, Kerberos, Hive, OIDC, …) **without hardcoding** connection fields or
integration logic. Everything is configurable so downstream companies can wire their own
components into the open-source view.

Three pillars:

1. **Capability schema fragments** — declare *what a context can provide*.
2. **`requiresContext` (service.json)** — declare *what a service needs* from its context (drives dynamic requiredness).
3. **`platformOps` (service.json)** — declare *what integration operations to run*, invoked against the resolved context (generalizes the previously-bespoke Ranger/Atlas steps).

See [PLATFORM_CONTEXTS.md](PLATFORM_CONTEXTS.md) for the managed-vs-external context model this builds on.

---

## 1. Capability schema fragments

One fragment file per capability under `KDPS/contexts/capabilities/<capability>.json`. The
loader merges every fragment in that directory into the context schema. **A company adds a
capability by dropping a new fragment file** — no code change.

```jsonc
// KDPS/contexts/capabilities/atlas.json
{
  "capability": "atlas",
  "label": "Apache Atlas",
  "order": 10,
  "fields": [
    { "name": "atlasUrl",  "label": "Atlas URL", "type": "string",
      "managedResolver": "atlas.url" },
    { "name": "atlasAuthMode", "label": "Authentication", "type": "enum",
      "options": ["basic","kerberos","ldap","none"], "managedResolver": "atlas.authMode" },
    { "name": "atlasAclMode", "label": "Authorization", "type": "enum",
      "options": ["ranger","simple"], "managedResolver": "atlas.aclMode" },
    { "name": "atlasRangerServiceName", "label": "Atlas Ranger service repo",
      "type": "string", "managedResolver": "atlas.rangerServiceName" },
    { "name": "federationUser", "label": "Federation / bot user", "type": "string" },
    { "name": "federationPassword", "label": "Federation / bot password",
      "type": "password", "secret": true }
  ]
}
```

Field attributes:

| attr | meaning |
|---|---|
| `name` | field id within the capability (referenced as `<capability>.<name>`) |
| `type` | `string` \| `password` \| `enum` \| `boolean` |
| `secret` | `true` → stored encrypted in instance data, never returned by the API |
| `managedResolver` | key the **managed** resolver uses to fill this field live from Ambari. Fields *with* a resolver are auto-filled for managed contexts and only collected on **external** contexts. Fields *without* one (e.g. an external bot password) are always operator-supplied. |
| `appliesTo` | optional `EXTERNAL` \| `MANAGED` to scope a field to one kind |
| `default` | default value in the edit form |

**Managed context** = the schema is used only to know which `managedResolver`s to run; nothing
is stored. **External context** = the edit page renders the merged schema and stores the
values (+ encrypts `secret` fields).

---

## 2. `requiresContext` (service.json)

A service declares what it needs from whatever context it deploys against:

```jsonc
"requiresContext": [
  { "capability": "atlas",
    "fields": ["atlasUrl","atlasAuthMode","atlasAclMode","atlasRangerServiceName"],
    "when": "atlasFederation.enabled" },
  { "capability": "atlas",
    "fields": ["federationUser","federationPassword"],
    "when": "atlasFederation.enabled", "appliesTo": "EXTERNAL" }
]
```

- `when` — a dotted form-value path; the requirement is active only when truthy (so
  federation fields are required only when the toggle is on).
- `appliesTo` — `EXTERNAL` means managed contexts auto-satisfy it (resolved from Ambari), so
  it's required only when an external context is selected.

**Dynamic requiredness** is computed two ways:
- **Wizard** — when deploying service S against context C with capability requirement R
  active, the context selector validates C satisfies R; a managed C auto-satisfies resolvable
  fields, an external C must have them filled (the operator is prompted inline).
- **Deploy-time gate** — the dispatcher re-validates the resolved context satisfies every
  active `requiresContext` before running `platformOps`, failing early with a precise message
  rather than mid-integration.

---

## 3. `platformOps` (service.json)

Declarative post-deploy integration steps, each invoking a **registered op primitive**
against the resolved context. Generalizes the previously-bespoke gates
(`ranger-plugin-settings`, `ranger-tagsync-settings`, `postDeploy.atlasFederation`).

```jsonc
"platformOps": [
  { "op": "atlas.federation", "when": "atlasFederation.enabled" },
  { "op": "ranger.tagsync",    "when": "ranger.tagSync.enabled" }
]
```

v1 op registry (the three generalized today):

| op | does | managed | external |
|---|---|---|---|
| `ranger.policyGrant` | grant a user access on a Ranger service repo | delegates to Ambari `ranger_policy` server action (no password) | view-side Ranger REST with context creds |
| `atlas.federation` | provision OM federation user + Ranger grants + OM REST register | Atlas URL/auth from Ambari; bot creds from KDPS secret | Atlas URL/auth + bot creds from context |
| `ranger.tagsync` | register a TagSync source | uses Ambari-resolved Ranger | uses context Ranger |

Each op resolves what it needs from the `ResolvedContext`, so the same service.json works
against a managed or external context unchanged — the context is the only thing that varies.

A new op type is registered server/view-side and then becomes invocable from any service.json
— the direct analogue of ODP's Ranger-v2 functions callable from any `ODP/xx/services/xxx`.

---

## Resolution flow (deploy)

```
submitDeploy
  ├─ resolve platform context (formValues.platformContextId, default = managed)
  ├─ validate active requiresContext satisfied (else fail early)
  └─ for each active platformOp: dispatch op(resolvedContext, values, params)
                                   └─ managed → Ambari server action / live resolution
                                      external → view-side REST with context creds
```
