# `externalServiceTargets` — declarative external-service auth wiring

When a KDPS-deployed service needs to connect to **another service that is not on
this Ambari cluster** (external Hive, external Ranger, external Atlas, etc.), the
operator has to provide:

1. A URL pointing at the external instance (overriding the Ambari auto-discovery)
2. An authentication mechanism — typically `none`, `basic` (user+password),
   `kerberos`, or `knox-sso`
3. The credentials matching that mechanism — usually a K8s `Secret` they create
   ahead of deploy

`externalServiceTargets` is the service.json schema that declares this contract.
The wizard renders the right UI based on the declared shape; the Java deploy
pipeline reads the operator's choices + Secret data and writes the appropriate
helm overrides. **For ODP-managed services (no URL override set), nothing in
this pipeline fires** — the existing Ambari-driven credential plumbing continues
to handle them automatically.

## Top-level schema

```jsonc
{
  // ...usual service.json fields (name, label, chart, form, bindings, ...)

  "externalServiceTargets": {
    "<targetKey>": {
      "label": "Hive Metastore",
      "discoveryServiceType": "HIVE_METASTORE",         // matches DiscoveryResource branch
      "urlOverrideField": "ui_hive_metastore_uri_override",
      "modeField":        "hive.externalAuthMode",       // form field that holds the chosen mode
      "authModes": {
        "none":     { "label": "No authentication" },
        "basic":    { ... },
        "kerberos": { ... },
        "knox-sso": { ... }
      }
    }
  }
}
```

`<targetKey>` is opaque — it only identifies the entry within this service.json
(e.g. `hive`, `ranger`, `atlas`). The deploy step iterates entries by key, so
keys must be unique within a single service.json.

## Per-mode schema

Every auth mode under `authModes.<mode>` has the same shape:

```jsonc
"basic": {
  "label": "Username + password (basic auth)",
  "secretField":   "rangerTagSync.adminCredsSecret",    // form field operator picks
  "secretKeys":    ["username", "password"],             // required keys in the Secret
  "applyTo": {
    "<helm.path>": "<template>"
  }
}
```

- `label` — human-readable string for the wizard's auth-mode dropdown.
- `secretField` — form field name (must be declared elsewhere in `form` with
  `type: "secret-discovery"`). Operator picks the K8s Secret here.
- `secretKeys` — keys required in the Secret. Missing keys at deploy time raise
  `IllegalStateException` with a clear "Secret X is missing key Y" message.
- `applyTo` — helm-path → template map. The deploy pipeline interpolates each
  template against the resolver context and writes the result as a helm
  `--set` override.

### `none` mode

For services that allow unauthenticated access (lightweight dev). Only `label`
is required; `secretField`/`secretKeys`/`applyTo` are all absent.

### `knox-sso` mode

For services fronted by Apache Knox. The Secret holds the SSO cookie token
issued by Knox; the `applyTo` block maps it onto the chart's expected paths.

```jsonc
"knox-sso": {
  "label": "Knox SSO",
  "secretField": "ranger.knoxSecret",
  "secretKeys":  ["sso-token"],
  "applyTo": {
    "ranger.knox.sso.cookieValue": "{{secret.data.sso-token | b64decode}}",
    "ranger.knox.sso.enabled":      "true"
  }
}
```

## Template language for `applyTo` values

A minimal interpolation engine with two refs and two filters:

| Token | Resolves to |
| --- | --- |
| `{{secret.name}}` | the K8s Secret's `metadata.name` chosen by the operator |
| `{{secret.data.<key>}}` | the **base64-encoded** value of `Secret.data.<key>` |
| `{{form.<dottedPath>}}` | the form value at `<dottedPath>` (excludeFromValues fields included) |

Filters (post-fix, piped):

| Filter | Effect |
| --- | --- |
| `\| b64decode` | base64-decode the string value (typically applied to `secret.data.X` so the chart gets the cleartext) |
| `\| trim` | strip leading/trailing whitespace (klist-copied principals often carry trailing newlines) |

Example:

```jsonc
"applyTo": {
  "global.security.kerberos.keytab.secretName":     "{{secret.name}}",
  "global.security.kerberos.keytab.secretDataKey":  "keytab",
  "global.security.kerberos.principal":             "{{secret.data.principal | b64decode | trim}}"
}
```

The interpolator preserves literal strings around the templates (e.g.
`"thrift://{{form.ui_hive_metastore_uri_override}}"`).

Unknown tokens resolve to empty string but log at WARN — this keeps simple
typos from blowing up the entire deploy.

## When the pipeline fires

For each entry in `externalServiceTargets`, the deploy pipeline:

1. Reads `urlOverrideField` from `request.formValues`.
2. If **empty or unset** → the entry is treated as Ambari-managed; nothing
   from this entry's `authModes` fires; existing internal-cluster pipeline
   (Ranger auto-registration, Kerberos keytab provisioning, etc.) runs as
   before. **Regression-safe by design.**
3. If **non-empty** → reads `modeField` for the chosen mode. Looks up the
   mode under `authModes`. Reads the chosen mode's `secretField` value (the
   K8s Secret name). Loads the Secret from the release namespace. Validates
   each key in `secretKeys` is present. For each `applyTo` entry,
   interpolates the template and writes the result as a helm override.

Errors that surface to the operator's deploy log (all `IllegalStateException`
caught by the resource layer and returned as `400` with the message in the
response body):

- Mode is referenced but not declared in `authModes`
- `secretField` is required by the mode but blank
- Secret doesn't exist in the namespace
- Required `secretKey` is missing from the Secret
- Template references `{{secret.data.X}}` for a key that isn't in `secretKeys`
  (the deploy bails early instead of writing an empty string into a helm path)

## Concrete example: OPENMETADATA with three external-service targets

```jsonc
"externalServiceTargets": {
  "hive": {
    "label": "Hive Metastore",
    "discoveryServiceType": "HIVE_METASTORE",
    "urlOverrideField": "ui_hive_metastore_uri_override",
    "modeField": "hive.externalAuthMode",
    "authModes": {
      "none": { "label": "No authentication" },
      "kerberos": {
        "label": "Kerberos / SASL GSSAPI",
        "secretField": "hive.externalKeytabSecret",
        "secretKeys": ["keytab", "principal"],
        "applyTo": {
          "global.security.kerberos.keytab.secretName":    "{{secret.name}}",
          "global.security.kerberos.keytab.secretDataKey": "keytab",
          "global.security.kerberos.principal":            "{{secret.data.principal | b64decode | trim}}"
        }
      }
    }
  },
  "ranger": {
    "label": "Ranger Admin (for TagSync)",
    "discoveryServiceType": "RANGER",
    "urlOverrideField": "rangerTagSync.adminUrlOverride",
    "modeField": "rangerTagSync.adminAuthMode",
    "authModes": {
      "basic": {
        "label": "Username + password",
        "secretField": "rangerTagSync.adminCredsSecret",
        "secretKeys": ["username", "password"],
        "applyTo": { /* the TagSync step reads creds via the resolver context directly */ }
      },
      "kerberos": {
        "label": "Kerberos / SPNEGO",
        "secretField": "rangerTagSync.adminKeytabSecret",
        "secretKeys": ["keytab", "principal"],
        "applyTo": { /* same as Hive above, scoped to Ranger paths */ }
      },
      "knox-sso": {
        "label": "Knox SSO",
        "secretField": "rangerTagSync.knoxSecret",
        "secretKeys": ["sso-token"]
      }
    }
  },
  "atlas": {
    "label": "Atlas (for federation)",
    "discoveryServiceType": "ATLAS",
    "urlOverrideField": "atlasFederation.hostPort",
    "modeField": "atlasFederation.externalAuthMode",
    "authModes": {
      "basic": {
        "label": "Atlas local user (basic auth)",
        "secretField": "atlasFederation.existingSecret",
        "secretKeys": ["username", "password"],
        "applyTo": { /* OM Atlas connector pulls creds from the Secret directly */ }
      },
      "kerberos": {
        "label": "Kerberos / SPNEGO",
        "secretField": "atlasFederation.keytabSecret",
        "secretKeys": ["keytab", "principal"]
      },
      "knox-sso": {
        "label": "Knox SSO",
        "secretField": "atlasFederation.knoxSecret",
        "secretKeys": ["sso-token"]
      }
    }
  }
}
```

## Wizard UI contract

The wizard reads `externalServiceTargets` and, for each entry:

- Adds an "External authentication" group below the existing service-select
  + URL override fields
- The group is **hidden** when the URL override value is empty (Ambari-managed
  path)
- When URL override is non-empty, the group renders:
  - An auth-mode dropdown built from the entry's `authModes` keys (using each
    mode's `label`)
  - The chosen mode's `secretField` (already declared elsewhere in the form
    as a `secret-discovery` field), surfaced conditionally

The wizard introduces one new field type: `external-auth-target`. It takes a
single attribute `target` whose value matches a `externalServiceTargets`
entry's key. Place it inside the form section where the corresponding
service-select + URL override live (e.g. `hadoopIntegration`).
