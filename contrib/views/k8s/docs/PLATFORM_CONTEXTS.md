# Platform Contexts

A **platform context** is a named ODP environment (Atlas, Ranger, Kerberos, …) that a KDPS
service integrates *against*. It centralises the connection + auth + credential information
that services previously re-asked for on every deploy, and it is the boundary across which
privileged operations (e.g. creating a Ranger policy) are performed.

This is deliberately distinct from any kubeconfig / "kube-context" notion — it is the
**data-platform** side, not the Kubernetes cluster the workloads run on.

## Two kinds of context

| | **Managed** | **External** |
|---|---|---|
| Backing | The ODP cluster this Ambari manages | A cluster Ambari does **not** manage (e.g. behind Knox) |
| Persistence | **Virtual** — never stored; a live projection of Ambari | Stored as a `KdpsContextEntity` (table `k8s_context`) |
| Endpoints / auth modes | Resolved live from Ambari config | Operator-entered (`config` JSON) |
| Ranger admin secret | **Never handled by the view** — operations are delegated to the Ambari server, which reads the decrypted `ranger-env` credentials | Operator-entered once, stored encrypted in view instance data (`context.<id>.rangerAdminPassword`) |
| Ranger op mechanism | `RANGER_POLICY_GRANT_VIA_AMBARI` → `POST /clusters/{c}/ranger_policy` | `RANGER_POLICY_CREATE_ATLAS_OM_READ` → view-side Ranger REST with stored creds |

There is always exactly one managed context, id `default`. It cannot be created, edited or
deleted — it is synthesised from the Ambari cluster on every read, so there is nothing to
persist and no risk of drift.

## Why the managed path delegates to the Ambari server

Ambari masks every `PASSWORD`-typed config in its REST API (`ranger-env/admin_password`
comes back as the opaque `SECRET:ranger-env:2:admin_password`), and the value is **not** in
the JCEKS credential store either, so a sandboxed view genuinely cannot read it. The
Ambari **server**, however, decrypts config in-process. So — exactly like the existing
`ranger_plugin_repository` feature — KDPS asks the server to perform the Ranger operation:

```
KDPS view ──POST /clusters/{c}/ranger_policy──▶ RangerPolicyResourceProvider
                                                 └─ schedules ─▶ EnsureRangerPolicyServerAction
                                                                  ├─ reads ranger-env creds (decrypted, in-JVM)
                                                                  ├─ ensures the user exists (xusers)
                                                                  └─ grants access on the service repo
```

The server-side action (`org.apache.ambari.server.serveraction.ranger.EnsureRangerPolicyServerAction`)
is **generic** — given `{rangerServiceName, userName, accessTypes, resourcesJson, policyNameHint}`
it idempotently:

1. ensures the user exists in Ranger (`/service/xusers/secure/users`) — Ranger rejects
   policies that reference unknown users;
2. grants the access, either by appending a policy item to an existing policy that already
   owns the same resource scope (Ranger refuses two policies with identical resources, error
   code 3010 — the conflicting policy name is parsed from the error and reused) or by
   creating a new policy;
3. polls the policy back until it is readable.

It is reusable beyond Atlas (Trino, etc.).

## Atlas federation example

For OpenMetadata → Atlas federation against a Ranger-authorized Atlas, the dispatcher issues
**two** grants because the Atlas Ranger servicedef forbids a single policy from spanning both
resource sets:

* entities: `{entity-type, entity-classification, entity}` → `entity-read`
* types: `{type-category, type}` → `type-read`

On a **managed** context these go through `RANGER_POLICY_GRANT_VIA_AMBARI` (no password). On
an **external** context the view calls Ranger REST directly with the context's stored creds.

## REST API (view)

`.../resources/api/contexts`

| Method | Path | Description |
|---|---|---|
| GET | `/contexts` | List: the virtual managed default + any external contexts |
| GET | `/contexts/{id}` | One context |
| GET | `/contexts/{id}/resolved` | The resolved view (live discovery for managed; secrets masked) |
| POST | `/contexts` | Create/update an **external** context (managed is read-only) |
| DELETE | `/contexts/{id}` | Delete an external context (`default` is protected) |

## Server-side REST API (ambari-server core)

`POST /api/v1/clusters/{clusterName}/ranger_policy` with body:

```json
{
  "RequestInfo": { "context": "…" },
  "RangerPolicy": {
    "rangerServiceName": "<cluster>_atlas",
    "userName": "openmetadata-federation",
    "accessTypes": "entity-read",
    "resourcesJson": "{\"entity-type\":[\"*\"],\"entity-classification\":[\"*\"],\"entity\":[\"*\"]}",
    "policyNameHint": "kdps-openmetadata-<release>-read-entities",
    "timeoutSeconds": 60
  }
}
```

Returns a pollable Ambari request id. Mirrors `ranger_plugin_repository` 1:1
(`RangerPolicyService` → `RangerPolicyResourceProvider` → `EnsureRangerPolicyServerAction`).

## Note on shipping external contexts

External contexts are persisted via the `k8s_context` view entity. Ambari registers view
entities at **version-registration** time, so the entity table is created when the view
version that introduced it is installed/registered fresh (the normal release path). Swapping
the jar in place on an already-registered version does **not** create the new table — the
managed default is virtual precisely so the common case needs no persistence.
