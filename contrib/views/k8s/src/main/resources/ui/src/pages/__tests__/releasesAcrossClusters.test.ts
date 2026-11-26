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
import { listReleasesAcrossClusters, releaseRowKey } from '../releasesAcrossClusters';

const clusters = [
  { id: 'default', name: 'Default cluster', default: true },
  { id: 'crc2', name: 'CRC second' },
  { id: 'k8s21', name: 'dev21 Kubernetes' },
];

describe('releases across clusters', () => {
  it('lists the releases of every cluster, each with its cluster, in the clusters\' order', async () => {
    const asked: (string | undefined)[] = [];
    const { items, unlisted } = await listReleasesAcrossClusters(clusters, async (cluster) => {
      asked.push(cluster);
      return { items: [{ namespace: 'trino', name: `t-${cluster ?? 'default'}`, kubernetesCluster: undefined as string | undefined }] };
    });
    expect(asked).toEqual([undefined, 'crc2', 'k8s21']);
    expect(items.map(r => [r.name, r.kubernetesCluster])).toEqual([
      ['t-default', 'default'], ['t-crc2', 'crc2'], ['t-k8s21', 'k8s21'],
    ]);
    expect(unlisted).toEqual([]);
  });

  it('keeps the cluster the server names, and lists the others when one cannot be read', async () => {
    const { items, unlisted } = await listReleasesAcrossClusters(clusters, async (cluster) => {
      if (cluster === 'k8s21') throw new Error('{ "status": 500, "message": "Cluster API is unreachable" }');
      return { items: [{ namespace: 'bi', name: 'superset', kubernetesCluster: cluster ?? 'default' }] };
    });
    expect(items).toHaveLength(2);
    expect(unlisted).toEqual([{ id: 'k8s21', name: 'dev21 Kubernetes', error: 'Cluster API is unreachable' }]);
  });

  it('keys a row by its cluster, so one release name in two clusters makes two rows', () => {
    expect(releaseRowKey({ namespace: 'trino', name: 't1', kubernetesCluster: 'default' })).toBe('trino/t1');
    expect(releaseRowKey({ namespace: 'trino', name: 't1' })).toBe('trino/t1');
    expect(releaseRowKey({ namespace: 'trino', name: 't1', kubernetesCluster: 'crc2' })).toBe('crc2:trino/t1');
  });
});
