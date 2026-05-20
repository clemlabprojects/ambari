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

# krb5 + krb5-dev (headers, needed for gssapi wheel build), python + pip,
# tini for clean PID 1 / signal handling. We need build tooling because
# python-gssapi has a C extension; we drop the build tooling at the end
# to keep the image small.
RUN apk add --no-cache --virtual .build-deps \
        gcc musl-dev python3-dev libffi-dev krb5-dev \
    && apk add --no-cache \
        krb5 python3 py3-pip tini \
    && python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir --upgrade pip \
    && /opt/venv/bin/pip install --no-cache-dir "gssapi==1.9.0" \
    && apk del .build-deps

ENV PATH="/opt/venv/bin:${PATH}"

COPY renew-loop.sh /usr/local/bin/renew-loop.sh
COPY s4u2self.py /usr/local/bin/s4u2self.py
RUN chmod 0755 /usr/local/bin/renew-loop.sh /usr/local/bin/s4u2self.py

# Defaults; the pod spec overrides any of these per-user.
ENV RENEW_INTERVAL_SEC=1800
ENV CC_PATH=/krb/cc
ENV KEYTAB_PATH=/etc/security/keytabs/jupyterhub.keytab

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/renew-loop.sh"]
