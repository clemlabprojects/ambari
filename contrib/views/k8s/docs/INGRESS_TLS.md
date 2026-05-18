# Ingress TLS — design + operations guide

How the K8s view provisions TLS leaf certificates for every service it deploys
(Superset, OpenMetadata, Trino, GitLab, …), with the four-mode operator UI on
top of three independent provisioning engines.

## The three layers (mental model)

TLS provisioning has three orthogonal axes:

| Axis | What it answers | Examples |
|---|---|---|
| **Trust root** | Who do clients trust? | Ambari Internal CA / Company root CA (uploaded once) / public ACME / corporate PKI |
| **Issuance engine** | Who mints the leaf? | The view itself (BouncyCastle in-process) / cert-manager / external-secrets + Vault PKI / operator-supplied |
| **Delivery** | How does the leaf land in K8s as a Secret? | View writes it / cert-manager writes it / ESO syncs it from upstream / operator drops it manually |

The wizard collapses this into **four user-facing TLS modes** plus two
"bring-your-own" escape hatches. Adapts to what's installed in the cluster.

## TLS modes

Each service form (`KDPS/services/<NAME>/service.json`) exposes one
`ingress.tlsMode` dropdown. Options whose backing system isn't installed are
hidden automatically (capability discovery — see below).

| Mode | When | Trust | Issuance | Delivery | Auto-renew |
|---|---|---|---|---|---|
| `none` | HTTP-only dev / no auth. Blocked under OIDC. | — | — | — | — |
| `bringYourOwn` | You provide an existing K8s Secret name. | whoever signed it | n/a | operator | no |
| `uploadLeaf` | Paste a leaf cert + key in the form. View creates the Secret. | whoever signed it | view receives PEM | view writes Secret | manual renew |
| `signedByAmbariCA` | Testing — view mints leaf signed by Ambari's internal CA. | Ambari Internal CA (browsers untrusted) | view (BouncyCastle) | view writes Secret | manual renew |
| `signedByCompanyCA` | Production — admin uploads a Company Issuing CA to the PKI registry; view mints leaves from it. | Company Issuing CA | view (BouncyCastle) | view writes Secret | manual renew |
| `signedByClusterIssuer` | **Recommended when cert-manager is installed.** Pick any ClusterIssuer/Issuer (incl. one promoted from a Company CA). | the Issuer's trust root | cert-manager | cert-manager writes Secret | yes (cert-manager native) |
| `sourcedFromExternalSecret` | Sync a leaf from Vault PKI (or any external-secrets backend). | the upstream PKI's | external store (Vault PKI / AWS SM / etc.) | ESO writes Secret | yes (ESO refresh) |

Hidden when:
- `signedByClusterIssuer` → if no `clusterissuers.cert-manager.io` CRD installed.
- `sourcedFromExternalSecret` → if no `externalsecrets.external-secrets.io` CRD installed.

Auto-enforced when:
- An OIDC security profile is selected in Step 1 — wizard sets
  `ingress.enabled=true` and bumps `tlsMode` away from `none` (configurable via
  `securityCoupling.minTlsMode` in service.json).
- Pre-flight at OIDC client registration checks that ingress.tls[] is populated
  or one of the TLS-step types is planned. Refuses to register an https
  redirect URI without TLS.

## Cluster capability discovery

`GET /api/cluster/capabilities` reports cluster facts the wizard adapts to:
```json
{
  "platform": "kubernetes" | "openshift",
  "openshift": { "routeCrd": false },
  "certManager": { "installed": true,  "clusterIssuerCrd": true,  "certificateCrd": true },
  "externalSecrets": { "installed": true, "secretStoreCrd": true, "clusterSecretStoreCrd": true, "externalSecretCrd": true }
}
```
Probed via CRD existence; cached 60 s server-side. The wizard's TLS-mode
dropdown filters by these flags.

## Discovery endpoints

| Endpoint | Returns | Drives |
|---|---|---|
| `GET /api/discovery/cluster-issuers` | ClusterIssuer + Issuer list (Ready=True by default) | `cluster-issuer-discovery` form field |
| `GET /api/discovery/secret-stores` | SecretStore + ClusterSecretStore list with provider type | `secret-store-discovery` form field |
| `GET /api/discovery/secrets?label=...` | K8s Secrets matching a label selector | `secret-discovery` form field (Company CA picker) |

## CA Registry

UI: **Configuration → Certificate Authorities**.

Operators upload Company Issuing CAs once. Each CA can be:
- Used directly by `signedByCompanyCA` (view-internal signing pipeline).
- **Promoted** to a cert-manager `ClusterIssuer` named `clemlab-<caName>`
  (one click in the Certificate Authorities page → "Promote to ClusterIssuer").
  Any service installed via `signedByClusterIssuer` can then pick the
  promoted issuer — same root of trust, different issuance backend.

### REST API (CA registry)

| Method | Path | Body / Returns |
|---|---|---|
| `GET`    | `/pki/cas`                                  | `{ items: [CaEntry, …] }` (no private keys) |
| `GET`    | `/pki/cas/{name}`                           | `CaEntry` |
| `POST`   | `/pki/cas`                                  | `{ caName, caCertPem, caKeyPem, description? }` → 201 |
| `DELETE` | `/pki/cas/{name}`                           | 204 |
| `POST`   | `/pki/cas/{name}/promote-to-cluster-issuer` | `{ clusterIssuerName, ready }` |

Promotion copies the CA Secret into cert-manager's namespace (cert-manager only
reads CA Secrets from its own namespace) and applies a `kind: ClusterIssuer`
with `spec.ca.secretName` referencing it.

### Storage

- Namespace: `ambari-pki` (configurable via view instance property `pki.namespace`).
- One `kubernetes.io/tls` Secret per CA: `<view-instance>-<sanitized-caName>`.
- Labels: `managed-by=ambari-k8s-view`, `ambari.clemlab.com/resource-type=issuing-ca`.
- Validation on upload: PEMs parse, cert has `basicConstraints.CA=true`, not expired, key parses.

## Deploy-time pipeline

The OIDC pre-flight at `OIDC_REGISTER_CLIENT` recognises any of these TLS
sibling steps as proof TLS will be in place:

```
HELM_REPO_LOGIN
  → INGRESS_TLS_SELFSIGN          (signedByAmbariCA / signedByCompanyCA)
  → INGRESS_TLS_CERTMANAGER       (signedByClusterIssuer — writes Certificate CR)
  → INGRESS_TLS_EXTERNAL_SECRET   (sourcedFromExternalSecret — writes ExternalSecret CR)
  → OIDC_REGISTER_CLIENT
  → OIDC_CREATE_SECRET
  → HELM_DEPLOY_DRY_RUN
  → HELM_DEPLOY
```

The chart never knows which mode was used — it just sees `ingress.tls[].secretName`
in values and references it.

### Backend wire shape (HelmDeployRequest)

The wizard sends exactly one of these alongside the chart `values`:

```jsonc
"ingressTlsSelfSign":     { "mode": "ambariInternal"|"companyUploaded", "caName"?, "secretName", "ingressHost", "validityDays" }
"ingressTlsUpload":       { "secretName", "certPem", "keyPem" }
"ingressTlsCertManager":  { "issuerName", "issuerKind", "secretName", "ingressHost", "durationHours" }
"ingressTlsExternalSecret": { "secretStoreName", "secretStoreKind", "remoteKey", "secretName", "ingressHost", "refreshInterval" }
```

Missing required fields → **HTTP 400** at the deploy endpoint with a clear
message (e.g. "INGRESS_TLS_CERTMANAGER: issuerName and at least one DNS name
are required"). The wizard adds a client-side check that surfaces the same
error before submit so the operator doesn't need to wait for the server round-trip.

## TLS state observability

Every Helm release row on the **Helm Releases** page now carries a `TLS`
column populated by `GET /helm/releases/{ns}/{release}/tls`. Per host × Secret:

- Source: `k8s-view-self-signed` / `cert-manager` / `external-secrets` / `external` (inferred from Secret labels/annotations)
- Status: `valid` / `expiring-warning` (≤30d) / `expiring-soon` (≤14d) / `expired` / `no-tls` / `secret-missing` / …
- Issuer, NotAfter, SANs, serial — read from the live K8s Secret's `tls.crt`.

Click **Renew** to dispatch the right action per source:

- `k8s-view-self-signed` → re-runs `INGRESS_TLS_SELFSIGN`, overwriting the Secret in place
- `cert-manager` → patches the `Certificate` CR with `cert-manager.io/issue-temporary-certificate=true` (forces immediate reissue)
- `external-secrets` → patches the `ExternalSecret` with a `force-sync` annotation (triggers ESO refresh)
- `external` → button disabled (operator owns it; expiry still displayed)

## Service.json contract

```jsonc
{
  "label": "Apache Superset",
  "chart": "superset",
  "version": "0.12.29",
  "requiredChartVersion": ">=0.12.29",
  "securityCoupling": {
    "requireIngress": true,
    "tlsProvisioning": "k8s-view",     // "k8s-view" | "chart-managed" | "external"
    "minTlsMode": "signedByAmbariCA"
  },
  "valueAliases": {                     // service.json field-path → chart values.yaml path
    "ingress.ingressClassName": "ingress.className"
  },
  "oidc": [{ ... }],
  "form":      [ ... ],
  "variables": [ ... ],
  "bindings":  [ ... ]
}
```

- `tlsProvisioning: "k8s-view"` — service participates in the four-mode pipeline (default; SUPERSET / OPENMETADATA / TRINO).
- `tlsProvisioning: "chart-managed"` — chart owns its TLS (GITLAB uses `gitlab.global.hosts.https` + cert-manager). Wizard skips the TLS dropdown, OIDC pre-flight skips the `ingress.tls[]` check.

## Renew flow per source

| Source | Renew dispatch | Auto-renew? |
|---|---|---|
| `k8s-view-self-signed` | Re-run `INGRESS_TLS_SELFSIGN` (re-mint + overwrite Secret) | manual via UI |
| `cert-manager` | Patch Certificate CR with `cert-manager.io/issue-temporary-certificate=true` | yes — cert-manager native renewal |
| `external-secrets` | Patch ExternalSecret with `force-sync` annotation | yes — ESO refreshInterval |
| `external` | Button disabled | operator-owned |

cert-manager `Certificate` resources are written with `duration` + `renewBefore`
(default 90d / 15d) so auto-renewal kicks in cleanly. ESO `ExternalSecret`
resources carry the operator-chosen `refreshInterval` (default 1h).

## Audit logging

Every cert event is logged at INFO under `org.apache.ambari.view.k8s`. The
deploy command's per-step messages also surface as event entries in the
operations drawer.

| Event | Logger | Fields |
|---|---|---|
| CA upload / replace / delete | `CaRegistryService` | `caName`, `secret=<ns>/<name>`, `subject`, `notAfter`, `uploadedBy` |
| Promote to ClusterIssuer | `KubernetesService` | `issuerName`, source CA Secret |
| Leaf mint (write Secret) | `WebHookConfigurationService` | `secret=<ns>/<name>`, `host`, `serial`, `notAfter` |
| Apply Certificate CR | `KubernetesService` | `name`, `secretName`, `issuer`, `dnsNames` |
| Apply ExternalSecret CR | `KubernetesService` | `name`, `secretName`, `store`, `remoteKey` |
| OIDC pre-flight refusal | `CommandService` | redirect URI scheme + TLS state, in step failure message |

## OpenShift

Detected via `routes.route.openshift.io` CRD presence in
`/api/cluster/capabilities`. The view ships `Certificate` + `ExternalSecret` +
`kubernetes.io/tls Secret` resources that work identically on OpenShift.

**Out of scope this round**: native `Route` rendering. Charts that target
OpenShift typically expose a `route.enabled` values flag — operators still use
the same TLS dropdown; only the chart side decides Ingress-vs-Route. This is a
chart-author concern; the view's resource shapes are platform-agnostic.

## Operational runbooks

### Onboard a new Company Issuing CA

```bash
openssl req -new -newkey rsa:4096 -keyout acme-issuing.key -out acme-issuing.csr \
  -nodes -subj "/CN=ACME Issuing CA/O=ACME Corp"
# Sign with your offline root → acme-issuing.crt
```

1. Configuration → Certificate Authorities → **Upload Company CA** (name=`acme-issuing-ca`, paste PEMs).
2. Click **Promote to ClusterIssuer** if cert-manager is installed (creates `clemlab-acme-issuing-ca` ClusterIssuer).
3. New installs pick `Sign via cert-manager` → `clemlab-acme-issuing-ca`, or `Sign with Company CA` → `acme-issuing-ca`. Either path uses the same root of trust.

### Rotate a leaf

Releases page → TLS column → **Renew** button. Dispatches based on source.

### Disaster recovery (CA key leak)

1. Generate a fresh issuing CA from your root, upload under a NEW name.
2. Re-promote to ClusterIssuer.
3. Reinstall each service (helm uninstall + install) picking the new ClusterIssuer.
4. Delete the compromised CA from the registry once no release references it.

## Configuration

View instance properties:

| Property | Default | Effect |
|---|---|---|
| `pki.namespace` | `ambari-pki` | Where Company CAs are persisted as Secrets. |

Per-service in service.json:

| Field | Default | Effect |
|---|---|---|
| `securityCoupling.requireIngress` | `true` | Auto-enable ingress when auth profile is selected. |
| `securityCoupling.tlsProvisioning` | `"k8s-view"` | Participation in unified pipeline. |
| `securityCoupling.minTlsMode` | `"signedByAmbariCA"` | Default TLS mode when auto-bumping off `none`. |
| `requiredChartVersion` | (varies) | Semver range. Rejected at deploy time if chart drifts. |
| `valueAliases` | `{}` | Rewrite canonical form-field paths to chart-specific values.yaml paths. |

Per-install (TLS section of the wizard form):

| Field | Default | Effect |
|---|---|---|
| `ingress.tlsMode` | `signedByAmbariCA` (auto-bumped to recommended) | Picks the TLS strategy. |
| `ingress.tlsSelfSign.validityDays` | `90` | View-signed leaf validity; capped 1..825. |
| `ingress.certManager.validityDays` | `90` | Cert-manager-signed leaf validity. |
| `ingress.externalSecret.refreshInterval` | `1h` | ESO refresh cadence. |
| `ingress.tuning.*` | (chart defaults) | Optional nginx-ingress annotations (collapsed by default). |

## Threat model

- **Private keys at rest**: CA private keys and operator-uploaded leaf private keys live in K8s Secrets in `ambari-pki` / the release namespace. Encryption-at-rest depends on the cluster's etcd configuration (envelope encryption with a KMS provider strongly recommended).
- **RBAC**: only the view's ServiceAccount needs read/write on `ambari-pki`. The release namespaces only need read on their `<release>-ingress-tls` Secret.
- **No private-key egress**: the registry's GET endpoints never return `tls.key`. The UI cannot read it back. The only place the key is touched is the view's signing path, which uses it to sign a leaf then forgets it (no caching).
- **Validity bounds**: 90d default, 825d hard cap per CA/Browser Forum baseline.
- **Audit**: every mutation is logged with the calling principal.

## Future work

- **OpenShift native Route**: chart-side change to expose `route.enabled` consistently across services so the view can render Route resources alongside Ingress on OCP clusters.
- **Pluggable providers**: today's ESO provider is wired generically (`SecretStore` discovery surfaces all providers); explicit Vault PKI helper UI (pre-fill `pki/issue/<role>`) is the next polish.
- **Bulk renew**: a cluster-level "renew all leaves expiring in N days" action; today renew is per-release.
- **Cron-driven self-sign renewal**: for `signedByAmbariCA` / `signedByCompanyCA` modes — cert-manager already handles its own; the view's pipeline is renew-on-action today.

