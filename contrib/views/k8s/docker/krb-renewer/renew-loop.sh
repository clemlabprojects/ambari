#!/bin/sh
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

# renew-loop.sh — every $RENEW_INTERVAL_SEC, perform S4U2Self for $TARGET_USER
# using the service keytab at $KEYTAB_PATH, and atomically write the impersonated
# ccache to $CC_PATH so the notebook container picks it up.
#
# Expected env (set by the pod spec):
#   SERVICE_PRINCIPAL   e.g. jupyterhub/jupyter.dev21.example.com@REALM
#   TARGET_USER         OIDC username, no realm  (e.g. lbakalian)
#   REALM               Kerberos realm           (e.g. DEV21.HADOOP.CLEMLAB.COM)
#   KEYTAB_PATH         /etc/security/keytabs/jupyterhub.keytab (default)
#   CC_PATH             /krb/cc                                  (default)
#   RENEW_INTERVAL_SEC  1800                                     (default)
#
# Failure policy:
#   - First iteration: hard fail (exit 1) so kubelet shows the error and the
#     user pod doesn't silently sit there with no auth.
#   - Subsequent iterations: log and continue. A transient KDC outage shouldn't
#     kill the sidecar — the existing ccache stays valid until the next renew
#     succeeds OR until the ticket expires (at which point Hadoop calls 401
#     and the user knows).
set -u

: "${SERVICE_PRINCIPAL:?SERVICE_PRINCIPAL not set}"
: "${TARGET_USER:?TARGET_USER not set}"
: "${REALM:?REALM not set}"
: "${KEYTAB_PATH:=/etc/security/keytabs/jupyterhub.keytab}"
: "${CC_PATH:=/krb/cc}"
: "${RENEW_INTERVAL_SEC:=1800}"

if [ ! -r "$KEYTAB_PATH" ]; then
    echo "[$(date -Iseconds)] FATAL: keytab not readable at $KEYTAB_PATH" >&2
    exit 1
fi

CC_DIR="$(dirname "$CC_PATH")"
mkdir -p "$CC_DIR"

renew_once() {
    # Tmp ccache so a half-written file never gets read by the notebook.
    CC_TMP="${CC_PATH}.new.$$"

    # The impersonator's own creds go to a separate ccache (we don't want the
    # notebook to ever see those; only the impersonated user's ccache at CC_PATH).
    SVC_CC="/tmp/svc.cc"

    KRB5CCNAME="FILE:${SVC_CC}" kinit -k -t "$KEYTAB_PATH" "$SERVICE_PRINCIPAL" >&2
    rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "[$(date -Iseconds)] kinit service principal failed (rc=$rc)" >&2
        return $rc
    fi

    # S4U2Self via python-gssapi. Writes the impersonated TGT to $CC_TMP.
    KRB5CCNAME="FILE:${SVC_CC}" \
    /usr/local/bin/s4u2self.py \
        --impersonator "$SERVICE_PRINCIPAL" \
        --target-user "${TARGET_USER}@${REALM}" \
        --output "$CC_TMP" >&2
    rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "[$(date -Iseconds)] s4u2self failed (rc=$rc)" >&2
        rm -f "$CC_TMP"
        return $rc
    fi

    # Atomic publish so the notebook never reads a torn ccache.
    chmod 0644 "$CC_TMP"
    mv -f "$CC_TMP" "$CC_PATH"
    echo "[$(date -Iseconds)] renewed ccache for ${TARGET_USER}@${REALM} -> $CC_PATH"
    return 0
}

# First iteration: hard fail.
if ! renew_once; then
    echo "[$(date -Iseconds)] FATAL: initial renewal failed; refusing to start" >&2
    exit 1
fi

# Steady-state loop.
while true; do
    sleep "$RENEW_INTERVAL_SEC"
    if ! renew_once; then
        echo "[$(date -Iseconds)] WARN: renewal failed; existing ccache still valid until expiry" >&2
    fi
done
