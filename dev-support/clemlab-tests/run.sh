#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_DIR="${ROOT_DIR}/dev-support/clemlab-tests"
BASE_COMPOSE="${COMPOSE_DIR}/docker-compose.yml"
RUNTIME_COMPOSE="${COMPOSE_DIR}/docker-compose.runtime.yml"
SMOKE_COMPOSE="${COMPOSE_DIR}/docker-compose.smoke.yml"
MAVEN_SETTINGS_TEMPLATE="${MAVEN_SETTINGS_TEMPLATE:-${COMPOSE_DIR}/maven-settings.xml}"
PROJECT_NAME="${PROJECT_NAME:-ambari-dev}"
INCLUDE_KEYCLOAK="${INCLUDE_KEYCLOAK:-1}"
INIT_AMBARI_DDL="${INIT_AMBARI_DDL:-1}"
AMBARI_DDL_FILE="${AMBARI_DDL_FILE:-${ROOT_DIR}/ambari-server/src/main/resources/Ambari-DDL-Postgres-CREATE.sql}"
AMBARI_DB_VERSION_SYNC="${AMBARI_DB_VERSION_SYNC:-1}"

PROJECT_VERSION="$(awk -F'[<>]' '/<version>/{print $3; exit}' "${ROOT_DIR}/pom.xml")"
if [ -z "${PROJECT_VERSION}" ]; then
  PROJECT_VERSION="2.8.2.0.0"
fi
AMBARI_SCHEMA_VERSION="${AMBARI_SCHEMA_VERSION:-${PROJECT_VERSION}}"

export AMBARI_SRC="${ROOT_DIR}"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

run_cmd() {
  echo "+ $*" >&2
  "$@"
}

detect_docker_platform() {
  case "$(uname -m)" in
    arm64|aarch64)
      echo "linux/arm64/v8"
      ;;
    x86_64|amd64)
      echo "linux/amd64"
      ;;
    *)
      echo "linux/amd64"
      ;;
  esac
}

install_maven_settings() {
  local cid="$1"
  local settings_src="$2"

  if [ ! -f "${settings_src}" ]; then
    fail "Maven settings template not found at ${settings_src}"
  fi

  echo "Configuring Maven settings.xml in container..."
  run_cmd docker cp "${settings_src}" "${cid}:/tmp/maven-settings.xml"
  run_cmd docker exec "${cid}" bash -lc 'set -euo pipefail; mkdir -p ~/.m2; cp /tmp/maven-settings.xml ~/.m2/settings.xml'
}

wait_for_postgres() {
  local cid="$1"
  echo "Waiting for PostgreSQL to be ready..."
  for _ in $(seq 1 60); do
    if docker exec "${cid}" bash -lc "pg_isready -h localhost -U postgres" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

init_ambari_db() {
  local cid="$1"
  echo "Initializing Ambari database (if missing)..."
  for _ in $(seq 1 10); do
    if docker exec "${cid}" bash -lc "psql -h localhost -v ON_ERROR_STOP=1 --username postgres --dbname postgres <<'EOSQL'
SELECT 'CREATE DATABASE ambari_db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='ambari_db')\\gexec
EOSQL"; then
      break
    fi
    echo "PostgreSQL not ready for init yet; retrying..."
    sleep 2
  done

  run_cmd docker exec "${cid}" bash -lc "psql -h localhost -v ON_ERROR_STOP=1 --username postgres --dbname ambari_db <<'EOSQL'
DO \$$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='ambari_user') THEN
    CREATE USER ambari_user WITH PASSWORD 'ambari123';
  END IF;
END \$$;
CREATE SCHEMA IF NOT EXISTS ambari_schema AUTHORIZATION ambari_user;
ALTER SCHEMA ambari_schema OWNER TO ambari_user;
ALTER ROLE ambari_user SET search_path to 'ambari_schema', 'public';
ALTER DATABASE ambari_db SET timezone TO 'UTC';
GRANT ALL PRIVILEGES ON DATABASE ambari_db TO ambari_user;
EOSQL"

  if [ "${INIT_AMBARI_DDL}" = "1" ] && [ -f "${AMBARI_DDL_FILE}" ]; then
    local ddl_to_apply="${AMBARI_DDL_FILE}"
    local rendered_ddl=""
    if grep -q '\${ambariSchemaVersion}' "${AMBARI_DDL_FILE}"; then
      rendered_ddl="$(mktemp /tmp/ambari-ddl.XXXXXX.sql)"
      sed "s/\${ambariSchemaVersion}/${AMBARI_SCHEMA_VERSION}/g" "${AMBARI_DDL_FILE}" > "${rendered_ddl}"
      ddl_to_apply="${rendered_ddl}"
    fi

    if docker exec "${cid}" bash -lc "psql -h localhost -tAc \"SELECT 1 FROM information_schema.tables WHERE table_schema='ambari_schema' AND table_name='stack'\" --username ambari_user --dbname ambari_db" | grep -q 1; then
      local current_schema_version=""
      current_schema_version="$(docker exec "${cid}" bash -lc "psql -h localhost -tAc \"SELECT metainfo_value FROM ambari_schema.metainfo WHERE metainfo_key='version'\" --username ambari_user --dbname ambari_db" | tr -d '[:space:]')"
      echo "Ambari DDL already applied (schema version: ${current_schema_version:-unknown}, target: ${AMBARI_SCHEMA_VERSION})."
      if [ "${AMBARI_DB_VERSION_SYNC}" = "1" ] && [ -n "${current_schema_version}" ] && [ "${current_schema_version}" != "${AMBARI_SCHEMA_VERSION}" ]; then
        echo "Synchronizing Ambari schema version metadata to ${AMBARI_SCHEMA_VERSION} for local compose runtime..."
        run_cmd docker exec "${cid}" bash -lc "psql -h localhost -v ON_ERROR_STOP=1 --username ambari_user --dbname ambari_db -c \"UPDATE ambari_schema.metainfo SET metainfo_value='${AMBARI_SCHEMA_VERSION}' WHERE metainfo_key='version';\""
      fi
    else
      echo "Applying Ambari DDL from ${AMBARI_DDL_FILE} (schema version ${AMBARI_SCHEMA_VERSION})..."
      run_cmd docker cp "${ddl_to_apply}" "${cid}:/tmp/Ambari-DDL-Postgres-CREATE.sql"
      run_cmd docker exec "${cid}" bash -lc "psql -h localhost -v ON_ERROR_STOP=1 --username ambari_user --dbname ambari_db -f /tmp/Ambari-DDL-Postgres-CREATE.sql"
    fi

    if [ -n "${rendered_ddl}" ] && [ -f "${rendered_ddl}" ]; then
      rm -f "${rendered_ddl}"
    fi
  fi
}

compose() {
  local cmd
  if docker compose version >/dev/null 2>&1; then
    cmd=(docker compose)
  else
    cmd=(docker-compose)
  fi
  echo "+ ${cmd[*]} $*" >&2
  "${cmd[@]}" "$@"
}

DOCKER_PLATFORM="${DOCKER_PLATFORM:-$(detect_docker_platform)}"
export DOCKER_PLATFORM

if ! command -v docker >/dev/null 2>&1; then
  fail "docker is required"
fi

echo "Starting docker compose (${PROJECT_NAME})..."
compose_files=("-f" "${BASE_COMPOSE}" "-f" "${RUNTIME_COMPOSE}")
if [ "${INCLUDE_KEYCLOAK}" = "1" ] && [ -f "${SMOKE_COMPOSE}" ]; then
  compose_files+=("-f" "${SMOKE_COMPOSE}")
fi
compose "${compose_files[@]}" -p "${PROJECT_NAME}" up -d --build

db_cid="$(compose "${compose_files[@]}" -p "${PROJECT_NAME}" ps -q db-ambari-test)"
[ -n "${db_cid}" ] || fail "db-ambari-test container not found"
wait_for_postgres "${db_cid}" || fail "PostgreSQL not ready"
init_ambari_db "${db_cid}"

server_cid="$(compose "${compose_files[@]}" -p "${PROJECT_NAME}" ps -q ambari-server)"
[ -n "${server_cid}" ] || fail "ambari-server container not found"

install_maven_settings "${server_cid}" "${MAVEN_SETTINGS_TEMPLATE}"

echo "Checking for built RPMs..."
if ! docker exec "${server_cid}" bash -lc "ls /tmp/ambari/ambari-server/target/rpm/ambari-server/RPMS/*/ambari-server-*.rpm >/dev/null 2>&1"; then
  fail "No ambari-server RPMs found. Run dev-support/clemlab-tests/build.sh first."
fi

echo "Ambari runtime containers are starting."
echo "Ambari UI: http://localhost:8080 (admin/admin)"
if [ "${INCLUDE_KEYCLOAK}" = "1" ]; then
  echo "Keycloak: http://localhost:18080"
fi
echo "Tip: follow logs with:"
echo "  docker compose -f ${BASE_COMPOSE} -f ${RUNTIME_COMPOSE} ${INCLUDE_KEYCLOAK:+-f ${SMOKE_COMPOSE}} -p ${PROJECT_NAME} logs -f ambari-server ambari-agent-1"
