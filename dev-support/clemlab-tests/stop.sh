#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_DIR="${ROOT_DIR}/dev-support/clemlab-tests"
BASE_COMPOSE="${COMPOSE_DIR}/docker-compose.yml"
SMOKE_COMPOSE="${COMPOSE_DIR}/docker-compose.smoke.yml"
RUNTIME_COMPOSE="${COMPOSE_DIR}/docker-compose.runtime.yml"

DEV_PROJECT_NAME="${PROJECT_NAME:-ambari-dev}"
SMOKE_PROJECT_NAME="${SMOKE_PROJECT_NAME:-ambari-smoke}"

STOP_DEV="${STOP_DEV:-1}"
STOP_SMOKE="${STOP_SMOKE:-1}"
CLEAN_VOLUMES="${CLEAN_VOLUMES:-0}"

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

stop_stack() {
  local name="$1"
  shift
  local containers
  local down_args=(down --remove-orphans)
  if [ "${CLEAN_VOLUMES}" = "1" ]; then
    down_args+=( -v )
  fi
  containers="$(compose "$@" -p "${name}" ps -q 2>/dev/null || true)"
  if [ -n "${containers}" ]; then
    echo "Stopping compose project: ${name}"
    compose "$@" -p "${name}" "${down_args[@]}" || true
  else
    echo "No running containers for compose project: ${name}"
  fi
}

if [ "${STOP_SMOKE}" = "1" ]; then
  stop_stack "${SMOKE_PROJECT_NAME}" -f "${BASE_COMPOSE}" -f "${SMOKE_COMPOSE}"
fi

if [ "${STOP_DEV}" = "1" ]; then
  if [ -f "${RUNTIME_COMPOSE}" ]; then
    if [ -f "${SMOKE_COMPOSE}" ]; then
      stop_stack "${DEV_PROJECT_NAME}" -f "${BASE_COMPOSE}" -f "${RUNTIME_COMPOSE}" -f "${SMOKE_COMPOSE}"
    else
      stop_stack "${DEV_PROJECT_NAME}" -f "${BASE_COMPOSE}" -f "${RUNTIME_COMPOSE}"
    fi
  else
    stop_stack "${DEV_PROJECT_NAME}" -f "${BASE_COMPOSE}"
  fi
fi

echo "Done."
