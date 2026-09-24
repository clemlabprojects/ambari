#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Brings the server up against an external PostgreSQL, then runs it in the foreground so the
# container's lifecycle is the server's lifecycle.
#
# Everything here is idempotent: the pod will be restarted, rescheduled and rolled, and each start
# must converge rather than assume a fresh database.

set -euo pipefail

DB_HOST="${AMBARI_DB_HOST:?AMBARI_DB_HOST is required}"
DB_PORT="${AMBARI_DB_PORT:-5432}"
DB_NAME="${AMBARI_DB_NAME:-ambari}"
DB_USER="${AMBARI_DB_USER:-ambari}"
DB_PASSWORD="${AMBARI_DB_PASSWORD:?AMBARI_DB_PASSWORD is required}"

log() { echo "[entrypoint] $*" >&2; }

# OpenShift assigns an arbitrary UID that has no passwd entry, and Ambari's python code calls
# getpwuid() during setup. Giving the UID a name through nss_wrapper is the usual remedy; without
# it the server fails with a KeyError long before anything useful happens.
if ! getent passwd "$(id -u)" >/dev/null 2>&1; then
  if [ -w /etc/passwd ]; then
    echo "ambari:x:$(id -u):0:Ambari:/var/lib/ambari-server:/sbin/nologin" >> /etc/passwd
    log "added a passwd entry for uid $(id -u)"
  else
    log "WARNING: uid $(id -u) has no passwd entry and /etc/passwd is not writable."
    log "Add 'ambari:x:\${UID}:0::/var/lib/ambari-server:/sbin/nologin' via a projected file, or"
    log "Ambari's setup will fail in getpwuid()."
  fi
fi

log "waiting for postgres at ${DB_HOST}:${DB_PORT}"
for _ in $(seq 1 60); do
  if timeout 2 bash -c ">/dev/tcp/${DB_HOST}/${DB_PORT}" 2>/dev/null; then break; fi
  sleep 2
done
timeout 2 bash -c ">/dev/tcp/${DB_HOST}/${DB_PORT}" 2>/dev/null \
  || { log "postgres is not reachable at ${DB_HOST}:${DB_PORT}"; exit 1; }

log "pointing the server at the database"
# No --jdbc-driver: the RPM already puts postgresql-*.jar on the server's classpath, and pointing
# the option at a path that does not exist only makes setup fail.
ambari-server setup -s \
  --ambari-java-home="${AMBARI_JAVA_HOME:-/usr/lib/jvm/ambari-java}" \
  --database=postgres \
  --databasehost="${DB_HOST}" \
  --databaseport="${DB_PORT}" \
  --databasename="${DB_NAME}" \
  --databaseusername="${DB_USER}" \
  --databasepassword="${DB_PASSWORD}"

# The schema is created once, by whoever gets there first. A second pod, or a restart, finds the
# tables already present: psql exits non-zero on the duplicate-object errors, which is expected and
# not a failure.
if ! PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" \
      -tAc "select 1 from information_schema.tables where table_name='clusters'" 2>/dev/null | grep -q 1; then
  log "empty database: loading the Ambari schema"
  PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" \
    -f /var/lib/ambari-server/resources/Ambari-DDL-Postgres-CREATE.sql >/dev/null 2>&1 || true
else
  log "schema already present"
fi

# Ambari refuses to start unless the invoking user matches ambari-server.user, and the packaged
# value is root, which no OpenShift pod can be. Everything the server writes to is group-0 writable
# in this image, so the arbitrary UID -- named through the passwd entry added above -- is the
# correct value to record. setup rewrites the properties file, so this has to come after it.
# ambari.properties is rewritten by setup, so anything the deployment wants to say about the server
# has to be applied after it. AMBARI_EXTRA_PROPERTIES carries one key=value per line; each key is
# replaced if present and appended otherwise.
PROPERTIES=/etc/ambari-server/conf/ambari.properties
set_property() {
  local key="$1" value="$2" escaped
  escaped="$(printf '%s' "${value}" | sed -e 's/[\\&|]/\\&/g')"
  if grep -q "^${key}=" "${PROPERTIES}"; then
    sed -i "s|^${key}=.*|${key}=${escaped}|" "${PROPERTIES}"
  else
    printf '%s=%s\n' "${key}" "${value}" >> "${PROPERTIES}"
  fi
}

if [ -n "${AMBARI_EXTRA_PROPERTIES:-}" ]; then
  while IFS= read -r line; do
    case "${line}" in ''|'#'*) continue;; esac
    set_property "${line%%=*}" "${line#*=}"
  done <<< "${AMBARI_EXTRA_PROPERTIES}"
  log "applied $(printf '%s' "${AMBARI_EXTRA_PROPERTIES}" | grep -c '=') properties from the deployment"
fi

# Ambari encrypts what it keeps in its credential store with this key, and so does the KDPS view
# with the kubeconfig you upload. Without it the view falls back to a passphrase that is a constant
# in the published source, so refuse to run rather than pretend the kubeconfig is protected.
if [ -z "${AMBARI_SECURITY_MASTER_KEY:-}" ]; then
  log "AMBARI_SECURITY_MASTER_KEY is not set: the master key is required."
  exit 1
fi

AMBARI_RUN_USER="$(id -u -n)"
export USER="${AMBARI_RUN_USER}" LOGNAME="${AMBARI_RUN_USER}"
if [ "$(id -u)" -ne 0 ] && [ -w /etc/ambari-server/conf/ambari.properties ]; then
  sed -i "s|^ambari-server.user=.*|ambari-server.user=${AMBARI_RUN_USER}|" \
    /etc/ambari-server/conf/ambari.properties
  log "running as ${AMBARI_RUN_USER} (uid $(id -u)); recorded it as ambari-server.user"
fi

# A view jar mounted at run time (a ConfigMap or a PVC) is copied in before the server starts, so
# the KDPS view can be updated without rebuilding the image.
if [ -d /opt/ambari-views ] && compgen -G "/opt/ambari-views/*.jar" >/dev/null; then
  log "installing views from /opt/ambari-views"
  cp -f /opt/ambari-views/*.jar /var/lib/ambari-server/resources/views/
fi

# ambari-server start daemonises and returns, so the container needs something to hold PID 1 and a
# way to notice the server dying. Tail the log, and exit when the server process is gone so the pod
# restarts rather than sitting there looking healthy with nothing running.
log "starting the server"
# When start fails the container is replaced and its filesystem, including the only copy of the
# server's own log, goes with it -- so the pod logs would show the failure and none of the reason.
if ! ambari-server start --skip-database-check </dev/null; then
  log "the server did not come up. Its log, which this container is about to take with it:"
  tail -n 200 /var/log/ambari-server/ambari-server.log 2>/dev/null || true
  tail -n 60 /var/log/ambari-server/ambari-server.out 2>/dev/null || true
  exit 1
fi

LOG=/var/log/ambari-server/ambari-server.log
PIDFILE=/var/run/ambari-server/ambari-server.pid
for _ in $(seq 1 30); do [ -f "$LOG" ] && break; sleep 1; done

terminate() { log "stopping the server"; ambari-server stop >/dev/null 2>&1 || true; exit 0; }
trap terminate TERM INT

tail -F "$LOG" &
TAIL_PID=$!

# Watch the process, not "ambari-server status": that command decides the server is gone whenever
# it cannot read something it expects to own, which under an arbitrary UID means it says the server
# is gone while the server is serving.
while :; do
  SERVER_PID="$(cat "${PIDFILE}" 2>/dev/null || true)"
  if [ -z "${SERVER_PID}" ] || ! kill -0 "${SERVER_PID}" 2>/dev/null; then
    break
  fi
  sleep 10
done

log "the server is no longer running; exiting so the pod is restarted"
kill "${TAIL_PID}" 2>/dev/null || true
exit 1
