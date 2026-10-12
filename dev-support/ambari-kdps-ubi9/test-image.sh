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
#   * the container exits non-zero when the server dies on its own.
#
# The entrypoint runs as PID 1 under the arbitrary UID OpenShift would use, with the image's own
# bash, tail and sed (tail behind a wrapper that can make it late). Only the server is a stand-in --
# an "ambari-server" stub on PATH starts it -- so the whole run takes under a minute and needs
# neither a database nor a network.
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
# The schema is there.
echo 1
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
on_term() { echo "fake server: shutdown hooks ran" >> "${log}"; exit 0; }
if [ "${FAKE_SERVER_IGNORES_TERM:-}" = 1 ]; then trap '' TERM; else trap on_term TERM; fi
echo "fake server: started" >> "${log}"
echo "audit: user login" >> /var/log/ambari-server/ambari-audit.log
for _ in $(seq 1 "${FAKE_SERVER_LIFETIME:-600}"); do sleep 1; done
echo "fake server: exiting on its own" >> "${log}"
EOF
chmod 0755 "${WORK}/stubs/"*

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

run_entrypoint() {
  docker run -d --name "${NAME}" --network "container:${NAME}-db" --user 1001:0 \
    -v "${WORK}/passwd:/etc/passwd:ro,Z" -v "${WORK}/stubs:/stubs:ro,Z" \
    -e PATH=/stubs:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin \
    -e AMBARI_DB_HOST=127.0.0.1 -e AMBARI_DB_PASSWORD=unused -e AMBARI_SECURITY_MASTER_KEY=unused \
    "$@" "${IMAGE}" >/dev/null
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
run_entrypoint -e FAKE_SERVER_LIFETIME=2
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

if [ "${FAILED}" -ne 0 ]; then
  echo "image checks FAILED for ${IMAGE}"
  exit 1
fi
echo "image checks passed for ${IMAGE}"
