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
# Checks what the image does with the database schema against a real PostgreSQL, before the image is
# pushed. test-image.sh checks the entrypoint's decisions against a stand-in database, which cannot
# tell whether the image's DDL loads in one transaction, nor whether the real schema upgrade starts
# from the image's classpath, finds Ambari's tables and changes them. This script does:
#   * an empty database gets the whole schema, with the version the image's DDL records, and
#     ambari.properties names "public", the PostgreSQL schema the tables went to; a second start finds
#     it there and does not load it again;
#   * a database user that cannot create tables ends the container with psql's own error, and the
#     database holds no table afterwards;
#   * a load that fails at the DDL's last table, after the DDL has committed its seed data, keeps
#     nothing of it either: the DDL's own COMMIT does not end the transaction part-way;
#   * a schema of the line before the image's ends the container, and the database is left exactly as
#     it was;
#   * AMBARI_SCHEMA_UPGRADE=true upgrades that schema to the image's line, with the same tables,
#     columns, keys and indexes as a fresh load; so does an upgrade that stopped after creating its
#     table, run again;
#   * the server starts on the upgraded schema, and the administrator password is set through its API.
#
# Nothing in the image is replaced: ambari-server setup, psql, the schema upgrade and the server are
# its own. Until the last check, "ambari-server start" is held back by a wrapper on PATH, so that the
# schema is compared before the server adds tables of its own (those its views keep their data in).
# PostgreSQL runs in a container with no network, whose loopback the image's container shares.
#
# Usage: test-image-postgres.sh <image>
# POSTGRES_IMAGE names the PostgreSQL image to run (default postgres:16-alpine).

set -uo pipefail

IMAGE="${1:?usage: $0 <image>}"
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:16-alpine}"
NAME="ambari-kdps-pgtest-$$"
DB_CONTAINER="${NAME}-db"
DB_PASSWORD="a-test-database-password"
WORK="$(mktemp -d)"
FAILED=0

cleanup() {
  docker rm -f "${NAME}" "${DB_CONTAINER}" >/dev/null 2>&1 || true
  rm -rf "${WORK}"
}
trap cleanup EXIT

ok()   { echo "ok   - $*"; }
fail() { echo "FAIL - $*"; FAILED=1; return 1; }

expect_contains() {
  if grep -qF -- "$2" <<<"$3"; then ok "$1"; else fail "$1: expected '$2'"; fi
}

expect_absent() {
  if grep -qF -- "$2" <<<"$3"; then fail "$1: did not expect '$2'"; else ok "$1"; fi
}

expect_equal() {
  if [ "$2" = "$3" ]; then ok "$1"; else fail "$1: expected '$2', got '$3'"; fi
}

# The pod's passwd entry, as the chart's passwd-shim init container writes it.
docker run --rm --network=none --entrypoint cat "${IMAGE}" /etc/passwd > "${WORK}/passwd"
echo "ambari:x:1001:0:Ambari:/var/lib/ambari-server:/sbin/nologin" >> "${WORK}/passwd"

# The image's DDL, its Ambari line (the first three numbers of its version) and the schema version
# its DDL records.
docker run --rm --network=none --entrypoint cat "${IMAGE}" \
  /var/lib/ambari-server/resources/Ambari-DDL-Postgres-CREATE.sql > "${WORK}/ddl.sql"
SERVER_VERSION="$(docker run --rm --network=none --entrypoint cat "${IMAGE}" /var/lib/ambari-server/resources/version | tr -d '[:space:]')"
SERVER_LINE="$(sed -E 's/^([0-9]+\.[0-9]+\.[0-9]+).*/\1/' <<<"${SERVER_VERSION}")"
DDL_VERSION="$(sed -n "s/^('version','\([^']*\)');.*/\1/p" "${WORK}/ddl.sql" | head -n 1)"
DDL_TABLES="$(grep -ciE '^[[:space:]]*create table' "${WORK}/ddl.sql")"
LAST_TABLE="$(sed -nE 's/^[[:space:]]*create table[[:space:]]+([[:alnum:]_]+).*/\1/Ip' "${WORK}/ddl.sql" | tail -n 1 \
  | tr '[:upper:]' '[:lower:]')"

# The image's own launcher, except that "start" is held back: the entrypoint then ends there, having
# done everything to the database it does before the server runs.
mkdir "${WORK}/stubs"
cat > "${WORK}/stubs/ambari-server" <<'EOF'
#!/bin/bash
if [ "${1:-}" = start ]; then
  echo "test: the server is not started"
  exit 1
fi
exec /usr/sbin/ambari-server "$@"
EOF
chmod 0755 "${WORK}/stubs/ambari-server"
HOLD_START=(-v "${WORK}/stubs:/stubs:ro,Z" -e PATH=/stubs:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin)

echo "==> starting ${POSTGRES_IMAGE}"
docker run -d --name "${DB_CONTAINER}" --network=none -e POSTGRES_PASSWORD="${DB_PASSWORD}" "${POSTGRES_IMAGE}" >/dev/null \
  || { echo "could not start ${POSTGRES_IMAGE}"; exit 1; }
# The image's start-up script runs a first server on the socket only, to initialise the database, then
# the real one: only that one listens on TCP.
for _ in $(seq 1 60); do
  docker exec "${DB_CONTAINER}" psql -h 127.0.0.1 -U postgres -c 'select 1' >/dev/null 2>&1 && break
  sleep 1
done

# psql in the PostgreSQL container, as $1 on database $2, stopping at the first error.
sql_as() {
  local user=$1 db=$2; shift 2
  docker exec "${DB_CONTAINER}" psql -U "${user}" -d "${db}" -v ON_ERROR_STOP=1 -qtA "$@"
}
sql() { sql_as postgres "$@"; }

# A dump of database $1 (with pg_dump's options that follow), without the random key recent pg_dump
# releases write in its \restrict and \unrestrict lines.
dump() {
  local db=$1; shift
  docker exec "${DB_CONTAINER}" pg_dump -U postgres -d "${db}" "$@" | grep -vE '^\\(un)?restrict '
}

# The tables of database $1 outside PostgreSQL's own schemas, as schema.table, one per line.
tables() {
  sql "$1" -c "select schemaname || '.' || tablename from pg_tables
               where schemaname not in ('pg_catalog', 'information_schema') order by 1"
}

# The schema version database $1 records, empty when it has no metainfo table.
schema_version() {
  sql "$1" -c "select metainfo_value from metainfo where metainfo_key = 'version'" 2>/dev/null
}

if ! docker exec "${DB_CONTAINER}" psql -h 127.0.0.1 -U postgres -c 'select 1' >/dev/null; then
  echo "${POSTGRES_IMAGE} did not start:"
  docker logs "${DB_CONTAINER}" 2>&1 | tail -n 20
  exit 1
fi

# The chart's database user, which is not a superuser: a superuser passes every permission check.
# "ambari" is the database it owns, as the chart expects; "ambari_partial" too, for a load that fails
# part-way; "ambari_nocreate" belongs to another role and does not let it create tables, as a
# PostgreSQL 15 or later database does not unless it is granted.
if ! sql postgres -c "create role ambari login password '${DB_PASSWORD}'" \
       -c "create database ambari owner ambari" \
       -c "create database ambari_partial owner ambari" \
       -c "create database ambari_nocreate" \
  || ! sql ambari_nocreate -c "revoke create on schema public from public"; then
  echo "could not prepare the databases in ${POSTGRES_IMAGE}"
  exit 1
fi

# Starts the entrypoint on database $1, with the options that follow.
run_entrypoint() {
  local db=$1; shift
  docker run -d --name "${NAME}" --network "container:${DB_CONTAINER}" --user 1001:0 \
    -v "${WORK}/passwd:/etc/passwd:ro,Z" \
    -e AMBARI_DB_HOST=127.0.0.1 -e AMBARI_DB_NAME="${db}" -e AMBARI_DB_USER=ambari \
    -e AMBARI_DB_PASSWORD="${DB_PASSWORD}" -e AMBARI_SECURITY_MASTER_KEY=a-test-master-key \
    -e AMBARI_ADMIN_PASSWORD=A-test-password-1 \
    "$@" "${IMAGE}" >/dev/null
}

# Waits for the container to exit, then sets RC, LOGS and PROPERTIES (its ambari.properties) and
# removes it.
wait_for_exit() {
  local running=true
  for _ in $(seq 1 300); do
    running="$(docker inspect -f '{{.State.Running}}' "${NAME}")"
    [ "${running}" = false ] && break
    sleep 1
  done
  RC=$(docker inspect -f '{{.State.ExitCode}}' "${NAME}")
  LOGS="$(docker logs "${NAME}" 2>&1)"
  PROPERTIES="$(docker cp "${NAME}:/etc/ambari-server/conf/ambari.properties" - 2>/dev/null | tar -xO 2>/dev/null)"
  docker rm -f "${NAME}" >/dev/null 2>&1
  [ "${running}" = false ] || { fail "the container is still running"; return 1; }
}

# The value $1 has in the ambari.properties wait_for_exit kept.
property() { sed -n "s|^$1=||p" <<<"${PROPERTIES}" | tail -n 1; }

# The entrypoint's own lines and the schema upgrade's, for a check that fails.
show_logs() { grep -E '^\[entrypoint\]|SchemaUpgradeHelper|UpgradeCatalog|ERROR|^psql' <<<"${LOGS}" | tail -n 40; }

echo "==> an empty database: the whole schema is loaded, and ambari.properties names where it went"
run_entrypoint ambari "${HOLD_START[@]}"
if wait_for_exit; then
  expect_contains "says it loaded it" "[entrypoint] loaded the Ambari ${DDL_VERSION} schema" "${LOGS}"
  expect_contains "and went on to start the server" "[entrypoint] starting the server" "${LOGS}" || show_logs
  expect_equal "the database records the DDL's schema version" "${DDL_VERSION}" "$(schema_version ambari)"
  expect_equal "every table of the DDL is there, in public" "${DDL_TABLES}" "$(tables ambari | grep -c '^public\.')"
  expect_equal "and nowhere else" "${DDL_TABLES}" "$(tables ambari | wc -l | tr -d ' ')"
  expect_equal "ambari.properties names public as the PostgreSQL schema" public "$(property server.jdbc.postgres.schema)"
fi
dump ambari --schema-only > "${WORK}/fresh.sql"

echo "==> a second start finds the schema and does not load it again"
run_entrypoint ambari "${HOLD_START[@]}"
if wait_for_exit; then
  expect_contains "finds it" "[entrypoint] the database schema (${DDL_VERSION}) is the one Ambari ${SERVER_VERSION} runs on" "${LOGS}"
  expect_absent "does not load it" "[entrypoint] the database holds no Ambari schema" "${LOGS}"
  dump ambari --schema-only > "${WORK}/restarted.sql"
  cmp -s "${WORK}/fresh.sql" "${WORK}/restarted.sql" && ok "the schema is unchanged" \
    || fail "the schema changed: $(diff "${WORK}/fresh.sql" "${WORK}/restarted.sql" | head -n 20)"
fi

echo "==> a user that cannot create tables: psql's error ends the container, and no table is left"
run_entrypoint ambari_nocreate "${HOLD_START[@]}"
if wait_for_exit; then
  expect_equal "exit code" 1 "${RC}"
  expect_contains "psql's error is in the output" "permission denied for schema public" "${LOGS}"
  expect_contains "says why" "[entrypoint] the Ambari schema could not be loaded, and nothing of it was kept" "${LOGS}"
  expect_absent "the server was not started" "[entrypoint] starting the server" "${LOGS}"
  expect_equal "the database holds no table" "" "$(tables ambari_nocreate)"
fi

echo "==> a load that fails at the DDL's last table (${LAST_TABLE}) keeps nothing of the schema"
# The table is there already, as an earlier load that was cut short could have left it. The DDL
# creates its last table after it commits its seed data, the version row included.
sql_as ambari ambari_partial -c "create table ${LAST_TABLE} (left_over integer)"
run_entrypoint ambari_partial "${HOLD_START[@]}"
if wait_for_exit; then
  expect_equal "exit code" 1 "${RC}"
  expect_contains "psql's error is in the output" "relation \"${LAST_TABLE}\" already exists" "${LOGS}"
  expect_equal "the table that was there is the only one left" "public.${LAST_TABLE}" "$(tables ambari_partial)"
  expect_equal "the database records no schema version" "" "$(schema_version ambari_partial)"
fi

# How to turn a schema of the image's line into one of the line before it: by undoing what the schema
# upgrade to the image's line adds, which the checks below then upgrade back. Each new Ambari line
# needs its own here.
EARLIER_LINE=""
case "${SERVER_LINE}" in
  2.8.2)
    EARLIER_LINE=2.8.1
    # UpgradeCatalog282 creates the oidc_client table and gives it a row in ambari_sequences.
    TABLE_ADDED=oidc_client
    undo_upgrade() {
      sql ambari -c "drop table oidc_client" -c "delete from ambari_sequences where sequence_name = 'oidc_client_id_seq'"
    } ;;
  *)
    fail "this script knows how to make a schema of the line before 2.8.2, but the image is Ambari ${SERVER_VERSION}: add the line before ${SERVER_LINE}" ;;
esac

if [ -n "${EARLIER_LINE}" ]; then
  set_version() { sql ambari -c "update metainfo set metainfo_value = '$1' where metainfo_key = 'version'"; }

  echo "==> a schema of the ${EARLIER_LINE} line ends the container and is left exactly as it was"
  undo_upgrade
  set_version "${EARLIER_LINE}"
  dump ambari > "${WORK}/before.sql"
  run_entrypoint ambari "${HOLD_START[@]}"
  if wait_for_exit; then
    expect_equal "exit code" 1 "${RC}"
    expect_contains "names both versions" "[entrypoint] the database schema is Ambari ${EARLIER_LINE}, older than this server, Ambari ${SERVER_VERSION}, which" "${LOGS}"
    expect_absent "nothing was run on it" "[entrypoint] upgrading the database schema" "${LOGS}"
    dump ambari > "${WORK}/after.sql"
    cmp -s "${WORK}/before.sql" "${WORK}/after.sql" && ok "the database, its data included, is unchanged" \
      || fail "the database changed: $(diff "${WORK}/before.sql" "${WORK}/after.sql" | head -n 20)"
  fi

  echo "==> AMBARI_SCHEMA_UPGRADE=true upgrades it to the ${SERVER_LINE} line, the same schema as a fresh load"
  run_entrypoint ambari "${HOLD_START[@]}" -e AMBARI_SCHEMA_UPGRADE=true
  if wait_for_exit; then
    expect_contains "says it upgraded it" "[entrypoint] upgraded the database schema to ${SERVER_LINE}." "${LOGS}" || show_logs
    expect_contains "what the upgrade did is in the output" "Creating table ${TABLE_ADDED}" "${LOGS}"
    tables ambari | grep -qx "public.${TABLE_ADDED}" && ok "the upgrade created ${TABLE_ADDED}" \
      || fail "the upgrade did not create ${TABLE_ADDED}"
    expect_contains "and went on to start the server" "[entrypoint] starting the server" "${LOGS}"
    expect_equal "ambari.properties names public as the PostgreSQL schema" public "$(property server.jdbc.postgres.schema)"
    expect_equal "the database records the ${SERVER_LINE} line" "${SERVER_LINE}" \
      "$(sed -E 's/^([0-9]+\.[0-9]+\.[0-9]+).*/\1/' <<<"$(schema_version ambari)")"
    dump ambari --schema-only > "${WORK}/upgraded.sql"
    cmp -s "${WORK}/fresh.sql" "${WORK}/upgraded.sql" && ok "tables, columns, keys and indexes are those of a fresh load" \
      || fail "the upgraded schema differs from a fresh load: $(diff "${WORK}/fresh.sql" "${WORK}/upgraded.sql" | head -n 20)"
  fi

  echo "==> an upgrade that stopped after creating its table completes when it runs again"
  # The table is there, but the version is still the earlier line's.
  set_version "${EARLIER_LINE}"
  run_entrypoint ambari "${HOLD_START[@]}" -e AMBARI_SCHEMA_UPGRADE=true
  if wait_for_exit; then
    expect_contains "says it upgraded it" "[entrypoint] upgraded the database schema to ${SERVER_LINE}." "${LOGS}" || show_logs
    expect_contains "the upgrade found the table" "Table ${TABLE_ADDED} already exists, skipping creation" "${LOGS}"
    expect_equal "the database records the ${SERVER_LINE} line" "${SERVER_LINE}" \
      "$(sed -E 's/^([0-9]+\.[0-9]+\.[0-9]+).*/\1/' <<<"$(schema_version ambari)")"
    dump ambari --schema-only > "${WORK}/retried.sql"
    cmp -s "${WORK}/fresh.sql" "${WORK}/retried.sql" && ok "tables, columns, keys and indexes are those of a fresh load" \
      || fail "the schema differs from a fresh load: $(diff "${WORK}/fresh.sql" "${WORK}/retried.sql" | head -n 20)"
  fi
fi

echo "==> the server starts on the schema, and the administrator password is set"
run_entrypoint ambari
started=""
for _ in $(seq 1 300); do
  LOGS="$(docker logs "${NAME}" 2>&1)"
  grep -qF "[entrypoint] set the administrator password from AMBARI_ADMIN_PASSWORD" <<<"${LOGS}" && { started=1; break; }
  [ "$(docker inspect -f '{{.State.Running}}' "${NAME}")" = false ] && break
  sleep 1
done
if [ -n "${started}" ]; then
  ok "the server came up and its API set the password"
  docker stop -t 60 "${NAME}" >/dev/null
  expect_equal "it stops cleanly" 0 "$(docker inspect -f '{{.State.ExitCode}}' "${NAME}")"
else
  fail "the server did not come up"
  grep -E '^\[entrypoint\]|ERROR|Exception' <<<"${LOGS}" | tail -n 40
fi
docker rm -f "${NAME}" >/dev/null 2>&1

if [ "${FAILED}" -ne 0 ]; then
  echo "PostgreSQL checks FAILED for ${IMAGE}"
  exit 1
fi
echo "PostgreSQL checks passed for ${IMAGE}"
