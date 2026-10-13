/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type { HelmRepo } from '../../types';

/** What the GitOps preview of a deploy is built from, as the wizard holds it. */
export interface FluxPreviewInput {
  releaseName: string;
  namespace: string;
  repoId?: string;
  /** The chart repository configured in KDPS, when the wizard found it. */
  repo?: HelmRepo;
  chart: string;
  values: Record<string, any>;
  pathPrefix?: string;
}

/** The manifests a GitOps deploy commits into the release's directory, and where. */
export interface FluxPreview {
  directory: string;
  source: Record<string, any>;
  release: Record<string, any>;
}

/**
 * The chart name as the Flux source knows it: the bare name for an HTTP repository, which indexes charts by name; the
 * path below the registry, without a tag, for an OCI registry. As the backend writes it.
 */
export function chartNameInSource(chart: string, oci: boolean): string {
  const name = chart.trim();
  const slash = name.lastIndexOf('/');
  if (!oci) return name.substring(slash + 1);
  const colon = name.lastIndexOf(':');
  return colon > slash ? name.substring(0, colon) : name;
}

/**
 * A preview of what a GitOps deploy commits: the HelmRepository of the release's own, named
 * `<release>-<repository>` in the release's namespace with the chart repository's URL, and the HelmRelease reading
 * it without naming a namespace. The Secrets of an authenticated repository are named as the backend names them;
 * the backend writes them through the Kubernetes API, never to Git. A platform that keeps its chart sources in one
 * namespace (a view setting) reads them there instead.
 */
export function fluxPreview(input: FluxPreviewInput): FluxPreview {
  const repoId = input.repoId || 'repo';
  const sourceName = `${input.releaseName}-${repoId}`;
  const repo = input.repo;
  const oci = repo?.type === 'OCI';
  const url = !repo?.url
    ? 'resolved-by-backend'
    : oci ? `oci://${repo.url.trim().replace(/^[a-zA-Z]+:\/\//, '')}` : repo.url.trim();
  const spec: Record<string, any> = { interval: '5m' };
  if (oci) spec.type = 'oci';
  spec.url = url;
  if (repo && repo.authMode && repo.authMode !== 'anonymous') {
    spec.secretRef = { name: repo.fluxSecretName?.trim() || `${sourceName}-repo-auth` };
  }
  if (repo?.fluxCertSecretName?.trim()) {
    spec.certSecretRef = { name: repo.fluxCertSecretName.trim() };
  } else if (repo?.caTruststores?.trim()) {
    spec.certSecretRef = { name: `${sourceName}-repo-ca` };
  }
  return {
    directory: `${input.pathPrefix || 'clusters/default'}/${input.namespace}/${input.releaseName}`,
    source: {
      apiVersion: 'source.toolkit.fluxcd.io/v1',
      kind: 'HelmRepository',
      metadata: { name: sourceName, namespace: input.namespace },
      spec,
    },
    release: {
      apiVersion: 'helm.toolkit.fluxcd.io/v2',
      kind: 'HelmRelease',
      metadata: { name: input.releaseName, namespace: input.namespace },
      spec: {
        interval: '5m',
        chart: {
          spec: {
            chart: chartNameInSource(input.chart, oci),
            sourceRef: { kind: 'HelmRepository', name: sourceName },
          },
        },
        values: input.values || {},
      },
    },
  };
}
