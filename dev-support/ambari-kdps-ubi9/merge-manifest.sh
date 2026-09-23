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
# Merge the per-arch images pushed by build-image.sh (<tag>-amd64 / <tag>-arm64) into one
# multi-arch manifest at the bare <tag>. Registry-side assembly only: no build, no qemu, so
# it runs on either agent once both arch builds have pushed.

set -euo pipefail

HARBOR="${HARBOR:-registry.clemlab.com}"
PROJECT="${PROJECT:-clemlabprojects}"
IMAGE_NAME="${IMAGE_NAME:-ambari-kdps}"
AMBARI_VERSION="${AMBARI_VERSION:-2.8.2.0-137}"

ref="${HARBOR}/${PROJECT}/${IMAGE_NAME}"
tag="${AMBARI_VERSION}"

echo ">>> merging ${ref}:${tag}  <=  ${tag}-amd64 + ${tag}-arm64"
docker buildx imagetools create -t "${ref}:${tag}" \
  "${ref}:${tag}-amd64" "${ref}:${tag}-arm64"
docker buildx imagetools inspect "${ref}:${tag}" | grep -iE "Name:|Platform:" | head -6
echo "=== multi-arch manifest published: ${ref}:${tag} ==="
