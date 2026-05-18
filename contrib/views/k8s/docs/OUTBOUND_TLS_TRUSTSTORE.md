# Outbound TLS Truststore (for OIDC and back-channel HTTPS)

Every KDPS service that talks **outbound** to Keycloak (OIDC token exchange,
JWKS fetch), Ranger, the Atlas/Hive metastore, S3, Vault, or any other
internal HTTPS endpoint must trust the CA that signed those endpoints'
certs. Browsers don't enter into this — these are pod-to-pod (or
pod-to-master-node) connections happening inside the workload.

If the trust chain is missing the pod sees a classic error and bails:

```
OpenSSL::SSL::SSLError: state=error: certificate verify failed
  (unable to get local issuer certificate)
```

…or the JVM equivalent
(`PKIX path building failed: unable to find valid certification path`).

## The Secret

For every release the view installs, the security-profile step creates
**one** Kubernetes Secret in the release's namespace, named:

```
<releaseName>-truststore
```

It has **exactly three keys**, in the formats below. The view writes all
three at install time; ops/Ansible can pre-create the same Secret with the
same shape to inject additional company CAs (the view will then leave
it alone if the keys are already present).

| Key                  | Format                                                                  | Used by                       |
| -------------------- | ----------------------------------------------------------------------- | ----------------------------- |
| `ca.crt`             | PEM bundle: one or more `-----BEGIN CERTIFICATE-----` blocks concatenated | GitLab (Ruby/OpenSSL), curl, any non-JVM consumer |
| `truststore.jks`     | Java KeyStore (binary), JKS format                                       | Superset/Trino/OpenMetadata and any JVM service via `javax.net.ssl.trustStore` |
| `truststore.password` | UTF-8 password string for `truststore.jks`                              | Same JVM services |

All three keys point at the **same set of CAs**. The PEM and the JKS are
two encodings of the same trust material; the password unlocks the JKS.

## What's in `ca.crt`

The view builds the bundle from:

1. **The Ambari server truststore** at the path given by
   `ssl.trustStore.path` in `ambari.properties` — typically configured by
   the operator to contain the company's internal CA chain (the same trust
   roots Ambari uses to talk to Keycloak, FreeIPA, etc.).
2. **The Ambari internal CA**, fetched via
   `WebHookConfigurationService.ensureAmbariCertificateAuthority()`. This is
   the CA the view itself uses to sign mutating-webhook serving certs and
   other in-cluster leaves.

The two are merged: every cert in the Ambari truststore is appended to the
PEM bundle, plus the Ambari internal CA if not already present (verified
by attempted-verify against each existing entry). Result: one
deduplicated PEM bundle covering every CA the workload could need.

To add a CA system-wide (so every future KDPS install picks it up):

```bash
keytool -importcert \
    -keystore "$AMBARI_TRUSTSTORE_PATH" \
    -storepass "$AMBARI_TRUSTSTORE_PASSWORD" \
    -alias acme-internal-root \
    -file acme-internal-root.pem \
    -noprompt
# next deploy → view re-reads the truststore and re-bundles into ca.crt
```

To add a CA to a single release without going through Ambari:

```bash
# Replace the Secret in-place with an extended ca.crt
EXISTING=$(kubectl -n <namespace> get secret <releaseName>-truststore -o json)
NEW_PEM=$(echo "$EXISTING" | jq -r '.data["ca.crt"]' | base64 -d ; cat extra-ca.pem)
kubectl -n <namespace> patch secret <releaseName>-truststore --type merge \
    -p "{\"data\":{\"ca.crt\":\"$(printf %s "$NEW_PEM" | base64 -w0)\"}}"
# Then rolling-restart the workload so init containers pick up the change.
```

## How services consume it

### JVM services (Superset, Trino, OpenMetadata, Hive metastore, …)

The release values get:

```yaml
global:
  security:
    tls:
      enabled: true
      truststore:
        enabled: true
      truststoreSecret: <releaseName>-truststore
      truststoreKey: truststore.jks
      truststorePasswordKey: truststore.password
```

The chart templates mount the JKS at a known path and add
`-Djavax.net.ssl.trustStore=…` to `JAVA_OPTS`. The JVM trusts everything in
the JKS for all outbound TLS.

### GitLab (Ruby/OpenSSL)

GitLab can't read a JKS. The chart's `certificates` init container takes
PEM input only. The view writes:

```yaml
gitlab:
  global:
    certificates:
      customCAs:
        - secret: <releaseName>-truststore
          keys:
            - ca.crt
```

**The `keys: [ca.crt]` filter is mandatory.** Without it, the chart loops
over every key in the Secret, including `truststore.jks` (binary) and
`truststore.password` (literal text). `update-ca-certificates` silently
discards non-PEM input, so leaving `keys` unset *happens to work* — but
it's relying on undefined behavior and a future image bump could turn the
silent discard into a hard failure.

The init container concatenates `ca.crt` into the system trust store
(`/etc/ssl/certs/ca-bundle.crt`) and the empty-dir is shared with every
GitLab sub-container (webservice, sidekiq, gitaly, toolbox, …). All
outbound HTTPS from any of them — `Net::HTTP`, `curl`, `git fetch`,
OmniAuth's JWKS poll — now sees the company CAs.

### Other non-JVM services

Anything that wants PEM input follows the GitLab pattern: pick the
`ca.crt` key from the same Secret. The view's binding template is:

```json
{
  "name": "trusted-ca-customcas",
  "targets": [
    { "path": "<chart>.global.certificates.customCAs[0].secret",
      "from": { "type": "template", "template": "${releaseName}-truststore" } },
    { "path": "<chart>.global.certificates.customCAs[0].keys[0]",
      "from": { "type": "template", "template": "ca.crt" } }
  ]
}
```

(Replace `<chart>.global.certificates.customCAs` with the chart's own
custom-CA value path if it differs.)

## Verifying the trust chain inside a running pod

```bash
# Confirm the CA is in the system bundle
kubectl -n <namespace> exec -it <pod> -- \
    awk '/BEGIN CERTIFICATE/{n++} END{print n, "certs"}' /etc/ssl/certs/ca-certificates.crt

# Confirm OpenSSL can verify the OIDC endpoint
kubectl -n <namespace> exec -it <pod> -- \
    openssl s_client -connect sso-dev.dev21.hadoop.clemlab.com:8444 \
        -servername sso-dev.dev21.hadoop.clemlab.com -showcerts < /dev/null \
    | grep -E '^(Verify return code|subject|issuer)='

# Java equivalent
kubectl -n <namespace> exec -it <pod> -- \
    keytool -list -keystore /etc/ssl/certs/.../truststore.jks \
        -storepass "$(cat /etc/ssl/certs/.../truststore.password)"
```

A passing trust chain shows `Verify return code: 0 (ok)`. Anything else,
treat as the same class of bug as the GitLab/Keycloak failure above.

## Failure modes worth knowing

| Symptom                                                                | Likely cause                                                                                       |
| ---------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `unable to get local issuer certificate` on OmniAuth                  | `customCAs` not wired (binding produced `customCAs[0]` as literal map key — see HelmService.insertNested / bindings.ts handling of `[N]`) |
| `PKIX path building failed`                                            | JVM not pointing at `truststore.jks` — check `JAVA_OPTS` env in the pod                            |
| customCAs Secret name resolves but ca.crt is empty                     | Ambari truststore path was empty/unreadable at install time — re-run security profile step        |
| Trust works locally but fails in CI/clones                             | Custom mirrors/registries use a different CA chain — add their root to the Ambari truststore      |
| `nodename nor servname provided` on outbound HTTPS                    | DNS / NetworkPolicy egress issue, **not** a trust problem; check `kubectl get networkpolicy`        |
