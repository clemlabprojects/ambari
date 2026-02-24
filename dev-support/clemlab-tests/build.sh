#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_DIR="${ROOT_DIR}/dev-support/clemlab-tests"
BASE_COMPOSE="${COMPOSE_DIR}/docker-compose.yml"
MAVEN_SETTINGS_TEMPLATE="${MAVEN_SETTINGS_TEMPLATE:-${COMPOSE_DIR}/maven-settings.xml}"
PROJECT_NAME="${PROJECT_NAME:-ambari-dev}"

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
AMBARI_BASE_IMAGE="${AMBARI_BASE_IMAGE:-rockylinux:9}"
export AMBARI_BASE_IMAGE
DNF_UPDATE="${DNF_UPDATE:-0}"
export DNF_UPDATE

if ! command -v docker >/dev/null 2>&1; then
  fail "docker is required"
fi

if [ "${SKIP_BASE_BUILD:-}" != "1" ]; then
  echo "Building ambari/build:latest (BASE_IMAGE=${AMBARI_BASE_IMAGE}, PLATFORM=${DOCKER_PLATFORM})..."
  run_cmd docker build --platform "${DOCKER_PLATFORM}" --build-arg BASE_IMAGE="${AMBARI_BASE_IMAGE}" --build-arg DNF_UPDATE="${DNF_UPDATE}" -t ambari/build:latest "${ROOT_DIR}/dev-support/docker/docker"
fi

echo "Building ambari-arm/build..."
run_cmd docker build --platform "${DOCKER_PLATFORM}" -t ambari-arm/build "${COMPOSE_DIR}"

echo "Starting docker compose (${PROJECT_NAME})..."
compose -f "${BASE_COMPOSE}" -p "${PROJECT_NAME}" up -d --build

server_cid="$(compose -f "${BASE_COMPOSE}" -p "${PROJECT_NAME}" ps -q ambari-server)"
[ -n "${server_cid}" ] || fail "ambari-server container not found"

install_maven_settings "${server_cid}" "${MAVEN_SETTINGS_TEMPLATE}"

echo "Building Ambari RPMs inside container (tests skipped)..."
docker exec "${server_cid}" bash -lc '
  set -euo pipefail
  cd /tmp/ambari
  ARCH_OPTS=""
  if [ "$(uname -m)" = "aarch64" ]; then
    case "${MAVEN_OPTS:-}" in
      *-Dos.arch=*) ARCH_OPTS="";;
      *) ARCH_OPTS="-Dos.arch=aarch64";;
    esac
  fi
  export MAVEN_OPTS="${MAVEN_OPTS:--Xmx3g -XX:MaxMetaspaceSize=512m} ${ARCH_OPTS}"
  EXTRA_ARGS="${AMBARI_MVN_EXTRA_ARGS:-}"
  if [ "$(uname -m)" = "aarch64" ] && [[ "${EXTRA_ARGS}" != *ambari.grafana.arch* ]]; then
    EXTRA_ARGS="${EXTRA_ARGS} -Dambari.grafana.arch=arm64"
  fi
  if [ "$(uname -m)" = "aarch64" ] && [[ "${EXTRA_ARGS}" != *ambari.arch* ]]; then
    EXTRA_ARGS="${EXTRA_ARGS} -Dambari.arch=aarch64"
  fi
  if [ -f /etc/os-release ]; then
    . /etc/os-release
    RHEL_MAJOR="${VERSION_ID%%.*}"
  else
    RHEL_MAJOR="0"
  fi
  if [[ "${EXTRA_ARGS}" != *build-rpm-rhel* ]]; then
    case "${RHEL_MAJOR}" in
      9) EXTRA_ARGS="${EXTRA_ARGS} -Dbuild-rpm-rhel9";;
      8) EXTRA_ARGS="${EXTRA_ARGS} -Dbuild-rpm-rhel8";;
      7) EXTRA_ARGS="${EXTRA_ARGS} -Dbuild-rpm-rhel7";;
    esac
  fi
  mvn -B clean install package rpm:rpm \
    -Dmaven.clover.skip=true \
    -Dfindbugs.skip=true \
    -DskipTests \
    -Drat.skip=true \
    -Dpython.ver="python >= 2.6" \
    ${EXTRA_ARGS}
'

echo "Build complete. Next: run dev-support/clemlab-tests/run.sh"
