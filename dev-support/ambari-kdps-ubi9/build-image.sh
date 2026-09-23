#!/bin/bash
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
# Builds and pushes the standalone Ambari + KDPS image.
#
# Native per-architecture build by default, the same convention the ODP images follow: each
# agent builds only its own architecture and pushes an arch-suffixed tag, so nothing runs
# under emulation. merge-manifest.sh assembles the multi-arch manifest under the bare tag
# once both agents have pushed.
#
# A single-run multi-arch build stays available for ad-hoc use: pass a comma-separated
# PLATFORMS (PLATFORMS=linux/amd64,linux/arm64) and the bare tag is pushed directly. That
# needs a builder with both platforms registered, which a plain agent does not have.

set -xe

HARBOR="${HARBOR:-registry.clemlab.com}"
PROJECT="${PROJECT:-clemlabprojects}"
IMAGE_NAME="${IMAGE_NAME:-ambari-kdps}"

AMBARI_VERSION="${AMBARI_VERSION:-2.8.2.0-137}"
UBI_VERSION="${UBI_VERSION:-9.6}"
HELM_VERSION="${HELM_VERSION:-3.16.3}"

# Where the ambari-server RPM lives. There is no public mirror, so this has to be given: the
# release pipeline publishes per-OS repositories under the build host's ambari-release tree.
AMBARI_REPO_URL="${AMBARI_REPO_URL:-}"
if [ -z "${AMBARI_REPO_URL}" ]; then
  echo "AMBARI_REPO_URL is required, e.g. https://<build-host>/ambari-release/dist/centos9-aarch64/1.x/BUILDS/${AMBARI_VERSION}/rpms" >&2
  exit 1
fi

# Canonical architecture, from $OS_ARCH when the caller set it (CI does) or from the host.
arch_input="${OS_ARCH:-$(uname -m)}"
case "$arch_input" in
  *aarch64*|*arm64*) osArch='arm64' ;;
  *)                 osArch='amd64' ;;
esac
echo "Detected architecture: ${osArch}"

PLATFORMS="${PLATFORMS:-linux/${osArch}}"

cd "$(dirname "$0")"

BUILD_ARGS="--build-arg UBI_VERSION=${UBI_VERSION}
            --build-arg AMBARI_VERSION=${AMBARI_VERSION}
            --build-arg AMBARI_REPO_URL=${AMBARI_REPO_URL}
            --build-arg HELM_VERSION=${HELM_VERSION}"

case "$PLATFORMS" in
  *,*)
    # Several platforms in one run: only buildx can do it, and only on a builder that has both
    # registered. 'clem' (docker-container driver) is that builder on the agents that have one;
    # the default docker driver cannot export straight to a registry.
    IMG_TAG="${AMBARI_VERSION}"
    TARGET_IMAGE="${HARBOR}/${PROJECT}/${IMAGE_NAME}:${IMG_TAG}"
    BUILDER_ARGS=""
    if docker buildx inspect clem >/dev/null 2>&1; then
      BUILDER_ARGS="--builder clem"
    fi
    echo "Building ${TARGET_IMAGE} for ${PLATFORMS}" >&2
    docker buildx build ${BUILDER_ARGS} --platform "${PLATFORMS}" ${BUILD_ARGS} \
      -t "${TARGET_IMAGE}" --push .
    ;;
  *)
    # Native single architecture, which is what CI does. Plain build and push: no buildx, so no
    # assumption about which driver an agent happens to have. ubuntu24 has only the default docker
    # driver, which cannot --push, and a one-platform build needs nothing buildx offers.
    IMG_TAG="${AMBARI_VERSION}-${osArch}"
    TARGET_IMAGE="${HARBOR}/${PROJECT}/${IMAGE_NAME}:${IMG_TAG}"
    echo "Building ${TARGET_IMAGE} for ${PLATFORMS}" >&2
    docker build ${BUILD_ARGS} -t "${TARGET_IMAGE}" .
    docker push "${TARGET_IMAGE}"
    ;;
esac

echo "Pushed ${TARGET_IMAGE}" >&2
