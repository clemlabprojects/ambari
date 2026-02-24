#!/usr/bin/env bash
set -euo pipefail

AMBARI_SRC="${AMBARI_SRC:-/tmp/ambari}"
export AMBARI_SRC

echo "[ambari-server] Waiting for database..."
DB_HOST="${AMBARI_DB_HOST:-db-ambari-test}"
DB_PORT="${AMBARI_DB_PORT:-5432}"
for i in $(seq 1 60); do
  if timeout 2 bash -lc ">/dev/tcp/${DB_HOST}/${DB_PORT}" 2>/dev/null; then
    break
  fi
  sleep 2
done

echo "[ambari-server] Installing and starting server from built RPMs..."
export AMBARI_NO_EXIT=0
python "${AMBARI_SRC}/dev-support/docker/docker/bin/ambaribuild.py" server

# ambaribuild.py keeps the container alive, but make sure logs are visible if it returns.
echo "[ambari-server] Ambari server started. Tailing log..."
tail -F /var/log/ambari-server/ambari-server.log
