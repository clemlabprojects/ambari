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

# TLS/Truststore Handling (Trino + KDPS charts)

## Overview
- **Truststore (company CA + Ambari internal CA)**
  - Ambari HTTPS truststore (`ssl.trustStore.path/type/password`) is loaded.
  - The Ambari internal CA cert is injected into that keystore (unless already present).
  - A single Secret `<release>-truststore` is created with:
    - `truststore.jks` (or PKCS12 if Ambari truststore is PKCS12)
    - `truststore.password`
    - `ca.crt` (PEM bundle of all certs, including internal CA)
  - Helm overrides set:
    - `global.security.tls.enabled=true`
    - `global.security.tls.truststore.enabled=true`
    - `global.security.tls.truststoreSecret=<release>-truststore`
    - `global.security.tls.truststoreKey=truststore.jks`
    - `global.security.tls.truststorePasswordKey=truststore.password`
    - `global.security.tls.mountPath` (if provided)
  - Chart default format is JKS unless overridden; PEM bundle is auxiliary.

- **HTTPS keystore (service HTTPS)**
  - Driven by TLS form + service.json bindings (e.g., `server.config.https.*`).
  - Auto-generate path uses the Ambari internal CA to issue a server cert:
    - Formats: PKCS12/JKS/PEM.
    - PEM mode yields `tls.crt`, `tls.key`, `ca.crt` in the Secret.
    - PKCS12/JKS mode yields keystore file + password key.
  - Keystore wiring stays separate from `global.security.tls.*` to avoid clashing with the truststore.

## Current defaults
- Truststore format follows Ambari truststore type (JKS by default). PEM is added as `ca.crt` for convenience.
- Chart wiring for truststore uses `global.security.tls.*` flat keys (Trino chart expects these).
- HTTPS keystore wiring uses `server.config.https.*` via bindings; truststore mount remains untouched.

## How to configure
1) Set Ambari `ssl.trustStore.path/type/password` so the company truststore is available.
2) In the UI TLS form, enable HTTPS and pick keystore format if you want the service HTTPS to be auto-generated.
3) Ingress TLS remains manual (tlsSecret/tlsHost) and is separate from the above.

## Reuse guidance
- Keep the shared truststore in `global.security.tls.*` (merged company + internal CA).
- Keep per-chart HTTPS keystores wired via `server.config.https.*` (bindings) to avoid conflicts.
- If a chart needs multiple truststores, it requires chart changes; current schema supports one truststore slot.

## Concrete on-disk layout (Trino HTTPS)

Given values:
```yaml
server:
  config:
    https:
      enabled: true
      keystore:
        secretName: trino-https
        key: keystore.p12
        mountPath: /etc/trino/https-keystore.p12
        passwordSecretName: trino-https-pass
        passwordKey: truststore.password
        passwordMountPath: /etc/trino/https-pass/password
```

Init + mounts produce:
```bash
$ ls -l /etc/trino
config.properties
jvm.config
node.properties
...

$ tail -n1 /etc/trino/config.properties
http-server.https.keystore.key=$(cat /etc/trino/https-pass/password)

$ ls -l /etc/trino/https-keystore.p12      # from Secret trino-https (key keystore.p12)
$ ls -l /etc/trino/https-pass/password     # from Secret trino-https-pass (key truststore.password)

# If truststore enabled:
$ ls -l /etc/security/truststore
ca.crt   # or truststore.jks depending on format
```

Secrets (default keys):
- `trino-https`: `keystore.p12`, `ca.crt`, `tls.crt`, `tls.key`
- `trino-https-pass`: `truststore.password`

Notes:
- Init container copies ConfigMap → emptyDir and appends the keystore password line; main container mounts the merged config dir.
- `busyboxImage` controls the init image (can point to your mirrored busybox).
