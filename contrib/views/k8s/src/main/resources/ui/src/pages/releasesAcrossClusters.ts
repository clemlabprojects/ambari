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

/** The Releases page lists the releases of every cluster KDPS knows, unless the operator picks one. */
export const ALL_CLUSTERS = 'all';

/** How many releases are read from each cluster when all of them are listed (one request per cluster). */
export const RELEASES_PER_CLUSTER = 500;

/** A cluster as the Releases page lists it. */
export interface ListedCluster {
  id: string;
  name?: string;
  default?: boolean;
}

/** A release row; only the fields this module reads. */
export interface ClusterRelease {
  namespace: string;
  name: string;
  kubernetesCluster?: string;
}

/** A cluster whose releases could not be read, and why. */
export interface UnlistedCluster {
  id: string;
  name: string;
  error: string;
}

/**
 * The key of a release row: the same release name may be deployed in the same namespace of two clusters, so the
 * cluster is part of it. Releases of the default cluster keep their "<namespace>/<name>" key.
 */
export const releaseRowKey = (release: ClusterRelease): string => {
  const cluster = release.kubernetesCluster;
  const base = `${release.namespace}/${release.name}`;
  return !cluster || cluster === 'default' ? base : `${cluster}:${base}`;
};

/** Why a request failed, without the JSON envelope the server answers errors in ({"status":500,"message":...}). */
const reasonOf = (reason: any): string => {
  const text: string = reason?.message || String(reason);
  try {
    const body = JSON.parse(text);
    if (body && typeof body === 'object' && (body.message || body.error)) return String(body.message || body.error);
  } catch { /* not JSON: the message as it is */ }
  return text;
};

/**
 * Reads the releases of every cluster, in the clusters' order, each one tagged with its cluster. A cluster that
 * cannot be read does not hide the others: it is returned with the reason.
 *
 * @param clusters the clusters KDPS knows, the default one first
 * @param read     reads one cluster's releases ({@code undefined} names the default cluster)
 */
export async function listReleasesAcrossClusters<T extends ClusterRelease>(
  clusters: ListedCluster[],
  read: (cluster: string | undefined) => Promise<{ items?: T[] }>,
): Promise<{ items: T[]; unlisted: UnlistedCluster[] }> {
  const answers = await Promise.allSettled(clusters.map(c => read(c.default ? undefined : c.id)));
  const items: T[] = [];
  const unlisted: UnlistedCluster[] = [];
  answers.forEach((answer, i) => {
    const cluster = clusters[i];
    if (answer.status === 'fulfilled') {
      for (const release of answer.value?.items || []) {
        items.push({ ...release, kubernetesCluster: release.kubernetesCluster || cluster.id });
      }
    } else {
      unlisted.push({ id: cluster.id, name: cluster.name || cluster.id, error: reasonOf(answer.reason) });
    }
  });
  return { items, unlisted };
}
