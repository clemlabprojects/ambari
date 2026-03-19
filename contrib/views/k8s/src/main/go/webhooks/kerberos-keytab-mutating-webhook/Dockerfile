# syntax=docker/dockerfile:1.7
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

# ---- OpenSSL stage ----
FROM --platform=$BUILDPLATFORM debian:bookworm-slim AS openssl
RUN apt-get update \
 && apt-get install -y --no-install-recommends openssl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
# Collect openssl binary and its runtime libraries to be copied into the final image
RUN mkdir -p /opt/openssl/bin /opt/openssl/lib \
 && cp /usr/bin/openssl /opt/openssl/bin/ \
 && (ldd /usr/bin/openssl | awk '/=>/ { print $(NF) }' | xargs -r -I{} cp -v {} /opt/openssl/lib/ || true)
# ---- Build stage ----
# Use BUILDPLATFORM so the builder image matches the machine running the build,
# while we cross-compile for TARGETOS/TARGETARCH.
FROM --platform=$BUILDPLATFORM golang:1.25 AS builder
WORKDIR /src

# Provided automatically by BuildKit/Buildx:
ARG TARGETOS
ARG TARGETARCH

# Enable module & build caches for speed
# (requires BuildKit; which Buildx uses)
RUN --mount=type=cache,target=/go/pkg/mod true
RUN --mount=type=cache,target=/root/.cache/go-build true

# 1) deps first for better layer caching
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod download

# 2) sources
COPY . .

# 3) (optional) ensure sums are tidy; cached too
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod tidy

# 4) build (static)
ENV CGO_ENABLED=0 \
    GOOS=$TARGETOS \
    GOARCH=$TARGETARCH
RUN --mount=type=cache,target=/root/.cache/go-build \
    go build -trimpath -ldflags="-s -w" -o /out/kerberos-keytab-mutating-webhook ./...

# ---- Run stage (distroless, non-root) ----
# Use TARGETPLATFORM so the final image matches the requested platform.
FROM --platform=$TARGETPLATFORM gcr.io/distroless/static:nonroot
WORKDIR /
COPY --from=builder /out/kerberos-keytab-mutating-webhook /kerberos-keytab-mutating-webhook
USER nonroot:nonroot

EXPOSE 8443
ENV LISTEN_ADDRESS=":8443"
VOLUME ["/tls"]
ENTRYPOINT ["/kerberos-keytab-mutating-webhook"]