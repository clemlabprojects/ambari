/*
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
import { ProtectedClusterError, rollbackReleaseToRevision, submitHelmDeploy, uninstallHelm } from '../client';

/**
 * An uninstall, a rollback or an upgrade on a protected cluster is refused (409) until the operator types the
 * cluster's name: every way the pages send one throws the same error, naming the cluster to ask for.
 */
describe('refusals on a protected cluster', () => {
  const originalFetch = (globalThis as any).fetch;
  const reason = "The Kubernetes cluster 'Production' is protected: type its name to confirm the uninstall of analytics/trino. Nothing was changed.";
  const refusal = JSON.stringify({ error: reason, code: 'PROTECTED_CLUSTER_NOT_CONFIRMED', cluster: { id: 'prod', name: 'Production' } });

  afterEach(() => {
    (globalThis as any).fetch = originalFetch;
  });

  const answer = (body: string, status: number) => {
    const response: any = { ok: status < 400, status, statusText: '', json: async () => JSON.parse(body), text: async () => body };
    response.clone = () => response;
    const fetch = jest.fn(() => Promise.resolve(response));
    (globalThis as any).fetch = fetch;
    return fetch;
  };

  it('name the cluster, whichever call was refused', async () => {
    for (const call of [
      () => uninstallHelm('trino', 'analytics', new URLSearchParams(), 'prod'),
      () => rollbackReleaseToRevision('analytics', 'trino', 2, 'prod'),
      () => submitHelmDeploy({ chart: 'clemlab/trino', releaseName: 'trino', namespace: 'analytics', values: {} }),
    ]) {
      answer(refusal, 409);
      const err = await call().then(() => null, (e) => e);
      expect(err).toBeInstanceOf(ProtectedClusterError);
      expect(err.cluster).toEqual({ id: 'prod', name: 'Production' });
      expect(err.message).toBe(reason);
    }
  });

  it('send the name the operator typed', async () => {
    const fetch = answer('{"ok":true,"revision":2}', 200);

    await rollbackReleaseToRevision('analytics', 'trino', 2, 'prod', 'Production');

    expect(String((fetch.mock.calls[0] as any[])[0])).toContain('?revision=2&cluster=prod&confirmCluster=Production');
  });
});
