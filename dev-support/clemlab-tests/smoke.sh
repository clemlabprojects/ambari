#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_DIR="${ROOT_DIR}/dev-support/clemlab-tests"
BASE_COMPOSE="${COMPOSE_DIR}/docker-compose.yml"
SMOKE_COMPOSE="${COMPOSE_DIR}/docker-compose.smoke.yml"
MAVEN_SETTINGS_TEMPLATE="${MAVEN_SETTINGS_TEMPLATE:-${COMPOSE_DIR}/maven-settings.xml}"
PROJECT_NAME="ambari-smoke"
CLUSTER_NAME="odp"
ODP_REPO_VERSION="${ODP_REPO_VERSION:-1.3.1.0-242}"
ODP_REPO_ID="${ODP_REPO_ID:-ODP-${ODP_REPO_VERSION}}"
ODP_REPO_NAME="${ODP_REPO_NAME:-ODP}"
SKIP_COMPOSE_DOWN="${SKIP_COMPOSE_DOWN:-}"
SKIP_COMPOSE_UP="${SKIP_COMPOSE_UP:-}"
SKIP_AMBARI_BUILD="${SKIP_AMBARI_BUILD:-}"

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

wait_for_url() {
  local url="$1"
  local max_tries="${2:-120}"
  local delay="${3:-5}"
  local i
  for i in $(seq 1 "${max_tries}"); do
    if curl -fsS --retry 3 --retry-all-errors --retry-delay 1 \
      --connect-timeout 3 --max-time 10 "${url}" >/dev/null; then
      return 0
    fi
    echo "Waiting for ${url} (${i}/${max_tries})..." >&2
    sleep "${delay}"
  done
  fail "Timed out waiting for ${url}"
}

configure_keycloak_ssl() {
  local cid="$1"

  echo "Configuring Keycloak realms to allow HTTP (sslRequired=NONE)..."
  run_cmd docker exec "${cid}" bash -lc \
    '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin --password admin'
  run_cmd docker exec "${cid}" bash -lc \
    '/opt/keycloak/bin/kcadm.sh update realms/master -s sslRequired=NONE'
  if docker exec "${cid}" bash -lc '/opt/keycloak/bin/kcadm.sh get realms/ambari' >/dev/null 2>&1; then
    run_cmd docker exec "${cid}" bash -lc \
      '/opt/keycloak/bin/kcadm.sh update realms/ambari -s sslRequired=NONE'
  else
    echo "Keycloak realm 'ambari' not found yet; skipping sslRequired update." >&2
  fi
}

wait_for_ambari() {
  local url="$1"
  local max_tries="${2:-120}"
  local delay="${3:-5}"
  local i
  for i in $(seq 1 "${max_tries}"); do
    if curl -fsS --retry 3 --retry-all-errors --retry-delay 1 \
      --connect-timeout 3 --max-time 10 \
      -u admin:admin "${url}" >/dev/null; then
      return 0
    fi
    sleep "${delay}"
  done
  fail "Timed out waiting for Ambari at ${url}"
}

fetch_hosts_json() {
  local cid
  cid="${server_cid:-}"
  if [ -z "${cid}" ]; then
    cid="$(compose -f "${BASE_COMPOSE}" -f "${SMOKE_COMPOSE}" -p "${PROJECT_NAME}" ps -q ambari-server)"
    server_cid="${cid}"
  fi
  if [ -n "${cid}" ]; then
    if docker exec "${cid}" bash -lc \
      'curl -fsS -u admin:admin http://localhost:8080/api/v1/hosts' 2>/dev/null; then
      return 0
    fi
    cid="$(compose -f "${BASE_COMPOSE}" -f "${SMOKE_COMPOSE}" -p "${PROJECT_NAME}" ps -q ambari-server)"
    server_cid="${cid}"
    if [ -n "${cid}" ]; then
      docker exec "${cid}" bash -lc \
        'curl -fsS -u admin:admin http://localhost:8080/api/v1/hosts' || true
      return 0
    fi
  fi
  curl -fsS -u admin:admin http://localhost:8080/api/v1/hosts || true
}

wait_for_host_name() {
  local max_tries="${1:-180}"
  local delay="${2:-5}"
  local i hosts_json host
  for i in $(seq 1 "${max_tries}"); do
    hosts_json="$(fetch_hosts_json)"
    if [ -n "${hosts_json}" ]; then
      host="$(printf '%s' "${hosts_json}" | python3 -c 'import json,sys; data=json.load(sys.stdin); items=data.get("items", []); print(items[0]["Hosts"]["host_name"]) if items else sys.exit(1)' || true)"
      if [ -n "${host}" ]; then
        echo "${host}"
        return 0
      fi
    fi
    echo "Waiting for Ambari host registration (${i}/${max_tries})..." >&2
    sleep "${delay}"
  done
  return 1
}

detect_server_os_family() {
  local cid="$1"
  local id version major
  read -r id version <<<"$(docker exec "${cid}" bash -lc '. /etc/os-release; echo "${ID} ${VERSION_ID}"')"
  major="${version%%.*}"
  case "${id}" in
    ubuntu)
      echo "ubuntu${major}"
      ;;
    rhel|rocky|centos|almalinux|ol|fedora)
      echo "redhat${major}"
      ;;
    *)
      echo "${id}${major}"
      ;;
  esac
}

default_repo_base_url() {
  local os_family="$1"
  case "${os_family}" in
    redhat9)
      echo "https://clemlabs.s3.eu-west-3.amazonaws.com/centos9/odp-release/${ODP_REPO_VERSION}/rpms/"
      ;;
    redhat8)
      echo "https://clemlabs.s3.eu-west-3.amazonaws.com/centos8/odp-release/${ODP_REPO_VERSION}/rpms/"
      ;;
    ubuntu22)
      echo "https://clemlabs.s3.eu-west-3.amazonaws.com/ubuntu22/odp-release/${ODP_REPO_VERSION}/dist/ODP"
      ;;
    ubuntu24)
      echo "https://clemlabs.s3.eu-west-3.amazonaws.com/ubuntu24/odp-release/${ODP_REPO_VERSION}/dist/ODP"
      ;;
    *)
      return 1
      ;;
  esac
}

if ! command -v docker >/dev/null; then
  fail "docker is required"
fi

existing_containers="$(compose -f "${BASE_COMPOSE}" -f "${SMOKE_COMPOSE}" -p "${PROJECT_NAME}" ps -q 2>/dev/null || true)"
if [ -n "${existing_containers}" ] && [ -z "${SKIP_COMPOSE_DOWN}" ]; then
  echo "Stopping existing smoke containers..."
  compose -f "${BASE_COMPOSE}" -f "${SMOKE_COMPOSE}" -p "${PROJECT_NAME}" down -v --remove-orphans || true
fi

if [ "${SKIP_BASE_BUILD:-}" != "1" ]; then
  echo "Building ambari/build:latest (BASE_IMAGE=${AMBARI_BASE_IMAGE}, PLATFORM=${DOCKER_PLATFORM})..."
  run_cmd docker build --platform "${DOCKER_PLATFORM}" --build-arg BASE_IMAGE="${AMBARI_BASE_IMAGE}" --build-arg DNF_UPDATE="${DNF_UPDATE}" -t ambari/build:latest "${ROOT_DIR}/dev-support/docker/docker"
fi

echo "Building ambari-arm/build..."
run_cmd docker build --platform "${DOCKER_PLATFORM}" -t ambari-arm/build "${COMPOSE_DIR}"

if [ -z "${SKIP_COMPOSE_UP}" ]; then
  echo "Starting docker compose..."
  compose -f "${BASE_COMPOSE}" -f "${SMOKE_COMPOSE}" -p "${PROJECT_NAME}" up -d --build
else
  echo "Skipping docker compose up (SKIP_COMPOSE_UP set)."
fi

server_cid="$(compose -f "${BASE_COMPOSE}" -f "${SMOKE_COMPOSE}" -p "${PROJECT_NAME}" ps -q ambari-server)"
keycloak_cid="$(compose -f "${BASE_COMPOSE}" -f "${SMOKE_COMPOSE}" -p "${PROJECT_NAME}" ps -q keycloak)"

[ -n "${server_cid}" ] || fail "ambari-server container not found"
[ -n "${keycloak_cid}" ] || fail "keycloak container not found"

install_maven_settings "${server_cid}" "${MAVEN_SETTINGS_TEMPLATE}"

echo "Waiting for Keycloak..."
wait_for_url "http://localhost:18080/realms/master"
configure_keycloak_ssl "${keycloak_cid}"
wait_for_url "http://localhost:18080/realms/master/.well-known/openid-configuration"

if [ -z "${SKIP_AMBARI_BUILD}" ]; then
  echo "Starting Ambari build/setup inside container..."
  run_cmd docker exec "${server_cid}" bash -lc \
    'set -o pipefail; cd /tmp/ambari; ARCH_OPTS=""; EXTRA_ARGS="-Drat.skip=true"; if [ "$(uname -m)" = "aarch64" ]; then ARCH_OPTS="-Dos.arch=aarch64"; EXTRA_ARGS="${EXTRA_ARGS} -Dambari.grafana.arch=arm64 -Dambari.arch=aarch64"; fi; if [ -f /etc/os-release ]; then . /etc/os-release; RHEL_MAJOR="${VERSION_ID%%.*}"; else RHEL_MAJOR="0"; fi; case "${RHEL_MAJOR}" in 9) EXTRA_ARGS="${EXTRA_ARGS} -Dbuild-rpm-rhel9";; 8) EXTRA_ARGS="${EXTRA_ARGS} -Dbuild-rpm-rhel8";; 7) EXTRA_ARGS="${EXTRA_ARGS} -Dbuild-rpm-rhel7";; esac; AMBARI_MVN_EXTRA_ARGS="${EXTRA_ARGS}" AMBARI_NO_EXIT=1 MAVEN_OPTS="-Xmx3g -XX:MaxMetaspaceSize=512m ${ARCH_OPTS}" python dev-support/docker/docker/bin/ambaribuild.py agent -b 2>&1 | tee /tmp/ambaribuild.log'
else
  echo "Skipping Ambari build/setup (SKIP_AMBARI_BUILD set)."
fi

server_cid="$(compose -f "${BASE_COMPOSE}" -f "${SMOKE_COMPOSE}" -p "${PROJECT_NAME}" ps -q ambari-server)"
[ -n "${server_cid}" ] || fail "ambari-server container not found after build"

echo "Waiting for Ambari server..."
wait_for_ambari "http://localhost:8080/api/v1/hosts"

host_name="$(wait_for_host_name 240 5)" || fail "No Ambari hosts registered"

if curl -fsS -u admin:admin "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}" >/dev/null; then
  echo "Cluster ${CLUSTER_NAME} already exists; reusing."
else
  echo "Creating cluster ${CLUSTER_NAME}..."
  curl -fsS -u admin:admin -H "X-Requested-By: ambari" -H "Content-Type: application/json" \
    -X POST "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}" \
    -d "{\"Clusters\":{\"version\":\"1.3\",\"stack_name\":\"ODP\"}}"
fi

echo "Registering ODP repository version..."
server_os_family="$(detect_server_os_family "${server_cid}")"
ODP_REPO_BASE_URL="${ODP_REPO_BASE_URL:-}"
if [ -z "${ODP_REPO_BASE_URL}" ]; then
  ODP_REPO_BASE_URL="$(default_repo_base_url "${server_os_family}")" || fail "Unsupported OS family ${server_os_family}. Set ODP_REPO_BASE_URL."
fi

repo_versions_json="$(curl -fsS -u admin:admin \
  "http://localhost:8080/api/v1/stacks/ODP/versions/1.3/repository_versions?fields=RepositoryVersions/repository_version,RepositoryVersions/id")"

repo_version_id="$(printf '%s' "${repo_versions_json}" | python3 -c 'import json,os,sys; data=json.load(sys.stdin); target=os.environ.get("ODP_REPO_VERSION");\n[print(item.get("RepositoryVersions", {}).get("id","")) or sys.exit(0) for item in data.get("items", []) if item.get("RepositoryVersions", {}).get("repository_version")==target]; sys.exit(0)')"

if [ -z "${repo_version_id}" ]; then
  create_repo_payload="$(cat <<JSON
{
  "RepositoryVersions": {
    "display_name": "${ODP_REPO_ID}",
    "repository_version": "${ODP_REPO_VERSION}"
  },
  "operating_systems": [
    {
      "OperatingSystems": {
        "os_type": "${server_os_family}"
      },
      "repositories": [
        {
          "Repositories": {
            "base_url": "${ODP_REPO_BASE_URL}",
            "repo_id": "${ODP_REPO_ID}",
            "repo_name": "${ODP_REPO_NAME}"
          }
        }
      ]
    }
  ]
}
JSON
)"
  curl -fsS -u admin:admin -H "X-Requested-By: ambari" -H "Content-Type: application/json" \
    -X POST "http://localhost:8080/api/v1/stacks/ODP/versions/1.3/repository_versions" \
    -d "${create_repo_payload}"

  repo_versions_json="$(curl -fsS -u admin:admin \
    "http://localhost:8080/api/v1/stacks/ODP/versions/1.3/repository_versions?fields=RepositoryVersions/repository_version,RepositoryVersions/id")"
  repo_version_id="$(printf '%s' "${repo_versions_json}" | python3 -c 'import json,os,sys; data=json.load(sys.stdin); target=os.environ.get("ODP_REPO_VERSION");\n[print(item.get("RepositoryVersions", {}).get("id","")) or sys.exit(0) for item in data.get("items", []) if item.get("RepositoryVersions", {}).get("repository_version")==target]; sys.exit(0)')"
fi

[ -n "${repo_version_id}" ] || fail "Unable to resolve repository version id for ${ODP_REPO_VERSION}"

cluster_versions_json="$(curl -fsS -u admin:admin \
  "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/stack_versions?fields=ClusterStackVersions/repository_version,ClusterStackVersions/id")"

cluster_stack_id="$(printf '%s' "${cluster_versions_json}" | python3 -c 'import json,os,sys; data=json.load(sys.stdin); target=os.environ.get("ODP_REPO_VERSION");\n[print(item.get("ClusterStackVersions", {}).get("id","")) or sys.exit(0) for item in data.get("items", []) if item.get("ClusterStackVersions", {}).get("repository_version")==target]; sys.exit(0)')"

if [ -z "${cluster_stack_id}" ]; then
  curl -fsS -u admin:admin -H "X-Requested-By: ambari" -H "Content-Type: application/json" \
    -X POST "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/stack_versions" \
    -d "{\"ClusterStackVersions\":{\"cluster_name\":\"${CLUSTER_NAME}\",\"stack\":\"ODP\",\"version\":\"1.3\",\"repository_version\":\"${ODP_REPO_VERSION}\"}}"
fi

if curl -fsS -u admin:admin "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/hosts/${host_name}" >/dev/null; then
  echo "Host ${host_name} already in cluster; skipping add."
else
  echo "Adding host ${host_name} to cluster..."
  curl -fsS -u admin:admin -H "X-Requested-By: ambari" \
    -X POST "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/hosts/${host_name}"
fi

echo "Registering services..."
for svc in ZOOKEEPER KAFKA AMBARI_METRICS KERBEROS POLARIS; do
  if curl -fsS -u admin:admin "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/services/${svc}" >/dev/null; then
    echo "Service ${svc} already registered; skipping."
  else
    curl -fsS -u admin:admin -H "X-Requested-By: ambari" \
      -H "Content-Type: application/json" \
      -X POST "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/services/${svc}" \
      -d "{\"ServiceInfo\":{\"service_name\":\"${svc}\",\"desired_repository_version_id\":${repo_version_id}}}"
  fi
done

echo "Adding Polaris components..."
if ! curl -fsS -u admin:admin \
  "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/services/POLARIS/components/POLARIS_SERVER" >/dev/null; then
  curl -fsS -u admin:admin -H "X-Requested-By: ambari" \
    -X POST "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/services/POLARIS/components/POLARIS_SERVER"
fi
if ! curl -fsS -u admin:admin \
  "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/hosts/${host_name}/host_components/POLARIS_SERVER" >/dev/null; then
  curl -fsS -u admin:admin -H "X-Requested-By: ambari" \
    -X POST "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/hosts/${host_name}/host_components/POLARIS_SERVER"
fi
if ! curl -fsS -u admin:admin \
  "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/services/POLARIS/components/POLARIS_CLIENT" >/dev/null; then
  curl -fsS -u admin:admin -H "X-Requested-By: ambari" \
    -X POST "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/services/POLARIS/components/POLARIS_CLIENT"
fi
if ! curl -fsS -u admin:admin \
  "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/hosts/${host_name}/host_components/POLARIS_CLIENT" >/dev/null; then
  curl -fsS -u admin:admin -H "X-Requested-By: ambari" \
    -X POST "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/hosts/${host_name}/host_components/POLARIS_CLIENT"
fi

echo "Storing OIDC admin credential..."
if curl -fsS -u admin:admin \
  "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/credentials/oidc.admin.credential" >/dev/null; then
  echo "OIDC admin credential already exists; skipping."
else
  curl -fsS -u admin:admin -H "X-Requested-By: ambari" -H "Content-Type: application/json" \
    -X POST "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/credentials/oidc.admin.credential" \
    -d '{"Credential":{"principal":"admin","key":"admin","type":"temporary"}}'
fi

echo "Applying Kerberos configs..."
curl -fsS -u admin:admin -H "X-Requested-By: ambari" -H "Content-Type: application/json" \
  -X PUT "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}" \
  -d '{
    "Clusters": {
      "desired_config": [
        {
          "type": "kerberos-env",
          "tag": "smoke-kerberos-env",
          "properties": {
            "realm": "AMBARI.EXAMPLE.COM",
            "kdc_type": "mit-kdc"
          }
        },
        {
          "type": "krb5-conf",
          "tag": "smoke-krb5-conf",
          "properties": {
            "manage_krb5_conf": "true",
            "conf_dir": "/etc",
            "content": "[libdefaults]\n default_realm = AMBARI.EXAMPLE.COM\n"
          }
        }
      ]
    }
  }'

echo "Applying Polaris OIDC configs..."
curl -fsS -u admin:admin -H "X-Requested-By: ambari" -H "Content-Type: application/json" \
  -X PUT "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}" \
  -d '{
    "Clusters": {
      "desired_config": [
        {
          "type": "oidc-env",
          "tag": "smoke-oidc-env",
          "properties": {
            "oidc_admin_url": "http://keycloak:8080",
            "oidc_realm": "ambari",
            "oidc_admin_realm": "master",
            "oidc_admin_client_id": "admin-cli",
            "oidc_verify_tls": "false"
          }
        },
        {
          "type": "polaris-application-properties",
          "tag": "smoke-polaris-auth",
          "properties": {
            "polaris.authentication.type": "external"
          }
        }
      ]
    }
  }'

echo "Enabling Kerberos (manage_kerberos_identities=false) to trigger OIDC provisioning..."
enable_response="$(curl -fsS -u admin:admin -H "X-Requested-By: ambari" -H "Content-Type: application/json" \
  -X PUT "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}?manage_kerberos_identities=false" \
  -d "{\"Clusters\":{\"security_type\":\"KERBEROS\"}}")"

request_id="$(printf '%s' "${enable_response}" | python3 -c 'import json,sys; data=json.load(sys.stdin); req=data.get("Requests") or {}; print(req.get("id",""))')"

[ -n "${request_id}" ] || fail "Failed to get Kerberos enable request id"

echo "Waiting for request ${request_id} to complete..."
for i in $(seq 1 120); do
  status_json="$(curl -fsS -u admin:admin "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/requests/${request_id}")"
  status="$(printf '%s' "${status_json}" | python3 -c 'import json,sys; data=json.load(sys.stdin); req=data.get("Requests") or {}; print(req.get("request_status",""))')"
  if [ "${status}" = "COMPLETED" ]; then
    break
  fi
  if [ "${status}" = "FAILED" ]; then
    fail "Kerberos enable request failed"
  fi
  sleep 5
done

echo "Validating OIDC updates in Polaris config..."
polaris_config_json="$(curl -fsS -u admin:admin \
  "http://localhost:8080/api/v1/clusters/${CLUSTER_NAME}/configurations?type=polaris-application-properties")"

POLARIS_PROPS_JSON="$(printf '%s' "${polaris_config_json}" | python3 -c 'import json,sys; data=json.load(sys.stdin); items=data.get("items", []); \nprint(json.dumps(items[0].get(\"properties\", {}))) if items else sys.exit(1)')" || fail "Missing polaris-application-properties config"

export POLARIS_PROPS_JSON
export CLUSTER_NAME

python3 - <<'PY'
import json, os, sys
props = json.loads(os.environ["POLARIS_PROPS_JSON"])
cluster = os.environ["CLUSTER_NAME"]
expected_auth_url = "http://keycloak:8080/realms/ambari"
expected_client_id = f"{cluster}-polaris"

def require(key, expected=None):
  if key not in props or props[key] is None:
    raise SystemExit(f"Missing Polaris OIDC property: {key}")
  if expected is not None and props[key] != expected:
    raise SystemExit(f"Unexpected {key}: {props[key]} (expected {expected})")

require("quarkus.oidc.tenant-enabled", "true")
require("quarkus.oidc.auth-server-url", expected_auth_url)
require("quarkus.oidc.client-id", expected_client_id)
require("quarkus.oidc.application-type", "service")
secret = props.get("quarkus.oidc.credentials.secret")
if not secret:
  raise SystemExit("Missing Polaris OIDC client secret")
PY

echo "Validating Kafka metrics reporter config..."
kafka_config="${ROOT_DIR}/ambari-server/src/main/resources/common-services/KAFKA/0.8.1/configuration/kafka-broker.xml"
grep -q "KafkaTimelineMetricsReporter" "${kafka_config}" \
  || fail "Kafka metrics reporter not configured in kafka-broker.xml"

echo "Validating Grafana Kafka dashboards..."
dash_base="${ROOT_DIR}/ambari-server/src/main/resources/common-services/AMBARI_METRICS/0.1.0/package/files/grafana-dashboards"
find "${dash_base}" -name "grafana-kafka-hosts.json" -print -quit | grep -q . \
  || fail "grafana-kafka-hosts.json not found"
find "${dash_base}" -name "grafana-kafka-topics.json" -print -quit | grep -q . \
  || fail "grafana-kafka-topics.json not found"

echo "Smoke test completed successfully."
