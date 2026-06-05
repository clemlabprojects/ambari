# Ambari REST Loopback (for in-view calls back to ambari-server)

Several K8S view code paths need to call Ambari's own REST API from inside the
view — to create a webhook user, fetch cluster service hosts, auto-create the
default OIDC security profile, or persist a base URI that a background worker
will later use to reconstruct an `AmbariActionClient`. These are **server →
self HTTPS** calls happening inside the same ambari-server JVM.

## The problem when Ambari sits behind a reverse proxy

Historically these callsites built the Ambari API base URL from the inbound
request's `UriInfo.getBaseUri()`. When operators put nginx / Caddy / HAProxy /
a cloud LB in front of Ambari at, say, `https://ambari.example.com`, the
inbound base URI reflects that public hostname. Looping back to that URL
means:

1. The connection leaves the box, hits the public proxy, and comes back in.
2. The proxy presents *its* TLS certificate (typically Let's Encrypt or the
   organisation's public PKI).
3. Ambari's own truststore (`ssl.trustStore.path` in `ambari.properties`,
   commonly `/etc/security/serverKeys/truststore.jks`) only contains the
   internal cluster CA — not the public proxy CA.

Result: `javax.net.ssl.SSLHandshakeException: PKIX path building failed:
unable to find valid certification path to requested target`. Most callsites
silently swallow this (best-effort `try/catch`), so the user only sees
secondary symptoms — an empty "Security Profile" dropdown when creating a
service from the catalog, missing Hive/Polaris endpoints in the discovery
dropdowns, a webhook user that never gets created.

## How the fix works

The `AmbariLoopbackUrlResolver` utility (in `o.a.a.v.k8s.utils`) builds an
Ambari loopback URL from the **local** listener identity, not from the
inbound request:

```
scheme://<host>:<port>/api/v1
```

| Part   | Source |
|--------|--------|
| scheme | `api.ssl` in `ambari.properties` (default `false` → `http`, `true` → `https`) |
| port   | `client.api.ssl.port` (HTTPS) or `client.api.port` (HTTP), defaults `8442` / `8080` |
| host   | see resolution order below |

### Host resolution order

1. **`k8s.view.ambari.loopback.host`** — explicit override read from
   `ambari.properties`. Set this when the automatic step below returns the
   wrong value.
2. **`InetAddress.getLocalHost().getCanonicalHostName()`** — the JVM's view of
   the local hostname. On a well-configured host where `/etc/hosts` and PTR
   records resolve cleanly, this returns the FQDN the cluster CA already
   signs (e.g. `master01.dev01.example.com`).
3. **`localhost`** — last-resort fallback with a `WARN` log. TLS hostname
   verification will almost certainly fail at this point; set the escape
   hatch property and restart.

## When to set `k8s.view.ambari.loopback.host`

Use it when step 2 above returns something that doesn't match the cert
Ambari presents on its HTTPS port. Common triggers:

- `/etc/hosts` resolves the hostname to `localhost`.
- No reverse DNS (PTR) record — `getCanonicalHostName()` returns the literal
  IP address instead of the FQDN.
- ambari-server runs in a container or Kubernetes pod and the in-pod hostname
  doesn't match the externally-issued cert.
- The cert has a different CN than `hostname -f` reports (e.g. cert issued
  for `ambari-internal.example.com` but the box's hostname is `master01`).

Set in `/etc/ambari-server/conf/ambari.properties`:

```properties
k8s.view.ambari.loopback.host=master01.dev01.example.com
```

Restart `ambari-server` for the change to take effect.

## Important non-impacts

This resolver is **only** used for the K8S view's loopback calls. It does
**not** change:

- The URLs the UI generates for the browser (those still come from
  `UriInfo.getBaseUri()` — the public proxy URL — which is what users need).
- Any other Ambari view (YARN Queue Manager, Files, Tez, Hive — these don't
  loopback to Ambari REST and were never affected).
- The truststore Ambari uses for outbound TLS (`ssl.trustStore.path`). If you
  need Ambari to talk *outbound* to a service whose cert isn't already
  trusted, you still extend that truststore as usual — the resolver only
  avoids the loopback-through-proxy round-trip.

## Why not honor `X-Forwarded-*` headers instead?

Honoring `X-Forwarded-*` would let Ambari understand the public URL
**for emitting links back to the browser**, but it would not fix this
specific loopback case — going through the proxy would still hit the cert
mismatch. The two concerns are orthogonal; addressing reverse-proxy header
trust at the Ambari level is a much larger, opt-in change that's not part of
this fix.
