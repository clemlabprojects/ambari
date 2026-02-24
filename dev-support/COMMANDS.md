# Ambari Dev Commands

This file lists the common commands for building, running, and testing Ambari in the local Docker setup.

## Build RPMs (once)

```bash
./dev-support/clemlab-tests/build.sh
```

## Run Ambari Server + Agents (from built RPMs)

Starts the runtime containers and installs the server/agent RPMs from the mounted repo.

```bash
./dev-support/clemlab-tests/run.sh
```

Defaults:

- Ambari UI: http://localhost:8080 (admin/admin)
- Keycloak: http://localhost:18080

Disable Keycloak in `run.sh`:

```bash
INCLUDE_KEYCLOAK=0 ./dev-support/clemlab-tests/run.sh
```

Follow logs:

```bash
docker compose -f dev-support/clemlab-tests/docker-compose.yml \
  -f dev-support/clemlab-tests/docker-compose.runtime.yml \
  -f dev-support/clemlab-tests/docker-compose.smoke.yml \
  -p ambari-dev logs -f ambari-server ambari-agent-1
```

## Smoke Test (OIDC + Polaris)

Full smoke:

```bash
./dev-support/clemlab-tests/smoke.sh
```

Skip rebuild / reuse running containers:

```bash
SKIP_COMPOSE_DOWN=1 SKIP_COMPOSE_UP=1 SKIP_AMBARI_BUILD=1 \
  ./dev-support/clemlab-tests/smoke.sh
```

## Unit Tests Only (inside container)

```bash
docker exec -it $(docker compose -f dev-support/clemlab-tests/docker-compose.yml \
  -f dev-support/clemlab-tests/docker-compose.runtime.yml \
  -p ambari-dev ps -q ambari-server) \
  bash -lc 'cd /tmp/ambari && python dev-support/docker/docker/bin/ambaribuild.py test'
```

## Stop Containers

```bash
./dev-support/clemlab-tests/stop.sh
```
