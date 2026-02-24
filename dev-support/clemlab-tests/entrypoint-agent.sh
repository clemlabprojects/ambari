#!/usr/bin/env bash
set -euo pipefail

AMBARI_SRC="${AMBARI_SRC:-/tmp/ambari}"
SERVER_HOST="${AMBARI_SERVER_HOST:-ambari-server}"
AMBARI_AGENT_HOSTNAME="${AMBARI_AGENT_HOSTNAME:-}"

arch="$(uname -m)"
rpm_glob="${AMBARI_SRC}/ambari-agent/target/rpm/ambari-agent/RPMS/${arch}/ambari-agent-*.rpm"

if ! ls ${rpm_glob} >/dev/null 2>&1; then
  echo "[ambari-agent] ERROR: ambari-agent RPM not found at ${rpm_glob}" >&2
  exit 1
fi

echo "[ambari-agent] Installing ambari-agent RPM..."
sudo yum install -y ${rpm_glob}

if [ -z "${AMBARI_AGENT_HOSTNAME}" ]; then
  AMBARI_AGENT_HOSTNAME="$(hostname -f 2>/dev/null || hostname)"
fi

echo "[ambari-agent] Configuring ambari-agent.ini (hostname=${AMBARI_AGENT_HOSTNAME}, server=${SERVER_HOST})..."
sudo sed -i "s/^hostname=.*/hostname=${SERVER_HOST}/" /etc/ambari-agent/conf/ambari-agent.ini
# Ambari Agent expects to talk to the server over HTTPS on 8440 by default.
sudo sed -i "s/^url_port=.*/url_port=8440/" /etc/ambari-agent/conf/ambari-agent.ini
sudo sed -i "s/^secured_url_port=.*/secured_url_port=8441/" /etc/ambari-agent/conf/ambari-agent.ini
# Remove any stray 'server=' entries (e.g. under [logging]) to avoid config corruption.
sudo sed -i "/^server=.*/d" /etc/ambari-agent/conf/ambari-agent.ini

echo "[ambari-agent] Waiting for Ambari server ${SERVER_HOST}:8080..."
for i in $(seq 1 60); do
  if curl -fsS --connect-timeout 2 --max-time 5 \
    "http://${SERVER_HOST}:8080/api/v1/hosts" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "[ambari-agent] Starting ambari-agent in foreground..."
export AMBARI_AGENT_RUN_IN_FOREGROUND=true
exec sudo -E ambari-agent start
