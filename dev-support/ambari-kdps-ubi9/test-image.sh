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
# Checks how a freshly built image starts and stops, before it is pushed:
#   * "ambari-server status" and "ambari-server stop" find a running server (they need ps);
#   * on SIGTERM the entrypoint signals the server and waits for it, and kills it only once
#     AMBARI_STOP_TIMEOUT has passed; a SIGTERM during start-up is not ignored;
#   * the audit log reaches the container's output, every line prefixed with "[ambari-audit] ",
#     from the server's first line on even when tail is slow to open the file, and lines kept on a
#     logs volume from an earlier container are not sent again;
#   * the container exits non-zero when the server dies on its own;
#   * the default admin/admin is replaced by AMBARI_ADMIN_PASSWORD through the server's API before
#     the readiness check the chart runs can pass, whatever characters the password holds, without
#     the password reaching the output; over HTTPS when the server serves only that; a restart leaves
#     a changed password alone; a password the server refuses, an API that never answers, a database
#     that cannot be read or admin/admin turned away from an account that should accept it end the
#     container rather than leave it serving admin/admin; an admin account that is disabled or locked
#     out, which admin/admin cannot sign in to, is left alone with a warning, also when that happens
#     while the password is being set; "admin" is refused, and so is no password at all unless
#     AMBARI_ADMIN_ALLOW_DEFAULT=true or the admin account no longer accepts admin/admin (an existing
#     installation moved to this image), and a database that cannot be read then also stops it;
#   * the database schema: an empty database gets the image's DDL in one transaction that stops at
#     the first error, once, and a load that fails ends the container with psql's error; a schema of
#     the image's Ambari line starts as it is; one of an earlier line ends the container with both
#     versions and the procedure, changing nothing, unless AMBARI_SCHEMA_UPGRADE=true, which runs
#     Ambari's schema upgrade as the container's own user, once, with what it did and the warnings it
#     logged in the output -- or ends the container with why it failed, and runs again on the next
#     start; one of a later line ends it, as nothing downgrades a schema; a schema version that cannot
#     be read ends it too. ambari.properties names the PostgreSQL schema the tables are found in,
#     whatever setup recorded, before the upgrade looks for them there.
#
# The entrypoint runs as PID 1 under the arbitrary UID OpenShift would use, with the image's own
# bash, tail, sed, python3 and curl (tail behind a wrapper that can make it late). Only the server,
# its schema upgrade and its database are stand-ins -- an "ambari-server" stub on PATH starts a
# stand-in server with a stand-in of its REST API, a "java" stub stands in for the schema upgrade, and
# a "psql" stub answers from the schema version and the password those last set -- so the whole run
# takes a few minutes and needs neither a database nor a network. test-image-postgres.sh checks the
# schema load and upgrade against a real PostgreSQL.
#
# Usage: test-image.sh <image>

set -uo pipefail

IMAGE="${1:?usage: $0 <image>}"
NAME="ambari-kdps-test-$$"
WORK="$(mktemp -d)"
FAILED=0

cleanup() {
  docker rm -f "${NAME}" "${NAME}-db" >/dev/null 2>&1 || true
  rm -rf "${WORK}"
}
trap cleanup EXIT

ok()   { echo "ok   - $*"; }
fail() { echo "FAIL - $*"; FAILED=1; }

expect_contains() {
  if grep -qF -- "$2" <<<"$3"; then ok "$1"; else fail "$1: expected '$2'"; fi
}

expect_absent() {
  if grep -qF -- "$2" <<<"$3"; then fail "$1: did not expect '$2'"; else ok "$1"; fi
}

wait_for_log() {
  local needle=$1 logs
  for _ in $(seq 1 120); do
    logs="$(docker logs "${NAME}" 2>&1)"
    grep -qF -- "${needle}" <<<"${logs}" && return 0
    sleep 0.5
  done
  fail "timed out waiting for '${needle}' in the container's output"
  tail -n 20 <<<"${logs}"
  return 1
}

# The pod's passwd entry, as the chart's passwd-shim init container writes it.
docker run --rm --network=none --entrypoint cat "${IMAGE}" /etc/passwd > "${WORK}/passwd"
echo "ambari:x:1001:0:Ambari:/var/lib/ambari-server:/sbin/nologin" >> "${WORK}/passwd"

# The image's Ambari version, its line (the first three numbers) and the schema version its DDL
# records: the messages about the schema name them.
SERVER_VERSION="$(docker run --rm --network=none --entrypoint cat "${IMAGE}" /var/lib/ambari-server/resources/version | tr -d '[:space:]')"
SERVER_LINE="$(sed -E 's/^([0-9]+\.[0-9]+\.[0-9]+).*/\1/' <<<"${SERVER_VERSION}")"
DDL_VERSION="$(docker run --rm --network=none --entrypoint sed "${IMAGE}" -n "s/^('version','\([^']*\)');.*/\1/p" \
  /var/lib/ambari-server/resources/Ambari-DDL-Postgres-CREATE.sql)"

mkdir "${WORK}/stubs"
cat > "${WORK}/stubs/ambari-server" <<'EOF'
#!/bin/bash
# Stand-in launcher: setup does nothing; start runs the stand-in server in the background and
# records its PID where the real launcher does.
if [ "${1:-}" = start ]; then
  /stubs/fake-server </dev/null >/dev/null 2>&1 &
  echo $! > /var/run/ambari-server/ambari-server.pid
fi
exit 0
EOF
cat > "${WORK}/stubs/psql" <<'EOF'
#!/bin/bash
# The database holds the schema the image's own DDL creates, with the version that DDL records, unless
# FAKE_SCHEMA_VERSION says otherwise: "none" for an empty database, or the version of another Ambari
# line. A schema loaded or upgraded since is kept in /tmp/fake-ambari/schema-version, and each load
# and upgrade is listed in /tmp/fake-ambari/schema.log. Its tables are in the PostgreSQL schema
# FAKE_PG_SCHEMA, "public" by default, where the database user's search_path finds them.
# The admin account has the password the database was seeded with (FAKE_ADMIN_PASSWORD, "admin" by
# default) for as long as the stand-in API has not changed it, and the entrypoint has to recognise
# the default by the digest the image's own DDL stores for "admin". While it has it, the account is
# returned with whether it is enabled and how many sign-ins to it failed in a row, as the stand-in
# server keeps them -- or, before the server has started, as the database was seeded.
# Anything else is refused, so that a query this stub does not know cannot pass unnoticed.
ddl=/var/lib/ambari-server/resources/Ambari-DDL-Postgres-CREATE.sql
state=/tmp/fake-ambari
mkdir -p -m 0777 "${state}"
ddl_version() { sed -n "s/^('version','\([^']*\)');.*/\1/p" "${ddl}" | head -n 1; }
schema() {
  if [ -f "${state}/schema-version" ]; then
    cat "${state}/schema-version"
  elif [ "${FAKE_SCHEMA_VERSION:-}" != none ]; then
    echo "${FAKE_SCHEMA_VERSION:-$(ddl_version)}"
  fi
}
stops_at_first_error() {
  case "$*" in
    *"-v ON_ERROR_STOP=1"*) ;;
    *) echo "psql stub: the query does not stop at the first error: $*" >&2; exit 3 ;;
  esac
}
case "$*" in
  *"select to_regclass('metainfo') is not null"*)
    stops_at_first_error "$@"
    if [ "${FAKE_SCHEMA_READ_FAILS:-}" = 1 ]; then
      echo 'psql: error: connection to server at "127.0.0.1", port 5432 failed: FATAL:  password authentication failed for user "ambari"' >&2
      exit 2
    fi
    if [ -n "$(schema)" ]; then echo t; else echo f; fi ;;
  *"select metainfo_value from metainfo where metainfo_key = 'version'"*)
    stops_at_first_error "$@"
    schema ;;
  *"select n.nspname from pg_class c join pg_namespace n on n.oid = c.relnamespace"*"where c.oid = to_regclass('metainfo')"*)
    stops_at_first_error "$@"
    if [ -n "$(schema)" ]; then echo "${FAKE_PG_SCHEMA:-public}"; fi ;;
  *"--single-transaction -f -"*)
    # The schema load: the whole DDL in one transaction, so without the DDL's own BEGIN and COMMIT,
    # which would end that transaction half-way.
    stops_at_first_error "$@"
    cat > "${state}/loaded.sql"
    # Compared by digest: the image has no cmp or diff.
    if [ "$(grep -vxE '(BEGIN|COMMIT);' "${ddl}" | sha256sum)" != "$(sha256sum < "${state}/loaded.sql")" ]; then
      echo "psql stub: the schema load is not the image's DDL without its own BEGIN and COMMIT" >&2
      exit 3
    fi
    if [ "${FAKE_SCHEMA_LOAD_FAILS:-}" = 1 ]; then
      echo 'psql:<stdin>:25: ERROR:  permission denied for schema public' >&2
      exit 3
    fi
    ddl_version > "${state}/schema-version"
    echo load >> "${state}/schema.log" ;;
  *user_authentication*)
    if [ "${FAKE_PSQL_FAILS:-}" = 1 ]; then echo "psql: error: connection to server failed" >&2; exit 2; fi
    key="$(sed -n "s/.*'LOCAL', '\([0-9a-f]*\)'.*/\1/p" /var/lib/ambari-server/resources/Ambari-DDL-Postgres-CREATE.sql | head -n 1)"
    case "$*" in
      *"'${key:-no digest in the DDL}'"*) ;;
      *) echo "psql stub: the query does not look for the digest the DDL stores" >&2; exit 3 ;;
    esac
    case "$*" in
      *"select u.active, u.consecutive_failures"*) ;;
      *) echo "psql stub: the query does not ask for the columns this stub answers with" >&2; exit 3 ;;
    esac
    if [ "$(cat /tmp/fake-ambari/password 2>/dev/null || printf '%s' "${FAKE_ADMIN_PASSWORD:-admin}")" = admin ]; then
      echo "$(cat /tmp/fake-ambari/active 2>/dev/null || echo "${FAKE_ADMIN_ACTIVE:-1}") ${FAKE_ADMIN_FAILURES:-0}"
    fi ;;
  *) echo "psql stub: a query this stub does not know: $*" >&2; exit 3 ;;
esac
EOF
mkdir -p "${WORK}/stubs/jdk/bin"
cat > "${WORK}/stubs/jdk/bin/java" <<'EOF'
#!/bin/bash
# Stand-in for the JVM running Ambari's schema upgrade, which the entrypoint finds through
# ambari.java.home. It checks that it is started the way "ambari-server upgrade" starts it, from the
# server's configuration and libraries, and not as root, and that ambari.properties names the
# PostgreSQL schema the stand-in database keeps its tables in: the real upgrade looks for tables there
# only, and takes them all to be missing anywhere else. Then it upgrades the stand-in database to the
# server's Ambari line and logs that to the server's log among lines of the server's own start-up, with
# a warning of the code that runs its SQL, as the real one does -- or, with FAKE_UPGRADE_FAILS=1, logs
# why it could not and fails; with FAKE_UPGRADE_FAILS=once, only the first time, as when the cause is
# removed before the next start. Like the real JVM it also prints messages of its own, which belong in
# the upgrade's own log file.
log=/var/log/ambari-server/ambari-server.log
state=/tmp/fake-ambari
case "$*" in
  *"-cp /etc/ambari-server/conf:/usr/lib/ambari-server/*"*" org.apache.ambari.server.upgrade.SchemaUpgradeHelper") ;;
  *) echo "java stub: not Ambari's schema upgrade: $*" >&2; exit 3 ;;
esac
if [ "$(id -u)" -eq 0 ]; then echo "java stub: the schema upgrade runs as root" >&2; exit 3; fi
if [ "${ROOT-unset}" != "" ]; then echo "java stub: ROOT is not set, empty, as the launcher sets it" >&2; exit 3; fi
recorded="$(sed -n 's/^server.jdbc.postgres.schema=//p' /etc/ambari-server/conf/ambari.properties | tail -n 1)"
if [ "${recorded}" != "${FAKE_PG_SCHEMA:-public}" ]; then
  echo "java stub: server.jdbc.postgres.schema is '${recorded}', but the tables are in '${FAKE_PG_SCHEMA:-public}'" >&2
  exit 3
fi
mkdir -p -m 0777 "${state}"
stamp() { date '+%Y-%m-%d %H:%M:%S.000'; }
echo "SLF4J: Class path contains multiple SLF4J providers."
echo "$(stamp) INFO  [main] o.a.a.s.s.StackManager:151 - the server's own start-up" >> "${log}"
echo "$(stamp) INFO  [main] o.a.a.s.u.SchemaUpgradeHelper:472 - Upgrading schema from source version = $(cat "${state}/schema-version" 2>/dev/null || echo "${FAKE_SCHEMA_VERSION}")" >> "${log}"
if [ "${FAKE_UPGRADE_FAILS:-}" = 1 ] || { [ "${FAKE_UPGRADE_FAILS:-}" = once ] && [ ! -f "${state}/upgrade-failed" ]; }; then
  touch "${state}/upgrade-failed"
  echo "$(stamp) ERROR [main] o.a.a.s.u.SchemaUpgradeHelper:512 - Exception occurred during upgrade, failed" >> "${log}"
  echo 'Exception in thread "main" org.apache.ambari.server.AmbariException: ERROR: relation "oidc_client" already exists' >&2
  exit 1
fi
sed -E 's/^([0-9]+\.[0-9]+\.[0-9]+).*/\1/' /var/lib/ambari-server/resources/version > "${state}/schema-version"
echo upgrade >> "${state}/schema.log"
echo "$(stamp) WARN  [main] o.a.a.s.o.DBAccessorImpl:1086 - Primary key doesn't exists for stand_in table, skipping" >> "${log}"
echo "$(stamp) INFO  [main] o.a.a.s.u.SchemaUpgradeHelper:502 - Upgrade successful." >> "${log}"
EOF
cat > "${WORK}/stubs/tail" <<'EOF'
#!/bin/bash
# The image's own tail. With TAIL_DELAY set it opens its file that much later, as it can on a busy
# node.
if [ -n "${TAIL_DELAY:-}" ]; then sleep "${TAIL_DELAY}"; fi
exec /usr/bin/tail "$@"
EOF
cat > "${WORK}/stubs/fake-server" <<'EOF'
#!/bin/bash
log=/var/log/ambari-server/ambari-server.log
# A new database seeds admin/admin, or FAKE_ADMIN_PASSWORD for one whose password was changed
# before; a restarted container keeps what its API was told since. Open to all, so that whoever runs
# the test can delete what lands in a directory it mounted.
mkdir -p -m 0777 /tmp/fake-ambari
[ -f /tmp/fake-ambari/password ] || printf '%s' "${FAKE_ADMIN_PASSWORD:-admin}" > /tmp/fake-ambari/password
[ -f /tmp/fake-ambari/active ] || echo "${FAKE_ADMIN_ACTIVE:-1}" > /tmp/fake-ambari/active
/stubs/fake-api &
api=$!
on_term() { echo "fake server: shutdown hooks ran" >> "${log}"; kill "${api}"; exit 0; }
if [ "${FAKE_SERVER_IGNORES_TERM:-}" = 1 ]; then trap '' TERM; else trap on_term TERM; fi
echo "fake server: started" >> "${log}"
echo "audit: user login" >> /var/log/ambari-server/ambari-audit.log
for _ in $(seq 1 "${FAKE_SERVER_LIFETIME:-600}"); do sleep 1; done
echo "fake server: exiting on its own" >> "${log}"
kill "${api}"
EOF
cat > "${WORK}/stubs/fake-api" <<'EOF'
#!/usr/bin/python3
# Stand-in for the server's REST API, listening where ambari.properties says the server does: the
# login page, and the call that changes the admin password, checked the way the server checks it.
# It records each answer in /tmp/fake-ambari/api.log, never the password.
import base64, json, os, ssl, subprocess
from http.server import BaseHTTPRequestHandler, HTTPServer

STATE = "/tmp/fake-ambari"
props = {}
with open("/etc/ambari-server/conf/ambari.properties") as f:
    for line in f:
        if "=" in line and not line.startswith("#"):
            key, value = line.rstrip("\n").split("=", 1)
            props[key] = value
tls = props.get("api.ssl") == "true"
port = int(props.get("client.api.ssl.port", "8443") if tls else props.get("client.api.port", "8080"))
unavailable = int(os.environ.get("FAKE_API_UNAVAILABLE_FOR", "0"))


def record(line):
    with open(STATE + "/api.log", "a") as f:
        f.write(line + "\n")


def admin_can_sign_in():
    with open(STATE + "/active") as f:
        active = f.read().strip() == "1"
    max_failures = int(props.get("authentication.local.max.failures") or "0")
    failures = int(os.environ.get("FAKE_ADMIN_FAILURES", "0"))
    return active and not (max_failures > 0 and failures >= max_failures)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def answer(self, code, message=None):
        data = json.dumps({"status": code, "message": message}).encode() if message else b""
        self.send_response(code)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.answer(200)

    def do_PUT(self):
        global unavailable
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if unavailable > 0:
            unavailable -= 1
            record("PUT 503")
            return self.answer(503, "Service Unavailable")
        with open(STATE + "/password", encoding="utf-8") as f:
            current = f.read()
        if self.path != "/api/v1/users/admin":
            record("PUT 404 " + self.path)
            return self.answer(404, "not found")
        if not self.headers.get("X-Requested-By"):
            record("PUT 400 no X-Requested-By")
            return self.answer(400, "CSRF protection is turned on. X-Requested-By HTTP header is required.")
        if os.environ.get("FAKE_ADMIN_DISABLED_ON_PUT") == "1":
            # Someone disables the account just before this call.
            with open(STATE + "/active", "w") as f:
                f.write("0\n")
        if self.headers.get("Authorization") != "Basic " + base64.b64encode(("admin:" + current).encode()).decode():
            record("PUT 401")
            return self.answer(401, "Authentication required")
        # The server checks the account only once the password matches, and then turns it away
        # all the same when it is disabled or locked out.
        if not admin_can_sign_in() or os.environ.get("FAKE_API_REFUSES_ADMIN") == "1":
            record("PUT 401 turned away")
            return self.answer(401, "Unable to sign in. Invalid username/password combination.")
        users = json.loads(body)["Users"]
        if users.get("old_password") != current:
            record("PUT 500 wrong current password")
            return self.answer(500, "Wrong current password provided")
        if os.environ.get("FAKE_API_REJECT") == "1":
            record("PUT 400 policy")
            return self.answer(400, "The password does not meet the Ambari user password policy : '%s' is too short" % users["password"])
        with open(STATE + "/password", "w", encoding="utf-8") as f:
            f.write(users["password"])
        record("PUT 200 " + ("https" if tls else "http"))
        self.answer(200)


server = HTTPServer(("127.0.0.1", port), Handler)
if tls:
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                    "-subj", "/CN=ambari.apps.example.com",
                    "-keyout", STATE + "/tls.key", "-out", STATE + "/tls.crt"],
                   check=True, capture_output=True)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(STATE + "/tls.crt", STATE + "/tls.key")
    server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
EOF
chmod 0755 "${WORK}/stubs/"* "${WORK}/stubs/jdk/bin/java"

# Something listening where the entrypoint waits for PostgreSQL. The containers under test share its
# network namespace, so the address is its loopback.
docker run -d --name "${NAME}-db" --network=none --entrypoint python3 "${IMAGE}" -c '
import socket
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", 5432))
s.listen(16)
conns = []
while True:
    conns.append(s.accept())
' >/dev/null

# Options given after the defaults win, so a test can override or empty any of them.
run_entrypoint() {
  docker run -d --name "${NAME}" --network "container:${NAME}-db" --user 1001:0 \
    -v "${WORK}/passwd:/etc/passwd:ro,Z" -v "${WORK}/stubs:/stubs:ro,Z" \
    -e PATH=/stubs:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin \
    -e AMBARI_DB_HOST=127.0.0.1 -e AMBARI_DB_PASSWORD=unused -e AMBARI_SECURITY_MASTER_KEY=unused \
    -e AMBARI_ADMIN_PASSWORD=a-first-password \
    "$@" "${IMAGE}" >/dev/null
}

# The readiness check the ambari chart runs, over HTTP or HTTPS.
readiness_probe() {
  docker exec "${NAME}" sh -c "test -f /tmp/admin-bootstrapped && curl -fsS ${2:-} -o /dev/null $1" >/dev/null 2>&1
}

# Waits for the container to exit on its own and sets RC and LOGS.
wait_for_exit() {
  local running=true
  for _ in $(seq 1 60); do
    running="$(docker inspect -f '{{.State.Running}}' "${NAME}")"
    [ "${running}" = false ] && break
    sleep 1
  done
  RC=$(docker inspect -f '{{.State.ExitCode}}' "${NAME}")
  LOGS="$(docker logs "${NAME}" 2>&1)"
  [ "${running}" = false ] || { fail "the container is still running"; return 1; }
}

# Sends SIGTERM the way the kubelet does and sets ELAPSED, RC and LOGS.
stop_container() {
  local start
  start=$(date +%s)
  docker stop -t 60 "${NAME}" >/dev/null
  ELAPSED=$(( $(date +%s) - start ))
  RC=$(docker inspect -f '{{.State.ExitCode}}' "${NAME}")
  LOGS="$(docker logs "${NAME}" 2>&1)"
  docker rm "${NAME}" >/dev/null
}

echo "==> ambari-server status and stop find the running server"
out="$(docker run --rm --network=none --user 1001:0 -v "${WORK}/passwd:/etc/passwd:ro,Z" \
  --entrypoint bash "${IMAGE}" -c '
    sleep 300 &
    echo $! > /var/run/ambari-server/ambari-server.pid
    ambari-server status; echo "status rc=$?"
    ambari-server stop; echo "stop rc=$?"
    kill -0 $! 2>/dev/null && echo "the stand-in is still running"' 2>&1)"
expect_contains "status reports the server running" "Ambari Server running" "${out}"
expect_contains "status succeeds" "status rc=0" "${out}"
expect_contains "stop succeeds" "stop rc=0" "${out}"
expect_absent "stop stopped it" "the stand-in is still running" "${out}"
expect_absent "ps is there" "No such file or directory: 'ps'" "${out}"

echo "==> SIGTERM: the server is asked to stop and given the time to do it"
run_entrypoint -e AMBARI_STOP_TIMEOUT=20
if wait_for_log "fake server: started" && wait_for_log "[ambari-audit] audit: user login"; then
  stop_container
  expect_contains "the server's shutdown hooks ran" "fake server: shutdown hooks ran" "${LOGS}"
  expect_contains "the entrypoint waited for it" "[entrypoint] the server has stopped" "${LOGS}"
  expect_absent "it was not killed" "killing it" "${LOGS}"
  [ "${RC}" = 0 ] && ok "exit code 0" || fail "exit code ${RC}, expected 0"
  [ "${ELAPSED}" -lt 15 ] && ok "stopped in ${ELAPSED}s" || fail "took ${ELAPSED}s to stop"
  expect_contains "the audit line is in the output, prefixed" "[ambari-audit] audit: user login" "${LOGS}"
  expect_absent "the server log is not tagged as audit" "[ambari-audit] fake server" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> SIGTERM: a server that does not stop is killed after AMBARI_STOP_TIMEOUT"
run_entrypoint -e AMBARI_STOP_TIMEOUT=3 -e FAKE_SERVER_IGNORES_TERM=1
if wait_for_log "fake server: started"; then
  stop_container
  expect_contains "killed after the timeout" "[entrypoint] the server is still running after 3s; killing it" "${LOGS}"
  [ "${RC}" = 0 ] && ok "exit code 0" || fail "exit code ${RC}, expected 0"
  [ "${ELAPSED}" -ge 3 ] && [ "${ELAPSED}" -lt 15 ] && ok "stopped in ${ELAPSED}s" \
    || fail "took ${ELAPSED}s to stop, expected 3 to 15"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> SIGTERM during start-up is not ignored"
docker run -d --name "${NAME}" --network=none --user 1001:0 \
  -v "${WORK}/passwd:/etc/passwd:ro,Z" -v "${WORK}/stubs:/stubs:ro,Z" \
  -e PATH=/stubs:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin \
  -e AMBARI_DB_HOST=127.0.0.1 -e AMBARI_DB_PASSWORD=unused -e AMBARI_SECURITY_MASTER_KEY=unused \
  "${IMAGE}" >/dev/null
if wait_for_log "waiting for postgres"; then
  stop_container
  [ "${RC}" = 0 ] && ok "exit code 0" || fail "exit code ${RC}, expected 0"
  [ "${ELAPSED}" -lt 10 ] && ok "stopped in ${ELAPSED}s" || fail "took ${ELAPSED}s to stop"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> audit lines kept on a logs volume are not sent again"
mkdir "${WORK}/logs"
echo "audit: from an earlier container" > "${WORK}/logs/ambari-audit.log"
chmod -R a+rwX "${WORK}/logs"
run_entrypoint -v "${WORK}/logs:/var/log/ambari-server:Z"
if wait_for_log "[ambari-audit] audit: user login"; then
  stop_container
  expect_absent "the earlier line is not repeated" "[ambari-audit] audit: from an earlier container" "${LOGS}"
  expect_contains "the new line is sent" "[ambari-audit] audit: user login" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> the audit lines the server writes first are sent, however late tail opens the file"
# The stand-in writes its audit line as it starts; tail opens the file two seconds later.
run_entrypoint -e TAIL_DELAY=2
if wait_for_log "[ambari-audit] audit: user login"; then
  ok "the first audit line is sent"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> the server dying on its own ends the container with an error"
# Long enough for the administrator password to be set first, on a busy agent too: dying before that
# is checked on its own below.
run_entrypoint -e FAKE_SERVER_LIFETIME=10
running=true
for _ in $(seq 1 40); do
  running="$(docker inspect -f '{{.State.Running}}' "${NAME}")"
  [ "${running}" = false ] && break
  sleep 1
done
if [ "${running}" = false ]; then
  RC=$(docker inspect -f '{{.State.ExitCode}}' "${NAME}")
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "says why" "[entrypoint] the server is no longer running" "$(docker logs "${NAME}" 2>&1)"
else
  fail "the container is still running after the server exited"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

# Every character a JSON string or a shell could trip over.
PASSWORD='p@ss "w\ord" $(id) `id` ${HOME} '"'"'q'"'"' é'

echo "==> the default administrator password is replaced before the readiness check passes"
# A marker left over from an earlier run, as a container restarted in place would have.
mkdir "${WORK}/tmp"
touch "${WORK}/tmp/admin-bootstrapped"
chmod -R a+rwX "${WORK}/tmp"
run_entrypoint -v "${WORK}/tmp:/tmp:Z" -e "AMBARI_ADMIN_PASSWORD=${PASSWORD}" -e FAKE_API_UNAVAILABLE_FOR=3
if wait_for_log "[entrypoint] waiting for the server's API to set the administrator password"; then
  readiness_probe http://127.0.0.1:8080/ && fail "ready while the password is still admin/admin" \
    || ok "not ready while the password is still admin/admin"
fi
if wait_for_log "[entrypoint] set the administrator password from AMBARI_ADMIN_PASSWORD"; then
  [ "$(cat "${WORK}/tmp/fake-ambari/password")" = "${PASSWORD}" ] && ok "the API was given the password unaltered" \
    || fail "the API was given '$(cat "${WORK}/tmp/fake-ambari/password")'"
  [ "$(grep -c '^PUT 503' "${WORK}/tmp/fake-ambari/api.log")" = 3 ] && ok "retried while the API was unavailable" \
    || fail "unexpected API calls: $(cat "${WORK}/tmp/fake-ambari/api.log")"
  [ "$(grep -c '^PUT 200 http$' "${WORK}/tmp/fake-ambari/api.log")" = 1 ] && ok "changed once, over HTTP" \
    || fail "unexpected API calls: $(cat "${WORK}/tmp/fake-ambari/api.log")"
  readiness_probe http://127.0.0.1:8080/ && ok "ready once the password is changed" || fail "not ready after the change"
  expect_absent "the password is not in the output" "${PASSWORD}" "$(docker logs "${NAME}" 2>&1)"
  expect_absent "the password is not given to the server" "AMBARI_ADMIN_PASSWORD" \
    "$(docker exec "${NAME}" sh -c 'tr "\0" "\n" < /proc/$(cat /var/run/ambari-server/ambari-server.pid)/environ')"

  echo "==> a restart leaves the changed password alone"
  docker restart -t 30 "${NAME}" >/dev/null
  if wait_for_log "[entrypoint] the administrator password is not the default one; leaving it as it is"; then
    [ "$(grep -c '^PUT' "${WORK}/tmp/fake-ambari/api.log")" = 4 ] && ok "no further call to the API" \
      || fail "unexpected API calls: $(cat "${WORK}/tmp/fake-ambari/api.log")"
    [ "$(cat "${WORK}/tmp/fake-ambari/password")" = "${PASSWORD}" ] && ok "the password is unchanged" \
      || fail "the password changed on restart"
    for _ in $(seq 1 20); do readiness_probe http://127.0.0.1:8080/ && break; sleep 0.5; done
    readiness_probe http://127.0.0.1:8080/ && ok "ready again" || fail "not ready after the restart"
  fi
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> the password is set over HTTPS when the server serves only that"
run_entrypoint -e "AMBARI_EXTRA_PROPERTIES=$(printf 'api.ssl=true\nclient.api.ssl.port=8443')"
if wait_for_log "[entrypoint] set the administrator password from AMBARI_ADMIN_PASSWORD"; then
  [ "$(docker exec "${NAME}" cat /tmp/fake-ambari/api.log)" = "PUT 200 https" ] && ok "changed over HTTPS on 8443" \
    || fail "unexpected API calls: $(docker exec "${NAME}" cat /tmp/fake-ambari/api.log)"
  readiness_probe https://127.0.0.1:8443/ -k && ok "ready" || fail "not ready after the change"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> SIGTERM while waiting for the API stops the container at once"
run_entrypoint -e FAKE_API_UNAVAILABLE_FOR=1000
if wait_for_log "[entrypoint] waiting for the server's API to set the administrator password"; then
  stop_container
  [ "${RC}" = 0 ] && ok "exit code 0" || fail "exit code ${RC}, expected 0"
  [ "${ELAPSED}" -lt 15 ] && ok "stopped in ${ELAPSED}s" || fail "took ${ELAPSED}s to stop"
  expect_contains "the server's shutdown hooks ran" "fake server: shutdown hooks ran" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> a password the server refuses ends the container, and is not printed"
run_entrypoint -e "AMBARI_ADMIN_PASSWORD=${PASSWORD}" -e FAKE_API_REJECT=1
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "says why" "[entrypoint] the server refused to set the administrator password (HTTP 400): The password does not meet the Ambari user password policy : '********' is too short" "${LOGS}"
  expect_contains "and that it will not serve" "[entrypoint] exiting rather than serve with an administrator password that may still be admin/admin" "${LOGS}"
  expect_contains "the server was stopped" "fake server: shutdown hooks ran" "${LOGS}"
  expect_absent "the password is not in the output" "${PASSWORD}" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> an API that never answers ends the container after AMBARI_ADMIN_PASSWORD_TIMEOUT"
run_entrypoint -e FAKE_API_UNAVAILABLE_FOR=1000 -e AMBARI_ADMIN_PASSWORD_TIMEOUT=3
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "says why" "[entrypoint] the administrator password could not be set within 3s" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> a database that cannot be read ends the container after AMBARI_ADMIN_PASSWORD_TIMEOUT"
run_entrypoint -e FAKE_PSQL_FAILS=1 -e AMBARI_ADMIN_PASSWORD_TIMEOUT=3
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "says why" "[entrypoint] could not read the administrator account from the database; trying again" "${LOGS}"
  expect_contains "and gives up" "[entrypoint] the administrator password could not be set within 3s" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

# An admin account admin/admin cannot sign in to: the container serves, and the API saw what is given.
expect_left_alone() {
  for _ in $(seq 1 20); do readiness_probe http://127.0.0.1:8080/ && break; sleep 0.5; done
  readiness_probe http://127.0.0.1:8080/ && ok "ready" || fail "not ready"
  [ "$(docker inspect -f '{{.State.Running}}' "${NAME}")" = true ] && ok "still running" || fail "the container exited"
  [ "$(docker exec "${NAME}" cat /tmp/fake-ambari/api.log 2>/dev/null)" = "$1" ] && ok "API calls: ${1:-none}" \
    || fail "unexpected API calls: $(docker exec "${NAME}" cat /tmp/fake-ambari/api.log 2>/dev/null)"
  [ "$(docker exec "${NAME}" cat /tmp/fake-ambari/password)" = admin ] && ok "the password was not changed" \
    || fail "the password was changed"
}

echo "==> a disabled admin account with the default password is left alone, with a warning"
run_entrypoint -e FAKE_ADMIN_ACTIVE=0
if wait_for_log "[entrypoint] WARNING: the admin account is disabled but still has Ambari's default password."; then
  expect_left_alone ""
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> a locked-out admin account with the default password is left alone, with a warning"
run_entrypoint -e "AMBARI_EXTRA_PROPERTIES=authentication.local.max.failures=3" -e FAKE_ADMIN_FAILURES=3
if wait_for_log "[entrypoint] WARNING: the admin account is locked out after too many failed sign-ins but still has Ambari's"; then
  expect_left_alone ""
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> failed sign-ins below the lockout limit do not stop the change"
run_entrypoint -e "AMBARI_EXTRA_PROPERTIES=authentication.local.max.failures=3" -e FAKE_ADMIN_FAILURES=2
if wait_for_log "[entrypoint] set the administrator password from AMBARI_ADMIN_PASSWORD"; then
  [ "$(docker exec "${NAME}" cat /tmp/fake-ambari/api.log)" = "PUT 200 http" ] && ok "changed once" \
    || fail "unexpected API calls: $(docker exec "${NAME}" cat /tmp/fake-ambari/api.log)"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> an admin account disabled while its password is being set is left alone, with a warning"
run_entrypoint -e FAKE_ADMIN_DISABLED_ON_PUT=1
if wait_for_log "[entrypoint] WARNING: the admin account is disabled but still has Ambari's default password."; then
  expect_left_alone "PUT 401 turned away"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> admin/admin turned away from an account that should accept it ends the container, and says why"
run_entrypoint -e FAKE_API_REFUSES_ADMIN=1
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "says why" "[entrypoint] the server turned admin/admin away (HTTP 401), although the database shows the admin" "${LOGS}"
  expect_contains "with the server's reason" "with the default password: Unable to sign in. Invalid username/password combination." "${LOGS}"
  expect_contains "and that it will not serve" "[entrypoint] exiting rather than serve with an administrator password that may still be admin/admin" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> the server dying before the password is set ends the container with an error"
run_entrypoint -e FAKE_SERVER_LIFETIME=2 -e FAKE_API_UNAVAILABLE_FOR=1000
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "says why" "[entrypoint] the server stopped before the administrator password could be set" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> no administrator password, on a new database: the server is not started"
run_entrypoint -e AMBARI_ADMIN_PASSWORD=
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "says why" "[entrypoint] AMBARI_ADMIN_PASSWORD is not set: the first administrator password is required." "${LOGS}"
  expect_absent "the server was not started" "[entrypoint] starting the server" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> no administrator password, on a database whose password was changed before: the server starts"
# An installation made before the image asked for a password, its password changed in the UI, moved
# to this image without one.
run_entrypoint -e AMBARI_ADMIN_PASSWORD= -e FAKE_ADMIN_PASSWORD=changed-in-the-ui
if wait_for_log "[entrypoint] AMBARI_ADMIN_PASSWORD is not set, and none is needed: the administrator password is not the default one"; then
  for _ in $(seq 1 20); do readiness_probe http://127.0.0.1:8080/ && break; sleep 0.5; done
  readiness_probe http://127.0.0.1:8080/ && ok "ready" || fail "not ready"
  [ "$(docker inspect -f '{{.State.Running}}' "${NAME}")" = true ] && ok "still running" || fail "the container exited"
  [ -z "$(docker exec "${NAME}" cat /tmp/fake-ambari/api.log 2>/dev/null)" ] && ok "the API was not called" \
    || fail "unexpected API calls: $(docker exec "${NAME}" cat /tmp/fake-ambari/api.log 2>/dev/null)"
  [ "$(docker exec "${NAME}" cat /tmp/fake-ambari/password)" = changed-in-the-ui ] && ok "the password was not changed" \
    || fail "the password was changed"
  expect_absent "no development-only warning" "AMBARI_ADMIN_ALLOW_DEFAULT=true" "$(docker logs "${NAME}" 2>&1)"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> no administrator password, and a disabled admin account with the default one: the server starts, with a warning"
run_entrypoint -e AMBARI_ADMIN_PASSWORD= -e FAKE_ADMIN_ACTIVE=0
if wait_for_log "[entrypoint] WARNING: the admin account is disabled but still has Ambari's default password."; then
  expect_left_alone ""
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> no administrator password, and a database that cannot be read: the server is not started"
run_entrypoint -e AMBARI_ADMIN_PASSWORD= -e FAKE_PSQL_FAILS=1
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "says why" "[entrypoint] AMBARI_ADMIN_PASSWORD is not set, and the database could not be read to tell whether the admin" "${LOGS}"
  expect_absent "the server was not started" "[entrypoint] starting the server" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> \"admin\" is not accepted as the administrator password"
run_entrypoint -e AMBARI_ADMIN_PASSWORD=admin
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "says why" "[entrypoint] AMBARI_ADMIN_PASSWORD is Ambari's public default password: choose another one." "${LOGS}"
  expect_absent "the server was not started" "[entrypoint] starting the server" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> AMBARI_ADMIN_ALLOW_DEFAULT=true keeps admin/admin, and says so"
run_entrypoint -e AMBARI_ADMIN_PASSWORD= -e AMBARI_ADMIN_ALLOW_DEFAULT=true
if wait_for_log "[entrypoint] WARNING: AMBARI_ADMIN_ALLOW_DEFAULT=true and no other administrator password was given"; then
  for _ in $(seq 1 20); do readiness_probe http://127.0.0.1:8080/ && break; sleep 0.5; done
  readiness_probe http://127.0.0.1:8080/ && ok "ready" || fail "not ready"
  expect_absent "the API was not called" "PUT" "$(docker exec "${NAME}" cat /tmp/fake-ambari/api.log 2>/dev/null)"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

# Schemas of an Ambari line earlier and later than any image's, and the stand-in JVM that upgrades.
OLDER_SCHEMA=1.0.0
NEWER_SCHEMA=99.0.0
STAND_IN_JAVA=(-e "AMBARI_EXTRA_PROPERTIES=ambari.java.home=/stubs/jdk")

# What the stand-in database recorded of schema loads and upgrades, one per line.
schema_changes() { docker exec "${NAME}" cat /tmp/fake-ambari/schema.log 2>/dev/null; }

# The PostgreSQL schema ambari.properties says Ambari's tables are in.
recorded_pg_schema() {
  docker exec "${NAME}" sed -n 's/^server.jdbc.postgres.schema=//p' /etc/ambari-server/conf/ambari.properties | tail -n 1
}

# The image ships the PostgreSQL schema setup records -- one named after the database -- which is not
# where the stand-in database keeps its tables: so the upgrade checks below only pass when the
# entrypoint records the right one.
echo "==> the image does not already record where the stand-in database keeps its tables"
PACKAGED_PG_SCHEMA="$(docker run --rm --network=none --entrypoint sed "${IMAGE}" -n 's/^server.jdbc.postgres.schema=//p' \
  /etc/ambari-server/conf/ambari.properties | tail -n 1)"
[ "${PACKAGED_PG_SCHEMA}" != public ] && ok "the image records the PostgreSQL schema '${PACKAGED_PG_SCHEMA}', not public" \
  || fail "the image already records public as the PostgreSQL schema: the checks below prove nothing"

echo "==> a schema of the image's Ambari line: the server starts on it as it is"
run_entrypoint "${STAND_IN_JAVA[@]}"
if wait_for_log "[entrypoint] set the administrator password from AMBARI_ADMIN_PASSWORD"; then
  expect_contains "says so" "[entrypoint] the database schema (${DDL_VERSION}) is the one Ambari ${SERVER_VERSION} runs on" \
    "$(docker logs "${NAME}" 2>&1)"
  [ -z "$(schema_changes)" ] && ok "neither loaded nor upgraded" || fail "unexpected schema changes: $(schema_changes)"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> an empty database: the schema is loaded in one transaction, then the server starts on it"
run_entrypoint -e FAKE_SCHEMA_VERSION=none
if wait_for_log "[entrypoint] set the administrator password from AMBARI_ADMIN_PASSWORD"; then
  LOGS="$(docker logs "${NAME}" 2>&1)"
  expect_contains "says it loads it" "[entrypoint] the database holds no Ambari schema: loading it" "${LOGS}"
  expect_contains "and that it did" "[entrypoint] loaded the Ambari ${DDL_VERSION} schema" "${LOGS}"
  expect_contains "the server runs on it" "[entrypoint] the database schema (${DDL_VERSION}) is the one Ambari ${SERVER_VERSION} runs on" "${LOGS}"
  [ "$(schema_changes)" = load ] && ok "loaded once" || fail "unexpected schema changes: $(schema_changes)"

  echo "==> a restart finds the schema there and does not load it again"
  docker restart -t 30 "${NAME}" >/dev/null
  if wait_for_log "[entrypoint] the administrator password is not the default one; leaving it as it is"; then
    [ "$(schema_changes)" = load ] && ok "not loaded again" || fail "unexpected schema changes: $(schema_changes)"
    [ "$(docker logs "${NAME}" 2>&1 | grep -cF '[entrypoint] the database schema (')" = 2 ] \
      && ok "the restart found it" || fail "the restart did not say it found the schema"
  fi
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> a schema load that fails ends the container with psql's error, before the server starts"
run_entrypoint -e FAKE_SCHEMA_VERSION=none -e FAKE_SCHEMA_LOAD_FAILS=1
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "psql's error is in the output" "ERROR:  permission denied for schema public" "${LOGS}"
  expect_contains "says why" "[entrypoint] the Ambari schema could not be loaded, and nothing of it was kept: psql's error is above." "${LOGS}"
  expect_absent "the server was not started" "[entrypoint] starting the server" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> a schema of an earlier Ambari line ends the container, says what to do, and changes nothing"
run_entrypoint "${STAND_IN_JAVA[@]}" -e "FAKE_SCHEMA_VERSION=${OLDER_SCHEMA}"
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "names both versions" "[entrypoint] the database schema is Ambari ${OLDER_SCHEMA}, older than this server, Ambari ${SERVER_VERSION}, which" "${LOGS}"
  expect_contains "and the way forward" "image with AMBARI_SCHEMA_UPGRADE=true (in the ambari chart, schemaUpgrade.enabled=true)." "${LOGS}"
  expect_absent "the schema was not upgraded" "Upgrade successful" "${LOGS}"
  expect_absent "the server was not started" "[entrypoint] starting the server" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> AMBARI_SCHEMA_UPGRADE=true upgrades an earlier line's schema as the container's user, then starts"
run_entrypoint "${STAND_IN_JAVA[@]}" -e "FAKE_SCHEMA_VERSION=${OLDER_SCHEMA}" -e AMBARI_SCHEMA_UPGRADE=true
if wait_for_log "[entrypoint] set the administrator password from AMBARI_ADMIN_PASSWORD"; then
  LOGS="$(docker logs "${NAME}" 2>&1)"
  expect_contains "says it upgrades" "[entrypoint] upgrading the database schema from ${OLDER_SCHEMA} to ${SERVER_LINE} (AMBARI_SCHEMA_UPGRADE=true)" "${LOGS}"
  expect_contains "the PostgreSQL schema holding the tables is recorded before the upgrade" \
    "[entrypoint] Ambari's tables are in the PostgreSQL schema 'public': recorded it as server.jdbc.postgres.schema" \
    "$(sed -n '1,/\[entrypoint\] upgrading the database schema/p' <<<"${LOGS}")"
  [ "$(recorded_pg_schema)" = public ] && ok "ambari.properties names it" \
    || fail "ambari.properties names the PostgreSQL schema '$(recorded_pg_schema)'"
  # What the container printed from there to the end of the upgrade. (The server's log is followed
  # once the server has started, from its last lines, which may then include the upgrade's.)
  UPGRADE_LOGS="$(sed -n '/\[entrypoint\] upgrading the database schema/,/\[entrypoint\] upgraded the database schema/p' <<<"${LOGS}")"
  expect_contains "what the upgrade did is in the output" "SchemaUpgradeHelper:472 - Upgrading schema from source version = ${OLDER_SCHEMA}" "${UPGRADE_LOGS}"
  expect_contains "so are the warnings it logged" "DBAccessorImpl:1086 - Primary key doesn't exists for stand_in table, skipping" "${UPGRADE_LOGS}"
  expect_contains "down to its outcome" "SchemaUpgradeHelper:502 - Upgrade successful." "${UPGRADE_LOGS}"
  expect_absent "the rest of the server's log is not" "the server's own start-up" "${UPGRADE_LOGS}"
  expect_absent "nor the JVM's own messages" "SLF4J: Class path contains multiple SLF4J providers." "${LOGS}"
  expect_contains "which are in the upgrade's log file" "SLF4J: Class path contains multiple SLF4J providers." \
    "$(docker exec "${NAME}" cat /var/log/ambari-server/ambari-server-upgrade.log)"
  expect_contains "says it is done, and to turn the option off" "[entrypoint] upgraded the database schema to ${SERVER_LINE}. Turn AMBARI_SCHEMA_UPGRADE off again" "${LOGS}"
  [ "$(schema_changes)" = upgrade ] && ok "upgraded once" || fail "unexpected schema changes: $(schema_changes)"
  for _ in $(seq 1 20); do readiness_probe http://127.0.0.1:8080/ && break; sleep 0.5; done
  readiness_probe http://127.0.0.1:8080/ && ok "ready" || fail "not ready"

  echo "==> a restart does not upgrade again, and says AMBARI_SCHEMA_UPGRADE has nothing left to do"
  docker restart -t 30 "${NAME}" >/dev/null
  if wait_for_log "[entrypoint] AMBARI_SCHEMA_UPGRADE=true has nothing to do. Turn it off"; then
    [ "$(schema_changes)" = upgrade ] && ok "not upgraded again" || fail "unexpected schema changes: $(schema_changes)"
  fi
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> a schema upgrade that fails ends the container with the reason, before the server starts"
run_entrypoint "${STAND_IN_JAVA[@]}" -e "FAKE_SCHEMA_VERSION=${OLDER_SCHEMA}" -e AMBARI_SCHEMA_UPGRADE=true -e FAKE_UPGRADE_FAILS=1
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "the server's log says why" "SchemaUpgradeHelper:512 - Exception occurred during upgrade, failed" "${LOGS}"
  expect_contains "so does the upgrade's own output" 'Exception in thread "main" org.apache.ambari.server.AmbariException' "${LOGS}"
  expect_contains "and what to do" "[entrypoint] the schema upgrade failed; the server's log above says why." "${LOGS}"
  expect_absent "the server was not started" "[entrypoint] starting the server" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> a schema upgrade that failed runs again on the next start, and completes once the cause is gone"
# The pod's next container starts from the image again, as Kubernetes restarts it; only the database,
# here the stand-in's state, is kept.
mkdir "${WORK}/retry"
chmod a+rwX "${WORK}/retry"
run_entrypoint "${STAND_IN_JAVA[@]}" -v "${WORK}/retry:/tmp:Z" -e "FAKE_SCHEMA_VERSION=${OLDER_SCHEMA}" \
  -e AMBARI_SCHEMA_UPGRADE=true -e FAKE_UPGRADE_FAILS=once
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "the first start fails" || fail "exit code ${RC}, expected 1"
  docker rm -f "${NAME}" >/dev/null 2>&1
  run_entrypoint "${STAND_IN_JAVA[@]}" -v "${WORK}/retry:/tmp:Z" -e "FAKE_SCHEMA_VERSION=${OLDER_SCHEMA}" \
    -e AMBARI_SCHEMA_UPGRADE=true -e FAKE_UPGRADE_FAILS=once
  if wait_for_log "[entrypoint] set the administrator password from AMBARI_ADMIN_PASSWORD"; then
    expect_contains "the next one upgrades" "[entrypoint] upgraded the database schema to ${SERVER_LINE}." "$(docker logs "${NAME}" 2>&1)"
    [ "$(schema_changes)" = upgrade ] && ok "upgraded once" || fail "unexpected schema changes: $(schema_changes)"
  fi
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> tables in a PostgreSQL schema other than public: that is the one recorded, and upgraded"
# A database with a schema named after its user, which the user's search_path then finds first.
run_entrypoint "${STAND_IN_JAVA[@]}" -e FAKE_PG_SCHEMA=ambari_kdps -e "FAKE_SCHEMA_VERSION=${OLDER_SCHEMA}" \
  -e AMBARI_SCHEMA_UPGRADE=true
if wait_for_log "[entrypoint] set the administrator password from AMBARI_ADMIN_PASSWORD"; then
  expect_contains "says so" "[entrypoint] Ambari's tables are in the PostgreSQL schema 'ambari_kdps': recorded it as server.jdbc.postgres.schema" \
    "$(docker logs "${NAME}" 2>&1)"
  [ "$(recorded_pg_schema)" = ambari_kdps ] && ok "ambari.properties names it" \
    || fail "ambari.properties names the PostgreSQL schema '$(recorded_pg_schema)'"
  [ "$(schema_changes)" = upgrade ] && ok "upgraded once" || fail "unexpected schema changes: $(schema_changes)"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> a schema of a later Ambari line ends the container: a schema is never downgraded"
run_entrypoint "${STAND_IN_JAVA[@]}" -e "FAKE_SCHEMA_VERSION=${NEWER_SCHEMA}" -e AMBARI_SCHEMA_UPGRADE=true
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "says why" "[entrypoint] the database schema is Ambari ${NEWER_SCHEMA}, newer than this server, Ambari ${SERVER_VERSION}, and" "${LOGS}"
  expect_absent "nothing was run on it" "[entrypoint] upgrading the database schema" "${LOGS}"
  expect_absent "the server was not started" "[entrypoint] starting the server" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

echo "==> a schema version that cannot be read ends the container, before the server starts"
run_entrypoint -e FAKE_SCHEMA_READ_FAILS=1
if wait_for_exit; then
  [ "${RC}" = 1 ] && ok "exit code 1" || fail "exit code ${RC}, expected 1"
  expect_contains "psql's error is in the output" "password authentication failed" "${LOGS}"
  expect_contains "says why" "[entrypoint] could not read the Ambari schema version from the database; psql's error is above" "${LOGS}"
  expect_absent "the server was not started" "[entrypoint] starting the server" "${LOGS}"
fi
docker rm -f "${NAME}" >/dev/null 2>&1

if [ "${FAILED}" -ne 0 ]; then
  echo "image checks FAILED for ${IMAGE}"
  exit 1
fi
echo "image checks passed for ${IMAGE}"
