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
# Ambari as a standalone host for the KDPS view, on UBI 9.
#
# This image manages no cluster: no agents, no host registration, no stack deployment. It exists so
# KDPS can run *inside* the Kubernetes or OpenShift cluster it deploys to, which is the only way to
# work when the kube API is not reachable from outside. In the cluster, the view authenticates with
# its ServiceAccount token — the fabric8 client picks that up on its own — so no kubeconfig is
# shipped and no API endpoint is exposed.
#
# The data platforms it manages are reached as KDPS platform contexts: a remote Ambari, a Cloudera
# Manager, or a manually described Hadoop cluster. That is what makes a cluster-less Ambari useful
# rather than odd.
ARG UBI_VERSION=9.6

FROM registry.access.redhat.com/ubi9/ubi:${UBI_VERSION}

# Ambari's own requirements are modest: python3 and openssl (per the RPM), postgresql for the client
# and the schema tooling, and a JRE to run the server. Everything else here is for the view: helm,
# which KDPS shells out to, and git for the GitOps deployment mode.
# UBI_VERSION is redeclared here: an ARG before FROM only reaches the FROM line.
ARG UBI_VERSION
ARG AMBARI_VERSION=2.8.2.0-137
ARG AMBARI_REPO_URL
ARG HELM_VERSION=3.16.3
# Ambari 2.8 refuses to set up on anything older: "Ambari Server requires JDK 17 or 21".
ARG JAVA_PACKAGE=java-17-openjdk-headless

LABEL org.opencontainers.image.title="Ambari (standalone) with the KDPS view" \
      org.opencontainers.image.description="Cluster-less Ambari hosting the Kubernetes Data Platform Services view, for running inside the cluster it deploys to" \
      org.opencontainers.image.vendor="Clemlab" \
      org.opencontainers.image.base.name="registry.access.redhat.com/ubi9/ubi:${UBI_VERSION}"

RUN dnf install -y --setopt=install_weak_deps=False \
        ${JAVA_PACKAGE} \
        python3 python3-pip \
        postgresql \
        openssl ca-certificates \
        tar gzip git \
    && dnf clean all && rm -rf /var/cache/dnf

# python3-distro is a hard requirement of the server RPM and is not in the UBI repositories.
RUN pip3 install --no-cache-dir distro

# helm: KDPS runs the binary rather than embedding a Helm library, so the image must carry it.
RUN set -eux; \
    arch="$(uname -m)"; \
    case "$arch" in aarch64) helm_arch=arm64 ;; x86_64) helm_arch=amd64 ;; *) echo "unsupported arch $arch" >&2; exit 1 ;; esac; \
    curl -fsSL "https://get.helm.sh/helm-v${HELM_VERSION}-linux-${helm_arch}.tar.gz" -o /tmp/helm.tgz; \
    tar -xzf /tmp/helm.tgz -C /tmp; \
    install -m 0755 "/tmp/linux-${helm_arch}/helm" /usr/local/bin/helm; \
    rm -rf /tmp/helm.tgz "/tmp/linux-${helm_arch}"; \
    helm version --short

# The server itself, from the ODP Ambari repository. AMBARI_REPO_URL is required: the RPM is not
# published to a public mirror, so the build must be told where it lives.
RUN set -eux; \
    test -n "${AMBARI_REPO_URL}" || { echo "AMBARI_REPO_URL build-arg is required" >&2; exit 1; }; \
    printf '[ambari]\nname=Ambari\nbaseurl=%s\nenabled=1\ngpgcheck=0\n' "${AMBARI_REPO_URL}" > /etc/yum.repos.d/ambari.repo; \
    dnf install -y --setopt=install_weak_deps=False "ambari-server-${AMBARI_VERSION}"; \
    dnf clean all; rm -rf /var/cache/dnf

# Silent setup at build time so the container starts without an interactive step. The database is
# configured at run time instead: it is external, and its address is not known when the image is
# built.
# Ambari 2.8 wants BOTH JDKs named: -j is the stack JDK it hands to agents, --ambari-java-home is
# the one the server itself runs on. Without the second it refuses with "Path to AMBARI_JAVA_HOME
# should be specified". The path is resolved from the installed java rather than hardcoded, because
# the JRE directory name carries the package version and moves with every UBI refresh.
# A stable path for the JDK, so the entrypoint does not have to rediscover a directory whose name
# carries the package version.
RUN set -eux; \
    JH="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"; \
    ln -sfn "${JH}" /usr/lib/jvm/ambari-java; \
    echo "ambari java home -> ${JH}"
ENV AMBARI_JAVA_HOME=/usr/lib/jvm/ambari-java

# Bake the JDK configuration into ambari.properties. The database arguments are placeholders: silent
# setup with no --database falls back to Ambari's EMBEDDED PostgreSQL and runs initdb, which cannot
# work in a build container and fails with "Unable to start PostgreSQL server". Naming an external
# postgres keeps setup to writing properties; the entrypoint re-runs it with the real address.
RUN set -eux; \
    ambari-server setup -s \
        -j "${AMBARI_JAVA_HOME}" --ambari-java-home="${AMBARI_JAVA_HOME}" \
        --database=postgres --databasehost=localhost --databaseport=5432 \
        --databasename=ambari --databaseusername=ambari --databasepassword=placeholder

# Two things in the packaging assume the server runs as root. Relax both; see the script.
COPY openshift-nonroot.py /tmp/openshift-nonroot.py
RUN python3 /tmp/openshift-nonroot.py && rm -f /tmp/openshift-nonroot.py

# The server re-packs every stack's package directory at start-up when the archive is out of date.
# Doing it here, as root, means the pod finds the hashes already current and only has to read them.
RUN python3 -c "import sys; sys.path.insert(0, '/usr/lib/ambari-server/lib'); \
from ambari_server.resourceFilesKeeper import ResourceFilesKeeper; \
ResourceFilesKeeper('/var/lib/ambari-server/resources', '/var/lib/ambari-server/resources/stacks').perform_housekeeping()"

# OpenShift runs containers as an arbitrary UID in group 0. Everything Ambari writes to therefore
# has to be group-owned by root and group-writable, or the server cannot start. Note what is NOT
# here: no USER root, no runAsUser — an image that needs uid 0 cannot run under the restricted SCC.
RUN for d in /var/lib/ambari-server /var/log/ambari-server /var/run/ambari-server \
             /etc/ambari-server /usr/lib/ambari-server; do \
        mkdir -p "$d"; chgrp -R 0 "$d"; chmod -R g=u "$d"; \
    done

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh

EXPOSE 8080 8443
USER 1001
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
