# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# krb-renewer — sidecar that runs alongside every JupyterHub user pod and
# holds the (privileged) jupyterhub service keytab. On a loop, it uses
# Kerberos S4U2Self to mint an impersonated TGT for the OIDC-authenticated
# user identified by $TARGET_USER, and writes it to a credential cache
# shared with the notebook container via an emptyDir volume.
#
# Design notes:
#   - The notebook container NEVER mounts the keytab. It only reads the
#     ccache file from the shared volume.
#   - We use MIT Kerberos (krb5 1.20+ in Alpine 3.20) which supports the
#     GSS_C_IMPERSONATOR_NAME credential-store extension required for S4U.
#   - python-gssapi 1.9.0 exposes the impersonate flow as
#     gssapi.Credentials(usage='impersonate', name=<target_user>).
#
# Multi-arch build (matches the other jupyterhub images already in the
# Clemlab registry):
#   docker buildx build --platform linux/amd64,linux/arm64 \
#     -t registry.clemlab.com/clemlabprojects/jupyterhub/krb-renewer:1.0.0 \
#     --push /tmp/krb-renewer

FROM alpine:3.20

# MIT krb5 client + tini. The S4U2Self step is implemented as a tiny C program
# (s4u2self.c) compiled against libkrb5; we drop the build toolchain at the end
# to keep the image small.
#
# Why C and not Python:
#   - python-gssapi.Credentials.impersonate() calls krb5_get_credentials_for_user
#     with options=0 -> non-forwardable result -> MIT KDC refuses any downstream
#     S4U2Proxy with "EVIDENCE_TKT_NOT_FORWARDABLE".
#   - pykrb5 (the libkrb5 Python binding, 0.9.x) does not expose
#     krb5_get_credentials_for_user in its public API.
#   - Going through libkrb5 in C directly lets us pass KRB5_GC_FORWARDABLE
#     explicitly and produce a forwardable evidence ticket.
RUN apk add --no-cache --virtual .build-deps gcc musl-dev krb5-dev \
    && apk add --no-cache krb5 tini

COPY s4u2self.c /tmp/s4u2self.c
RUN gcc -O2 -Wall -o /usr/local/bin/s4u2self /tmp/s4u2self.c -lkrb5 \
    && rm -f /tmp/s4u2self.c \
    && apk del .build-deps

COPY renew-loop.sh /usr/local/bin/renew-loop.sh
RUN chmod 0755 /usr/local/bin/renew-loop.sh /usr/local/bin/s4u2self

# Defaults; the pod spec overrides any of these per-user.
ENV RENEW_INTERVAL_SEC=1800
ENV CC_PATH=/krb/cc
ENV KEYTAB_PATH=/etc/security/keytabs/jupyterhub.keytab

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/renew-loop.sh"]
