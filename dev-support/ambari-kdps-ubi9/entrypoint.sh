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

LOG_DIR=/var/log/ambari-server
LOG="${LOG_DIR}/ambari-server.log"
AUDIT_LOG="${LOG_DIR}/ambari-audit.log"
PIDFILE=/var/run/ambari-server/ambari-server.pid

# How long the server gets to stop on its own before it is killed. Kubernetes kills the container
# terminationGracePeriodSeconds after asking it to stop, so this has to stay below that.
STOP_TIMEOUT="${AMBARI_STOP_TIMEOUT:-50}"
case "${STOP_TIMEOUT}" in
  ''|*[!0-9]*) log "AMBARI_STOP_TIMEOUT must be a number of seconds, not '${STOP_TIMEOUT}'; using 50"; STOP_TIMEOUT=50 ;;
esac

# The pod's readiness check requires this file. It is written once the administrator password is no
# longer Ambari's public default, once the account that has it is disabled or locked out (which turns
# admin/admin away just the same), or once keeping the default has been explicitly allowed, so the
# Service and the Route never send anyone to a server that still accepts admin/admin.
ADMIN_MARKER=/tmp/admin-bootstrapped
# What Ambari-DDL-Postgres-CREATE.sql stores for the "admin" account: the salted digest of "admin".
DEFAULT_ADMIN_KEY=538916f8943ec225d97a9a86a2c6ec0818c1cd400e09e03b660fdaaec4af29ddbb6f2b1033b81b00
# How long the server's API gets to accept the new administrator password before the container gives
# up and exits.
ADMIN_TIMEOUT="${AMBARI_ADMIN_PASSWORD_TIMEOUT:-300}"
case "${ADMIN_TIMEOUT}" in
  ''|*[!0-9]*) log "AMBARI_ADMIN_PASSWORD_TIMEOUT must be a number of seconds, not '${ADMIN_TIMEOUT}'; using 300"; ADMIN_TIMEOUT=300 ;;
esac

# The processes copying the server's log and its audit log to the container's output.
TAIL_PID=""
AUDIT_PID=""

# Give the copies a moment to print what the server wrote last -- on a stop, that is the shutdown
# itself -- then end them. tail checks the files once a second on the container's own filesystem
# (it cannot be notified of changes on an overlay), hence two.
stop_followers() {
  if [ -n "${TAIL_PID}${AUDIT_PID}" ]; then
    sleep 2
    kill ${TAIL_PID} ${AUDIT_PID} 2>/dev/null || true
  fi
}

# This script is PID 1: the moment it exits, the kernel kills everything left in the container, the
# JVM included, and the JVM's shutdown hooks never run. So on SIGTERM it signals the JVM itself and
# waits for it to go. That SIGTERM is what lets the KDPS view finish the work in progress and Jetty
# close its connections; nothing else passes the pod's signal on, because the server runs in a
# process group of its own. "ambari-server stop" is not used: it allows a fixed 30 seconds, and when
# it fails -- it always did while the image had no ps -- nothing stops the JVM before this script
# exits.
terminate() {
  trap '' TERM INT
  stop_server
  stop_followers
  exit 0
}

stop_server() {
  log "stopping the server"
  local pid
  pid="$(cat "${PIDFILE}" 2>/dev/null || true)"
  if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
    kill -TERM "${pid}" 2>/dev/null || true
    for _ in $(seq 1 "${STOP_TIMEOUT}"); do
      kill -0 "${pid}" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "${pid}" 2>/dev/null; then
      log "the server is still running after ${STOP_TIMEOUT}s; killing it"
      kill -KILL "${pid}" 2>/dev/null || true
    else
      log "the server has stopped"
    fi
  fi
}
# Installed before any of the start-up work: bash running as PID 1 ignores a SIGTERM it has no trap
# for, so a pod deleted while it is still starting would otherwise sit out its whole grace period.
trap terminate TERM INT

# A container restarted in place keeps its filesystem, and with it the marker of its previous run.
rm -f "${ADMIN_MARKER}"

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

# The value ambari.properties gives a key, empty when it gives none.
property() { sed -n "s|^$1=||p" "${PROPERTIES}" | tail -n 1; }

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

# Prints "changed" once the admin account has a password other than the one the schema was created
# with (or no local password at all). While it still has that one, prints "default" -- admin/admin
# signs in -- unless the account is "disabled", or "locked" out after authentication.local.max.failures
# failed sign-ins: the server turns admin/admin away from such an account, this script included.
# Fails when the database cannot be asked. The stored digest is read rather than admin/admin tried at
# the login, because a failed sign-in counts towards that lockout.
admin_password_state() {
  local row active failures max_failures
  row="$(PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" \
    -tA -F ' ' -c "select u.active, u.consecutive_failures
          from user_authentication a join users u on u.user_id = a.user_id
          where u.user_name = 'admin' and a.authentication_type = 'LOCAL'
            and a.authentication_key = '${DEFAULT_ADMIN_KEY}' limit 1")" || return 1
  if [ -z "${row}" ]; then
    echo changed
    return 0
  fi
  read -r active failures <<< "${row}"
  case "${active}" in ''|*[!0-9]*) return 1 ;; esac
  case "${failures}" in ''|*[!0-9]*) return 1 ;; esac
  max_failures="$(property authentication.local.max.failures)"
  case "${max_failures}" in ''|*[!0-9]*) max_failures=0 ;; esac
  if [ "${active}" -eq 0 ]; then
    echo disabled
  elif [ "${max_failures}" -gt 0 ] && [ "${failures}" -ge "${max_failures}" ]; then
    echo locked
  else
    echo default
  fi
}

# Says why an admin account that still has the default password is left as it is: the password can
# only be changed by signing in, and no one can sign in to it, so neither can this script -- nor
# anyone trying admin/admin.
leave_unusable_admin() {
  if [ "$1" = locked ]; then
    log "WARNING: the admin account is locked out after too many failed sign-ins but still has Ambari's"
    log "default password. admin/admin cannot sign in while it is locked, so it is left as it is: give it"
    log "another password (Manage Ambari > Users) before unlocking it."
  else
    log "WARNING: the admin account is disabled but still has Ambari's default password. admin/admin"
    log "cannot sign in while it is disabled, so it is left as it is: give it another password (Manage"
    log "Ambari > Users) before enabling it again."
  fi
}

# On an empty database Ambari creates its "admin" account with the password "admin", which is in its
# public documentation, and nothing makes anyone change it. AMBARI_ADMIN_PASSWORD replaces it once the
# server is up and before the pod reports ready (set_admin_password below). Keeping the default is
# for development only and has to be asked for. Without a password the server is still started when
# the admin account no longer accepts admin/admin: an installation whose password was changed before
# this image asked for one has nothing left to replace, and refusing would only take its console down
# when the image is upgraded. A new database, or an account that still accepts admin/admin, is refused.
ALLOW_DEFAULT_ADMIN="${AMBARI_ADMIN_ALLOW_DEFAULT:-false}"
if [ "${ALLOW_DEFAULT_ADMIN}" != true ]; then
  if [ "${AMBARI_ADMIN_PASSWORD:-}" = admin ]; then
    log "AMBARI_ADMIN_PASSWORD is Ambari's public default password: choose another one."
    exit 1
  elif [ -z "${AMBARI_ADMIN_PASSWORD:-}" ]; then
    if ! ADMIN_STATE="$(admin_password_state)"; then
      log "AMBARI_ADMIN_PASSWORD is not set, and the database could not be read to tell whether the admin"
      log "account still has Ambari's default password."
      exit 1
    fi
    case "${ADMIN_STATE}" in
      changed)
        log "AMBARI_ADMIN_PASSWORD is not set, and none is needed: the administrator password is not the default one" ;;
      disabled|locked)
        leave_unusable_admin "${ADMIN_STATE}" ;;
      *)
        log "AMBARI_ADMIN_PASSWORD is not set: the first administrator password is required."
        log "Set AMBARI_ADMIN_ALLOW_DEFAULT=true to keep admin/admin instead (development only)."
        exit 1 ;;
    esac
  fi
fi
# Only this script needs the password: the server and everything it starts are not given it.
export -n AMBARI_ADMIN_PASSWORD

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

# The audit log -- sign-ins, refused access, changes to users, groups and privileges -- has a file of
# its own and never reaches ambari-server.log, and the container's filesystem goes with the
# container. Copy it to the container's output, where the cluster's log collection picks it up, with
# every line tagged so it can be routed apart from the server log. The copy starts where the file
# ends before the server starts: nothing this server writes is missed, and lines an earlier container
# already sent, kept on a logs volume, are not sent twice. That point is measured here rather than
# left to "tail -n 0", which starts wherever the file ends when tail gets round to opening it -- on a
# busy node, after the server's first lines. -F follows the file across rotation, reading each new
# file from its start.
touch "${AUDIT_LOG}" 2>/dev/null || true
AUDIT_FROM=$(( $(stat -c %s "${AUDIT_LOG}" 2>/dev/null || echo 0) + 1 ))
tail -c "+${AUDIT_FROM}" -F "${AUDIT_LOG}" > >(sed -u 's/^/[ambari-audit] /') &
AUDIT_PID=$!

# ambari-server start daemonises and returns, so the container needs something to hold PID 1 and a
# way to notice the server dying. Tail the log, and exit when the server process is gone so the pod
# restarts rather than sitting there looking healthy with nothing running.
log "starting the server"
# When start fails the container is replaced and its filesystem, including the only copy of the
# server's own log, goes with it -- so the pod logs would show the failure and none of the reason.
if ! ambari-server start --skip-database-check </dev/null; then
  log "the server did not come up. Its log, which this container is about to take with it:"
  tail -n 200 "${LOG}" 2>/dev/null || true
  tail -n 60 "${LOG_DIR}/ambari-server.out" 2>/dev/null || true
  stop_followers
  exit 1
fi

for _ in $(seq 1 30); do [ -f "$LOG" ] && break; sleep 1; done

tail -F "$LOG" &
TAIL_PID=$!

# Whether the server process is still there. The process itself is watched rather than "ambari-server
# status" run every few seconds: that starts a Python interpreter and reads the server's configuration
# each time, to learn whether one PID is alive. (status does work in this image, for an operator in
# "oc exec": it needs ps, which is why procps-ng is installed.)
server_running() {
  local pid
  pid="$(cat "${PIDFILE}" 2>/dev/null || true)"
  [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null
}

# The server's own API, on the loopback: plain HTTP on client.api.port, or, once api.ssl is on, HTTPS
# only, on client.api.ssl.port. The certificate names the route host rather than 127.0.0.1, hence -k;
# the request never leaves the pod.
API_PORT="$(property client.api.port)"
API_URL="http://127.0.0.1:${API_PORT:-8080}"
CURL_TLS=()
if [ "$(property api.ssl)" = true ]; then
  API_PORT="$(property client.api.ssl.port)"
  API_URL="https://127.0.0.1:${API_PORT:-8443}"
  CURL_TLS=(-k)
fi

# Asks the server to change the admin password from the default to AMBARI_ADMIN_PASSWORD, so that the
# server stores it the way it will check it. The JSON is built by python, which escapes whatever the
# password contains, and reaches curl on its standard input: the password is never on a command line,
# where anyone listing the processes would see it, nor in the logs. Prints the HTTP status, 000 when
# nothing answered, and leaves the server's answer in the file named by $1.
put_admin_password() {
  AMBARI_ADMIN_PASSWORD="${AMBARI_ADMIN_PASSWORD}" python3 -c '
import json, os, sys
json.dump({"Users": {"old_password": "admin", "password": os.environ["AMBARI_ADMIN_PASSWORD"]}}, sys.stdout)' \
    | curl -s "${CURL_TLS[@]}" --connect-timeout 5 --max-time 10 -o "$1" -w '%{http_code}' \
        -u admin:admin -H 'X-Requested-By: ambari' -X PUT --data-binary @- \
        "${API_URL}/api/v1/users/admin" \
    || true
}

# The reason the server gave in the answer in the file named by $1, on one line, with the password
# masked should the server ever quote it back.
api_message() {
  AMBARI_ADMIN_PASSWORD="${AMBARI_ADMIN_PASSWORD}" python3 -c '
import json, os, sys
text = open(sys.argv[1], errors="replace").read()
try:
    text = str(json.loads(text).get("message", text))
except Exception:
    pass
text = " ".join(text.replace(os.environ["AMBARI_ADMIN_PASSWORD"], "********").split())
print(text[:300] or "no message")' "$1" 2>/dev/null || echo "no message"
}

# Replaces Ambari's default administrator password with AMBARI_ADMIN_PASSWORD, once. When the account
# has any other password -- set on an earlier start, or by someone since -- it is left alone: the
# Secret sets the first password and never takes over from one changed in the UI. So is an account
# that is disabled or locked out, with a warning: admin/admin cannot sign in to it. Returns non-zero
# when the password could not be set, so that the container does not go on serving admin/admin.
set_admin_password() {
  if [ -z "${AMBARI_ADMIN_PASSWORD:-}" ] || [ "${AMBARI_ADMIN_PASSWORD}" = admin ]; then
    # Unless the default was allowed, the server was started without a password only because the
    # admin account no longer accepted admin/admin (checked above): there is nothing to set.
    if [ "${ALLOW_DEFAULT_ADMIN}" = true ]; then
      log "WARNING: AMBARI_ADMIN_ALLOW_DEFAULT=true and no other administrator password was given: this"
      log "server accepts admin/admin until someone changes it. Development only."
    fi
    return 0
  fi
  local deadline state status waiting=""
  deadline=$(( $(date +%s) + ADMIN_TIMEOUT ))
  while :; do
    if ! server_running; then
      log "the server stopped before the administrator password could be set"
      return 1
    fi
    if ! state="$(admin_password_state)"; then
      log "could not read the administrator account from the database; trying again"
    elif [ "${state}" = changed ]; then
      log "the administrator password is not the default one; leaving it as it is"
      return 0
    elif [ "${state}" != default ]; then
      leave_unusable_admin "${state}"
      return 0
    else
      status="$(put_admin_password "${ADMIN_RESPONSE}")"
      case "${status}" in
        2??)
          if [ "$(admin_password_state)" = changed ]; then
            log "set the administrator password from AMBARI_ADMIN_PASSWORD"
            return 0
          fi
          log "the server accepted the administrator password, but the database does not show it changed"
          return 1 ;;
        000|502|503|504)
          if [ -z "${waiting}" ]; then
            log "waiting for the server's API to set the administrator password"
            waiting=1
          fi ;;
        *)
          # Someone may have changed the password in the meantime, which makes the old one wrong, or
          # disabled the account, which turns admin/admin away.
          state="$(admin_password_state)" || state=""
          case "${state}" in
            changed)
              log "the administrator password is not the default one; leaving it as it is"
              return 0 ;;
            disabled|locked)
              leave_unusable_admin "${state}"
              return 0 ;;
          esac
          case "${status}" in
            401|403)
              log "the server turned admin/admin away (HTTP ${status}), although the database shows the admin"
              log "account enabled, not locked out and with the default password: $(api_message "${ADMIN_RESPONSE}")" ;;
            *)
              log "the server refused to set the administrator password (HTTP ${status}): $(api_message "${ADMIN_RESPONSE}")" ;;
          esac
          return 1 ;;
      esac
    fi
    if [ "$(date +%s)" -ge "${deadline}" ]; then
      log "the administrator password could not be set within ${ADMIN_TIMEOUT}s"
      return 1
    fi
    # Waited for in the background, like the watch below, so that a stop is not held up.
    sleep 5 &
    wait $!
  done
}

ADMIN_RESPONSE="$(mktemp)"
if set_admin_password; then
  rm -f "${ADMIN_RESPONSE}"
  touch "${ADMIN_MARKER}"
else
  rm -f "${ADMIN_RESPONSE}"
  log "exiting rather than serve with an administrator password that may still be admin/admin"
  stop_server
  stop_followers
  exit 1
fi

while server_running; do
  # In the background and waited for, because bash runs a trap only once the command in the
  # foreground has finished: a plain "sleep 10" would hold the stop up by as much as ten seconds.
  sleep 10 &
  wait $!
done

log "the server is no longer running; exiting so the pod is restarted"
stop_followers
exit 1
