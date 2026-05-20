<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

Ambari OIDC Single Sign-on
==========================

> **Scope.** This document covers the clemlab-specific OIDC SSO implementation
> that runs in addition to (and independently of) Knox SSO described in
> [`index.md`](./index.md).  It is the production path for clusters that use
> Keycloak (or any standards-compliant OIDC provider) directly, without Knox.

* [Overview](#overview)
* [Architecture & request flow](#architecture--request-flow)
* [Configuration reference](#configuration-reference)
* [How a login actually authenticates](#how-a-login-actually-authenticates)
* [JIT user & group provisioning (AMBARI-419 / AMBARI-423)](#jit-user--group-provisioning)
* [Session-JWT model (AMBARI-433)](#session-jwt-model)
* [Operator runbook](#operator-runbook)
* [Security model](#security-model)
* [Troubleshooting](#troubleshooting)
* [Change history](#change-history)

<a id="overview"></a>
## Overview

Ambari authenticates browser users by minting an **HS256 server-issued session
JWT** after a successful OpenID-Connect Authorization-Code flow against the
configured Identity Provider (typically Keycloak).  The session JWT is written
as the `hadoop-jwt` cookie and validated by Ambari on every subsequent request.

Two filters cooperate:

| Filter | Path scope | Responsibility |
|---|---|---|
| `OidcCallbackFilter` | `/oidc/*` and any unauthenticated browser GET | Issues `302` to the IdP; handles the `/oidc/callback` leg; exchanges `code` for tokens; mints + writes the Ambari session cookie. |
| `AmbariJwtAuthenticationFilter` | every request | Reads the cookie, verifies the JWT (RS256 for Knox SSO / upstream IdP tokens; **HS256** for our session tokens), passes the username on to `AmbariJwtAuthenticationProvider`. |

<a id="architecture--request-flow"></a>
## Architecture & request flow

```
┌─────────┐   1) GET /                ┌───────────────┐
│ Browser │ ─────────────────────────▶│ Ambari Server │
└─────────┘                           │ OidcCallback  │
     ▲                                │  Filter       │
     │ 2) 302 → IdP authorize         └───────┬───────┘
     │                                        │
     │                                        ▼
     │                                ┌───────────────┐
     │ 3) login + consent             │   Keycloak    │
     │ ─────────────────────────────▶ │               │
     │                                └───────┬───────┘
     │ 4) 302 → /oidc/callback?code=… │       │
     │ ◀──────────────────────────────────────┘
     ▼
┌─────────┐  5) GET /oidc/callback    ┌───────────────┐
│ Browser │ ─────────────────────────▶│ OidcCallback  │
└─────────┘                           │  Filter       │
     ▲                                │               │
     │                                │ 6) POST       │
     │                                │    /token     │───┐ server-side
     │                                │               │   │ token exchange
     │                                │ 7) extract    │   │ (uses clientSecret)
     │                                │    username + │   ▼
     │                                │    groups     │ ┌─────────┐
     │                                │ 8) mint HS256 │ │Keycloak │
     │                                │    session JWT│ └─────────┘
     │ 9) Set-Cookie hadoop-jwt + 302 │               │
     │ ◀───────────────────────────── └───────────────┘
     ▼ to original URL

  ────── subsequent requests use the cookie ──────

┌─────────┐  10) GET / (cookie)       ┌─────────────────────────┐
│ Browser │ ─────────────────────────▶│ AmbariJwt              │
└─────────┘                           │  AuthenticationFilter   │
                                      │  → verify HS256         │
                                      │  → resolveUsername      │
                                      │  → JIT-create if needed │
                                      │  → set SecurityContext  │
                                      └─────────────────────────┘
```

<a id="configuration-reference"></a>
## Configuration reference

All properties live in `sso-configuration` (managed via `ambari-server setup-sso-oidc`
or the REST API at `/api/v1/services/AMBARI/components/AMBARI_SERVER/configurations/sso-configuration`).

### Core OIDC

| Property | Default | Description |
|---|---|---|
| `ambari.sso.oidc.authentication.enabled` | derived | When `"true"`, OIDC redirect + callback are active. When empty, derived from `clientId`/`clientSecret` being set (back-compat). |
| `ambari.sso.oidc.clientId` | empty | OIDC client ID registered in Keycloak (e.g. `ambari-oidc`). |
| `ambari.sso.oidc.clientSecret` | empty | OIDC client secret.  Server-side only — never sent to the browser. |
| `ambari.sso.oidc.callbackUrl` | empty | Absolute `redirect_uri` registered in Keycloak (e.g. `https://ambari.corp.example.com:8442/oidc/callback`). Required when Ambari sits behind a reverse proxy. |
| `ambari.sso.oidc.providerUrl` | empty | Keycloak `…/auth` endpoint.  Overrides `ambari.sso.provider.url` so Knox SSO and OIDC SSO can coexist. |
| `ambari.sso.oidc.providerCertificate` | empty | X509 cert of the Keycloak realm signing key.  Used to verify upstream tokens. |
| `ambari.sso.jwt.usernameClaim` | empty | JWT claim used as the Ambari username. Empty → try `preferred_username`, fall back to `sub`. Set to a specific claim (e.g. `email`, `upn`) to override. **No fallback** when set explicitly: a missing claim returns null → loud failure. |
| `ambari.sso.jwt.cookieName` | `hadoop-jwt` | Name of the session cookie. |

### JIT user / group provisioning (AMBARI-419)

| Property | Default | Description |
|---|---|---|
| `ambari.sso.oidc.user.auto.create` | `false` | When `true`, missing Ambari users are auto-created on first OIDC login (UserAuthenticationType=JWT). Avoids requiring LDAP sync. |
| `ambari.sso.oidc.user.case.conversion` | `none` | Case-normalization of displayName: `none` / `lower` / `upper`. |
| `ambari.sso.oidc.user.default.role` | empty | Reserved (currently informational). |
| `ambari.sso.oidc.groups.claim` | empty | JWT claim carrying group memberships (typically `groups`). Empty disables group sync. |
| `ambari.sso.oidc.groups.auto.create` | `false` | When `true`, groups named in the claim that don't exist are auto-created (GroupType=JWT). |
| `ambari.sso.oidc.groups.case.conversion` | `none` | Same as user case conversion, applied to group names. |
| `ambari.sso.oidc.groups.sync.on.login` | `true` | When `true`, group memberships are refreshed on each login (additions AND removals). When `false`, sync runs only at user creation. |

### Group-based admin grants + login gating (AMBARI-423)

| Property | Default | Description |
|---|---|---|
| `ambari.sso.oidc.admin.users` | empty | Comma-separated usernames matched against the JWT subject.  These users receive `AMBARI.ADMINISTRATOR` at JIT-create time. Use for bootstrap. |
| `ambari.sso.oidc.admin.groups` | empty | Comma-separated group names that receive `AMBARI.ADMINISTRATOR` via group binding (idempotent — re-asserted on each login). Group membership grants admin transitively. |
| `ambari.sso.oidc.allowed.groups` | empty | When NON-EMPTY, login is rejected unless the user is in `admin.users` OR the JWT `groups` claim intersects this list OR `admin.groups` (admin groups are implicitly allowed). Empty = no gating. |

### Session token (AMBARI-433)

| Property | Default | Description |
|---|---|---|
| `ambari.sso.session.token.lifespan.seconds` | `28800` (8 h) | Lifespan of the Ambari-signed session JWT (and the `hadoop-jwt` cookie's `Max-Age`). Independent of the Keycloak access-token TTL. |
| `ambari.sso.session.signing.key` | empty | Optional explicit HMAC-SHA256 signing key (≥32 chars).  When empty, derived from `ambari.sso.oidc.clientSecret` via HKDF-SHA256.  Set explicitly for multi-node Ambari (all nodes MUST share the key) or to decouple session invalidation from `clientSecret` rotation. |

### Service-management toggles

| Property | Default | Description |
|---|---|---|
| `ambari.sso.oidc.manage_services` | `false` | When `true`, Ambari pushes OIDC SSO config to listed cluster services. |
| `ambari.sso.oidc.enabled_services` | empty | Comma-delimited list (or `*`).  Services not listed fall back to Knox SSO config. |

<a id="how-a-login-actually-authenticates"></a>
## How a login actually authenticates

1. **Browser hits Ambari** (`/`, `/#/main/...`, anywhere).
2. **`OidcCallbackFilter.shouldApply`** returns `true` because: SSO is enabled, the request is a browser GET (`Accept: text/html`, no `X-Requested-With`), the path is NOT under `/api/` or `/views/` (using `getRequestURI()` so view contexts are correctly excluded — see AMBARI-432), and there's no `hadoop-jwt` cookie.
3. **`OidcCallbackFilter.doFilter`** builds an HMAC-signed `state` token, then 302-redirects to Keycloak's authorize endpoint:
   ```
   https://sso/realms/REALM/protocol/openid-connect/auth?
     response_type=code&client_id=…&redirect_uri=…&scope=openid&state=…
   ```
4. **Keycloak authenticates the user** (interactive login, MFA, etc.) and 302s back to `/oidc/callback?code=…&state=…`.
5. **`OidcCallbackFilter` callback leg:**
   1. Verifies the `state` HMAC and extracts the original URL (return target).
   2. Server-side POST to Keycloak's token endpoint with the authorization code + `client_secret` → receives `access_token`.
   3. Parses the access token (no signature verification needed — the channel was authenticated TLS).
   4. Resolves the username via `AmbariSessionTokenService.resolveUsername` — honors `ambari.sso.jwt.usernameClaim`.
   5. Reads the operator-configured groups claim (default `groups`) from the access token.
   6. **Mints a fresh HS256 session JWT** carrying `sub`, `preferred_username`, the configured username claim if non-standard, AND the `groups` array.  Signed with the resolved signing key.
   7. Writes the session JWT as the `hadoop-jwt` cookie (`HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`, `Max-Age=` configured lifespan).
   8. 302-redirects to the original URL embedded in the state.
6. **Subsequent request** (with the cookie):
   1. `AmbariJwtAuthenticationFilter` reads `hadoop-jwt`, parses the JWT.
   2. `AmbariSessionTokenService.isAmbariSessionToken` → `true` (iss=`ambari`, alg=HS256). Routes to the HMAC branch.
   3. Verifies signature + `exp` via `AmbariSessionTokenService.verify`. No audience check (server-internal token).
   4. `AmbariJwtAuthenticationProvider.authenticate` receives `JwtAuthenticationToken(username, serializedJwt)`.
   5. Parses the JWT once for the `groups` claim.
   6. **AMBARI-423 allow-list gate** (`enforceAllowedGroupsGate`): rejects if `allowed.groups` is non-empty AND the user is not in `admin.users` AND `groups ∩ (allowed.groups ∪ admin.groups)` is empty.
   7. Looks up the user in Ambari's DB.  Missing user + `user.auto.create=true` → JIT create.
   8. Reconciles group memberships if `groups.claim` is set.
   9. AMBARI-423 admin-groups bootstrap: idempotently grants `AMBARI.ADMINISTRATOR` to each group in `admin.groups`.
   10. Sets the Spring `SecurityContext` and returns.

<a id="jit-user--group-provisioning"></a>
## JIT user & group provisioning (AMBARI-419 / AMBARI-423)

### When JIT is appropriate

JIT lets users sign in without prior LDAP sync.  The Ambari user record is created on first successful OIDC token presentation, with `UserAuthenticationType=JWT`.  The matching `Group` records (with `GroupType=JWT`) are created from the `groups` claim.

JIT is the right path for:

* Pure-OIDC deployments (Keycloak with social-only logins, Okta, Azure AD without LDAP federation).
* Lab / dev clusters where you don't want to maintain an LDAP server.
* Any environment where Keycloak (federated against IPA / AD / etc.) is the single source of truth.

It is **not** the right path when Ambari should reject any user that isn't pre-synced from LDAP.  In that case, leave `user.auto.create=false` (the default).

### Granting admin

Three escalating paths, listed in [the operator runbook](#operator-runbook) below:

1. `admin.users=<username>` — direct, fires only at first JIT create. Operator-only (no Keycloak access needed).
2. Add user to a Keycloak group listed in `admin.groups` — group-based, source of truth in Keycloak, propagates on each login.
3. Add a non-admin Keycloak group to `allowed.groups` (but NOT to `admin.groups`) — user is allowed in, no automatic admin role; admin must grant per-resource privileges (e.g. VIEW_USER on a specific view instance) manually.

### Audit

Each JIT create logs:

```
[JIT-AUTO-CREATE] Provisioning Ambari user from OIDC JWT: <username>
[JIT-AUTO-CREATE] Successfully provisioned <username> from JWT
```

Filter `ambari-server.log` for `[JIT-AUTO-CREATE]` to capture provisioning events.

### Bulk revocation

```
ambari-server purge-jit-users --inactive=true --dry-run=true   # preview
ambari-server purge-jit-users --inactive=true --confirm=true   # commit
```

Only deletes users with `auth_type=JWT` (never `LOCAL`/`KERBEROS`/`PAM`).  See
[AMBARI-419 design notes](../../../../docs/...) for full semantics.

<a id="session-jwt-model"></a>
## Session-JWT model (AMBARI-433)

### Why a server-issued session JWT?

Pre-AMBARI-433 behavior was: store the **upstream Keycloak access token** in the `hadoop-jwt` cookie.  Access tokens are short-lived by design (Keycloak default: 5 min); once expired the cookie was rejected and the user bounced through Keycloak again every few minutes.

AMBARI-433 decouples the two concerns:

* **Identity proof** — the Keycloak access token, validated once at login and immediately discarded.
* **Browser session** — an Ambari-signed HS256 JWT with a configurable lifespan (default 8 h), backing the cookie until it expires or the user logs out.

### Token shape

```json
{
  "iss": "ambari",
  "sub": "alice",
  "preferred_username": "alice",
  "iat": 1779296140,
  "exp": 1779324940,
  "jti": "57b2…-…-3e8a",
  "groups": ["hadoop_admins", "hadoop_users"],
  "<configured username claim if non-standard>": "alice"
}
```

* **alg pinned to HS256.** Verification rejects anything else — defense against alg-confusion attacks (`alg=none`, RS256 cross-talk).
* **iss=`ambari`** distinguishes our tokens from Knox/Keycloak-issued JWTs at verification time.  `AmbariJwtAuthenticationFilter.validateToken` routes on `iss` + `alg`: session tokens take the HMAC branch, upstream tokens take the RSA branch.
* **Claim forwarding.**  At mint time, `OidcCallbackFilter` propagates:
  * the operator-configured `groups` claim (so `enforceAllowedGroupsGate` and group sync continue to work post-login);
  * the operator-configured username claim (so `resolveUsername(session-token)` finds the username under that key when it's not one of `sub` / `preferred_username`).

  Service-controlled claims (`iss`, `iat`, `exp`, `jti`, `sub`, `preferred_username`) supplied by the caller are silently dropped.

### Key derivation

The HMAC key comes from one of two sources:

1. `ambari.sso.session.signing.key`, if non-empty (UTF-8 bytes, must be ≥ 32 chars).
2. **HKDF-SHA256** (RFC 5869) over `ambari.sso.oidc.clientSecret`, with the fixed info string `"ambari-session-jwt-v1"`.  Deterministic — same secret yields the same key — and cryptographically independent of the OIDC state-token signing key (which uses a different info string).

Side-effects of derivation:

* Rotating `clientSecret` automatically invalidates every outstanding session token (intentional — gives operators a one-knob session revocation).
* All Ambari nodes in a multi-node deployment MUST share the same `clientSecret` (or set `session.signing.key` explicitly).

### Lifespan

Cookie `Max-Age` equals the JWT's `exp` minus `iat`, equals `ambari.sso.session.token.lifespan.seconds`.  Default 28800 s = 8 h.

To extend without restart:

```
curl -k -u admin:admin -H 'X-Requested-By: ambari' -X POST \
  -d '{"Configuration":{"category":"sso-configuration","properties":{"ambari.sso.session.token.lifespan.seconds":"86400"}}}' \
  https://AMBARI:8442/api/v1/services/AMBARI/components/AMBARI_SERVER/configurations
```

> **WARNING — `sso-configuration` POSTs are *replacement*, not *merge*.**  The
> properties payload OVERWRITES the existing properties bag.  Any property not
> in the request body is reset to its default (often empty).  Always read the
> current state first, merge your delta, then POST the full payload — see the
> [operator runbook](#operator-runbook) for the read-modify-write pattern.

<a id="operator-runbook"></a>
## Operator runbook

### Add an OIDC user as Ambari admin (bootstrap)

Easiest path when you don't have Keycloak admin access:

```bash
# Read current properties first (so we don't wipe other settings)
CURRENT=$(curl -sk -u admin:admin -H 'X-Requested-By: ambari' \
  https://AMBARI:8442/api/v1/services/AMBARI/components/AMBARI_SERVER/configurations/sso-configuration \
  | python3 -c 'import json,sys;print(json.dumps(json.load(sys.stdin)["Configuration"]["properties"]))')
NEW=$(python3 -c "import json;p=json.loads('$CURRENT');p['ambari.sso.oidc.admin.users']='hadoopadmin';print(json.dumps(p))")
curl -k -u admin:admin -H 'X-Requested-By: ambari' -X POST \
  -d "{\"Configuration\":{\"category\":\"sso-configuration\",\"properties\":$NEW}}" \
  https://AMBARI:8442/api/v1/services/AMBARI/components/AMBARI_SERVER/configurations
```

### Add an OIDC user as Ambari non-admin (read-only via group)

```bash
# In Keycloak admin: create a group `hadoop_readonly`, add the user to it.
# Then in Ambari:
NEW_ALLOWED="hadoop_admins,hadoop_readonly"
# Read current, merge, POST (same read-modify-write pattern as above).
```

The user is allow-listed in but receives no Ambari role.  Grant per-view / per-cluster
privileges manually after first login (e.g. `VIEW.USER` on a specific view instance).

### Rotate the session signing key

Either rotate `oidc.clientSecret` in Keycloak + Ambari (HKDF rederives) OR set
`ambari.sso.session.signing.key` to a fresh random 64-char secret.  All outstanding
sessions become invalid immediately; users must re-login.  No Ambari restart required.

### Extend session lifespan (e.g. from 8 h to 24 h)

```bash
curl -k -u admin:admin -H 'X-Requested-By: ambari' -X POST \
  -d '{"Configuration":{"category":"sso-configuration","properties":{"ambari.sso.session.token.lifespan.seconds":"86400"}}}' \
  https://AMBARI:8442/api/v1/services/AMBARI/components/AMBARI_SERVER/configurations
```

Existing cookies keep their original `Max-Age` (browsers don't refresh on each
response).  Newly issued cookies use the longer lifespan.

### Recover a wiped sso-configuration

If a `sso-configuration` POST accidentally cleared `clientSecret` or
`providerCertificate`:

```bash
# clientSecret: re-fetch from Keycloak admin API
KC_TOKEN=$(curl -sk -X POST "$KC/realms/master/protocol/openid-connect/token" \
  -d 'client_id=admin-cli' -d 'username=admin' -d "password=$KC_ADMIN_PW" \
  -d 'grant_type=password' | jq -r .access_token)
CLIENT_UUID=$(curl -sk -H "Authorization: Bearer $KC_TOKEN" \
  "$KC/admin/realms/$REALM/clients?clientId=$CLIENT_ID" | jq -r '.[0].id')
SECRET=$(curl -sk -H "Authorization: Bearer $KC_TOKEN" \
  "$KC/admin/realms/$REALM/clients/$CLIENT_UUID/client-secret" | jq -r .value)

# providerCertificate: re-fetch from Keycloak realm keys
CERT=$(curl -sk -H "Authorization: Bearer $KC_TOKEN" \
  "$KC/admin/realms/$REALM/keys" \
  | jq -r '.keys[] | select(.type=="RSA" and .use=="SIG") | .certificate' | head -1)

# Then re-POST the full sso-configuration with these restored.
```

<a id="security-model"></a>
## Security model

* **Cookie storage.** `hadoop-jwt` is `HttpOnly` + `Secure` + `SameSite=Lax` + `Path=/`. Inaccessible to JavaScript; not sent over HTTP; not sent on cross-site subresource requests (only top-level cross-site GETs and same-site requests).
* **Algorithm pinning.** `AmbariSessionTokenService.verify` requires `alg=HS256`. Tokens with any other algorithm are rejected — no algorithm-confusion downgrade path.
* **Issuer pinning.** Tokens with `iss != "ambari"` AND `alg=HS256` do NOT route through the session-token verifier (they fall to the RSA path, where they'll fail signature verification). An attacker who somehow obtains the HMAC key can mint tokens that verify, but those tokens still cannot bypass the RSA-only path required for upstream-issued JWTs.
* **State token (CSRF defense).** The OIDC `state` parameter is an HMAC-signed JSON payload with a 5-minute TTL, signed with a key derived from `clientSecret` via a *different* HKDF info string (`"ambari-oidc-state-v1"`) than the session-token key. Leaking one does not leak the other.
* **No raw access token in the cookie.** The Keycloak access token is exchanged server-side over TLS, used once to extract identity claims, and immediately discarded. It is never stored, logged, or transmitted to the browser. Any audit infrastructure that previously needed `access_token` from the cookie should consult the OIDC callback log lines instead.
* **Claim whitelisting at mint.** `OidcCallbackFilter` forwards only the claims downstream code is documented to read (`groups`, configured username claim). It does NOT mirror every upstream claim — that would let Keycloak mis-configuration push attacker-controlled data into Ambari's authentication path.
* **AMBARI-419 race-safety.** Two simultaneous first-time logins of the same OIDC user race in `createUser`. The losing thread catches "User already exists" and re-fetches; both browser tabs succeed without duplicate auth-source attachment.
* **AMBARI-419 forbidden-character handling.** `UserName.fromString` rejects `<>&|\\\``. The provider catches `IllegalArgumentException` and returns a clean 401 (`AmbariAuthenticationException`) instead of a 500. Some IdPs (notably AD with SAM names containing `\\`) hit this path.

<a id="troubleshooting"></a>
## Troubleshooting

### `502 Token exchange failed`

Server-side DNS / network from the Ambari host to Keycloak is broken.  Check `/etc/hosts` and firewall rules; verify with `curl -k https://<keycloak>:<port>/realms/<realm>` from the Ambari server.  Common cause: a parallel Ansible playbook (e.g. `ansible-role-kubernetes`) replaced an `/etc/hosts` line that previously aliased Keycloak's hostname.

### `403 "Cannot find user from JWT. Please, ensure LDAP is configured and users are synced."`

The message is misleading — the actual gate is usually one of:

1. **`allowed.groups` non-empty, user's `groups` claim doesn't intersect.**  Verify with:
   ```bash
   grep -E "(allowed|admin)\\.groups" /var/log/ambari-server/ambari-server.log | tail -20
   ```
   Fix by adding the user to a Keycloak group on the allow-list (or by setting `admin.users`).
2. **The session token doesn't carry the `groups` claim.**  Should not happen post-AMBARI-433, but verify by decoding the `hadoop-jwt` cookie at https://jwt.io and confirming `groups` is present.

### `403 "Invalid JWT token"`

The cookie is present but invalid — almost always **expired session JWT** (passed the `exp` claim).  In normal operation the cookie's `Max-Age` matches the JWT's `exp`, so the browser deletes the cookie at the same time the server rejects it.  If you see this consistently, check that the server clock is in sync with Keycloak (NTP drift > a few seconds will cause spurious failures).

### Browser bouncing to Keycloak forever after a `/views/...` click

Pre-AMBARI-432 bug — the `/views/` exclusion in `OidcCallbackFilter.isBrowserGetRequest` used `getServletPath()`, which returns a path relative to the view's WebAppContext (so it never matched `/views/`).  Fixed in AMBARI-432 by switching to `getRequestURI()`.  If you still see this, confirm the running ambari-server includes the fix.

### Cookie present in DevTools but Ambari still bounces

* Check the cookie's `Path` is `/` and not scoped to a sub-path.
* Confirm `Secure` is set (cookies marked Secure are dropped on HTTP requests; mixed-content load orders can drop them).
* Verify the `Domain` (or lack thereof) matches the request host.

### After a `sso-configuration` POST, login is completely broken

Almost certainly a property got wiped — the POST API replaces the whole properties bag.  See [Recover a wiped sso-configuration](#recover-a-wiped-sso-configuration) above.  The most commonly wiped properties are `clientSecret` and `providerCertificate`.

<a id="change-history"></a>
## Change history

| JIRA | What | Notes |
|---|---|---|
| AMBARI-7 | Initial Jetty-based Oozie compatibility for `oozie-setup.sh` | Unrelated, but provides historical context for `oozie_prepare_war.py` behavior. |
| AMBARI-417 / AMBARI-418 | `setup-sso-oidc` CLI action | Initial OIDC setup mechanism. |
| AMBARI-419 | OIDC JIT user / group provisioning | Allows pure-OIDC deployments without LDAP. Adds the `*.auto.create`, `*.case.conversion`, `groups.claim`, `groups.sync.on.login` properties. |
| AMBARI-423 | Group-based admin + login gating | Adds `admin.users`, `admin.groups`, `allowed.groups`. |
| AMBARI-431 / AMBARI-430 | (Unrelated — Impala / KAFKA service definitions.) | |
| AMBARI-432 | Views redirect-loop fix | `OidcCallbackFilter` now uses `getRequestURI()` for the `/views/` exclusion so view requests inside their own WebAppContext are correctly skipped. |
| AMBARI-433 | Server-issued HS256 session JWT | Decouples the Ambari UI session from the upstream OIDC access-token TTL. Adds `session.token.lifespan.seconds`, `session.signing.key`. Refactors `mint()` to forward operator-relevant claims (`groups`, custom username claim). |
