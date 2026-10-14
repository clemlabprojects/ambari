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
# Base image, Ambari repository and Helm source are build arguments so the image can be rebuilt from
# mirrors in an air-gapped site (see README.md, "Rebuilding the image at a customer site"). What comes
# from a mirror is checked against values in this directory, not values the mirror serves: the Helm
# tarball against the pinned sha256 below, the Ambari packages against the Clemlab RPM signing key.
# Everything else comes from the base image's own repositories, under Red Hat's signature.
ARG BASE_IMAGE=registry.access.redhat.com/ubi9/ubi
ARG UBI_VERSION=9.6

FROM ${BASE_IMAGE}:${UBI_VERSION}

ARG BASE_IMAGE
ARG UBI_VERSION
ARG AMBARI_VERSION=2.8.2.0-137
# Yum repository holding the ambari-server RPM: the published Clemlab repository for the build, or a
# customer mirror of it (reposync).
ARG AMBARI_REPO_URL
ARG HELM_VERSION=3.22.0
# Full URL of the Helm tarball; empty = https://get.helm.sh/helm-v<HELM_VERSION>-linux-<arch>.tar.gz
ARG HELM_URL=
# sha256 of the official helm-v<HELM_VERSION>-linux-<arch>.tar.gz, from the Helm release page (and its
# .sha256sum files on get.helm.sh). They are pinned here rather than fetched next to the tarball, so a
# tampered mirror cannot serve a matching checksum. A new HELM_VERSION needs both values replaced.
ARG HELM_SHA256_AMD64=1e4ab49e429626cf6c6958d914248b78c9730803c2751b87627e171dc800e7bb
ARG HELM_SHA256_ARM64=f14e804dfee240f55525b667488fe9adca349e63e00c9af634c0beb1421ac310
# Java runtime Ambari runs on (17 is what the server is built and tested with).
ARG JAVA_PACKAGE=java-17-openjdk-headless
# View jars removed from the image: this Ambari only hosts KDPS (+ the admin view). Dropping the
# HDFS Files, Capacity Scheduler, Tez and SQL Assistant views saves ~570 MB; an operator who wants one
# back mounts its jar under /opt/ambari-views (entrypoint.sh installs those at start).
ARG DROP_VIEWS="files capacity-scheduler tez-view sql-assistant-view"

LABEL org.opencontainers.image.title="Ambari (standalone) with the KDPS view" \
      org.opencontainers.image.description="Cluster-less Ambari hosting the Kubernetes Data Platform Services view, for running inside the cluster it deploys to" \
      org.opencontainers.image.vendor="Clemlab" \
      org.opencontainers.image.base.name="${BASE_IMAGE}:${UBI_VERSION}"

# procps-ng: ambari-server stop, status, restart and the setup-* commands find the running server with
# ps. The RPM does not require it and UBI does not ship it; without it those commands fail with
# "No such file or directory: 'ps'" whenever the server is running.
# python3-distro: the server's Python imports it and the RPM requires it. It comes from UBI AppStream like
# the rest, so the build needs no Python package index.
RUN dnf install -y --setopt=install_weak_deps=False \
        ${JAVA_PACKAGE} \
        python3 python3-distro \
        postgresql \
        openssl ca-certificates \
        tar gzip git \
        procps-ng \
    && dnf clean all && rm -rf /var/cache/dnf

# Helm CLI. The view runs install, upgrade, uninstall and the repository operations on the Helm library
# it embeds; it calls this binary for release rollback and history only.
RUN set -eux; \
    arch="$(uname -m)"; \
    case "$arch" in aarch64) helm_arch=arm64 ;; x86_64) helm_arch=amd64 ;; *) echo "unsupported arch $arch" >&2; exit 1 ;; esac; \
    case "$helm_arch" in amd64) sha="${HELM_SHA256_AMD64}" ;; arm64) sha="${HELM_SHA256_ARM64}" ;; esac; \
    test -n "$sha" || { echo "HELM_SHA256_$(echo "$helm_arch" | tr a-z A-Z) build-arg is required: the sha256 of the official helm-v${HELM_VERSION}-linux-${helm_arch}.tar.gz" >&2; exit 1; }; \
    url="${HELM_URL:-https://get.helm.sh/helm-v${HELM_VERSION}-linux-${helm_arch}.tar.gz}"; \
    curl -fsSL "$url" -o /tmp/helm.tgz; \
    echo "${sha}  /tmp/helm.tgz" | sha256sum -c - \
        || { echo "$url is not the official helm-v${HELM_VERSION}-linux-${helm_arch}.tar.gz (sha256 differs from HELM_SHA256_$(echo "$helm_arch" | tr a-z A-Z))" >&2; exit 1; }; \
    tar -xzf /tmp/helm.tgz -C /tmp; \
    install -m 0755 "/tmp/linux-${helm_arch}/helm" /usr/local/bin/helm; \
    rm -rf /tmp/helm.tgz "/tmp/linux-${helm_arch}"; \
    helm version --short

ENV AMBARI_JAVA_HOME=/usr/lib/jvm/ambari-java

COPY openshift-nonroot.py /tmp/openshift-nonroot.py
# Public key the Clemlab release pipeline signs the Ambari RPMs with. Shipped here rather than downloaded
# so that a mirrored repository is checked against Clemlab's key, not against one the mirror serves.
COPY RPM-GPG-KEY-clemlab /etc/pki/rpm-gpg/RPM-GPG-KEY-clemlab

# Everything that writes under the Ambari directories happens in ONE layer. Each step below rewrites
# the ~1 GB of files the RPM installs (setup's ownership pass, the resource-files keeper, the
# OpenShift group-permission pass); as separate RUN instructions they stored the payload three times
# and the image weighed 3.3 GB compressed instead of ~0.6 GB.
#
# Right after the install the view jar is checked for this machine's Helm native library. The view calls
# Helm in-process through a per-architecture library: a package built for the other architecture still
# starts and serves every Kubernetes page, then fails each deploy, upgrade and release list, so the image
# build refuses it.
RUN set -eux; \
    test -n "${AMBARI_REPO_URL}" || { echo "AMBARI_REPO_URL build-arg is required" >&2; exit 1; }; \
    rpm --import /etc/pki/rpm-gpg/RPM-GPG-KEY-clemlab; \
    printf '[ambari]\nname=Ambari\nbaseurl=%s\nenabled=1\ngpgcheck=1\ngpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-clemlab\n' \
        "${AMBARI_REPO_URL}" > /etc/yum.repos.d/ambari.repo; \
    dnf install -y --setopt=install_weak_deps=False "ambari-server-${AMBARI_VERSION}"; \
    dnf clean all; rm -rf /var/cache/dnf; \
    \
    case "$(uname -m)" in x86_64|amd64) helm_arch=amd64;; aarch64|arm64) helm_arch=arm64;; \
        *) echo "No Helm native library for architecture $(uname -m)" >&2; exit 1;; esac; \
    for jar in /var/lib/ambari-server/resources/views/k8s-view-*.jar; do \
        natives="$(python3 -c 'import re, sys, zipfile; print(" ".join(n for n in zipfile.ZipFile(sys.argv[1]).namelist() if re.match(r"WEB-INF/lib/(linux|darwin|windows)-[^/]+[.]jar$", n)))' "$jar")"; \
        case "$natives" in "WEB-INF/lib/linux-${helm_arch}-"*.jar) ;; \
            *) echo "$jar must hold exactly the Helm native library linux-${helm_arch}, found: ${natives:-none}" >&2; exit 1;; esac; \
        case "$natives" in *" "*) echo "$jar holds several Helm native libraries: $natives" >&2; exit 1;; esac; \
    done; \
    \
    for v in ${DROP_VIEWS}; do rm -fv /var/lib/ambari-server/resources/views/${v}-*.jar; done; \
    \
    JH="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"; \
    ln -sfn "${JH}" "${AMBARI_JAVA_HOME}"; \
    echo "ambari java home -> ${JH}"; \
    \
    ambari-server setup -s \
        -j "${AMBARI_JAVA_HOME}" --ambari-java-home="${AMBARI_JAVA_HOME}" \
        --database=postgres --databasehost=localhost --databaseport=5432 \
        --databasename=ambari --databaseusername=ambari --databasepassword=placeholder; \
    \
    python3 /tmp/openshift-nonroot.py; rm -f /tmp/openshift-nonroot.py; \
    python3 -c "import sys; sys.path.insert(0, '/usr/lib/ambari-server/lib'); \
from ambari_server.resourceFilesKeeper import ResourceFilesKeeper; \
ResourceFilesKeeper('/var/lib/ambari-server/resources', '/var/lib/ambari-server/resources/stacks').perform_housekeeping()"; \
    \
    for d in /var/lib/ambari-server /var/log/ambari-server /var/run/ambari-server \
             /etc/ambari-server /usr/lib/ambari-server; do \
        mkdir -p "$d"; chgrp -R 0 "$d"; chmod -R g=u "$d"; \
    done; \
    ls -la /var/lib/ambari-server/resources/views/

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh

EXPOSE 8080 8443
# OpenShift runs the container as an arbitrary user without a passwd entry, so HOME would be "/", which that user
# cannot write. Helm keeps its chart cache and registry settings under HOME: without a writable one every chart
# install from KDPS fails with "mkdir /.cache: permission denied". The Ambari data directory is group-writable.
# Set here, after the build steps, so they keep writing their own files where they did before.
ENV HOME=/var/lib/ambari-server
USER 1001
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
