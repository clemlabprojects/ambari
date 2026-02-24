# Build And Test (Local)

This repo supports three useful local flows:
- Build and run Ambari in Docker without running tests.
- Run the full Docker smoke flow (includes Keycloak + cluster setup).
- Run Maven and Python tests locally.

All commands below assume you are at the repo root:

```bash
cd ~/luc-data/DPH/ambari-clemlabprojects
```

## Prerequisites

- Docker Desktop running
- Java 17 available at:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.12.jdk/Contents/Home
```

- Recommended Maven memory settings to reduce freezes:

```bash
export MAVEN_OPTS="-Xmx3g -XX:MaxMetaspaceSize=512m"
```

- On arm64 builds, the scripts automatically append `-Dos.arch=aarch64` to `MAVEN_OPTS` so the frontend plugin selects the correct Node tarball. If you already set `-Dos.arch`, it will not be duplicated.

- Maven repository priority (Clemlab Nexus):
  The Docker helper scripts install `dev-support/clemlab-tests/maven-settings.xml`
  into the container at `~/.m2/settings.xml` before running Maven.

- Optional: override Docker platform detection (scripts auto-detect):

```bash
export DOCKER_PLATFORM=linux/amd64
# or
export DOCKER_PLATFORM=linux/arm64/v8
```

## 1) Build + Run Ambari In Docker (No Tests)

Use the helper scripts in `dev-support/clemlab-tests`.

Build RPMs (tests skipped):

```bash
bash dev-support/clemlab-tests/build.sh
```

Install and run Ambari server + agent from the built RPMs:

```bash
bash dev-support/clemlab-tests/run.sh
```

Ambari UI:
- http://localhost:8080
- user: `admin`
- password: `admin`

Notes:
- The repo is mounted into the container at `/tmp/ambari`.
- These scripts skip tests (`-DskipTests`) and RAT (`-Drat.skip=true`).
- Default Docker Compose project name: `ambari-dev`.
- Maven cache is persisted in a named volume at `/root/.m2` to avoid re-downloading jars.

Cleanup:

```bash
bash dev-support/clemlab-tests/stop.sh
# dev only:
# STOP_SMOKE=0 bash dev-support/clemlab-tests/stop.sh
# remove volumes too (cold start):
# CLEAN_VOLUMES=1 bash dev-support/clemlab-tests/stop.sh
```

Optional flags:

```bash
export PROJECT_NAME=ambari-dev
export SKIP_BASE_BUILD=1
export AMBARI_BASE_IMAGE=rockylinux:9
export AMBARI_MVN_EXTRA_ARGS="-Dstack.distribution=ODP"
export MAVEN_SETTINGS_TEMPLATE=~/path/to/your/settings.xml
```

Arm64 note (Ambari Web build):
- `ambari-web` uses Node 4.5.0 via `frontend-maven-plugin`.
- On arm64, keep `MAVEN_OPTS` with `-Dos.arch=aarch64` (the helper scripts inject it automatically) so the plugin resolves an arm64 Node tarball.

## 2) Full Docker Smoke Flow (Cluster + OIDC/Keycloak)

This flow builds, starts containers (including Keycloak), and drives Ambari APIs.

```bash
export AMBARI_SRC="$PWD"

bash dev-support/clemlab-tests/smoke.sh
```

Smoke cleanup:

```bash
STOP_DEV=0 bash dev-support/clemlab-tests/stop.sh
```

## 3) Run Tests Locally

### Ambari Server First

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.12.jdk/Contents/Home
export MAVEN_OPTS="-Xmx3g -XX:MaxMetaspaceSize=512m"

mvn -pl ambari-server -am -Drat.skip=true test
```

### Full Repo

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.12.jdk/Contents/Home
export MAVEN_OPTS="-Xmx3g -XX:MaxMetaspaceSize=512m"

mvn -Drat.skip=true test
```

### Python Unit Tests (Ambari Server)

If you use a virtualenv (recommended):

```bash
python3 -m venv .venv-ambari-test
source .venv-ambari-test/bin/activate
pip install -U pip
```

Run the Ambari server Python unit tests:

```bash
PYTHONPATH=ambari-common/src/main/python:ambari-server/src/main/python:ambari-server/src/test/python:ambari-agent/src/main/python \
  python ambari-server/src/test/python/unitTests.py
```

## Tips

- If Maven runs out of memory or freezes, increase `MAVEN_OPTS`:

```bash
export MAVEN_OPTS="-Xmx4g -XX:MaxMetaspaceSize=1g"
```

- For Docker issues, always start with a clean stop:

```bash
bash dev-support/clemlab-tests/stop.sh
```
