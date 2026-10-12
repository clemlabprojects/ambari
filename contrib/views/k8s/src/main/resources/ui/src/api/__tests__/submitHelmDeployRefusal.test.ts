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
import { submitHelmDeploy } from '../client';
import { unconfirmedTlsReplacement } from '../../components/ServiceInstallationModal/ingressTls';

/**
 * A deploy whose ingress certificate would replace an existing Secret KDPS did not issue is refused (409) with a code
 * and the Secret: the wizard recognises it from the error submitHelmDeploy throws, asks the operator, and deploys
 * again with replaceExistingTls.
 */
describe('submitHelmDeploy refusals', () => {
  const originalFetch = (globalThis as any).fetch;

  afterEach(() => {
    (globalThis as any).fetch = originalFetch;
  });

  const answer = (body: string, status: number) => {
    const response: any = {
      ok: status < 400,
      status,
      json: async () => JSON.parse(body),
      text: async () => body,
    };
    response.clone = () => response;
    (globalThis as any).fetch = jest.fn(() => Promise.resolve(response));
  };

  const deploy = () => submitHelmDeploy({ chart: 'clemlab/trino', releaseName: 'trino', namespace: 'analytics', values: {} })
    .then(() => null, (e) => e);

  it('carry the code and the Secret of a refusal the operator can lift by confirming', async () => {
    const reason = 'The ingress certificate Secret analytics/trino-ingress-tls already exists and was not issued by KDPS';
    answer(JSON.stringify({ error: reason, code: 'INGRESS_TLS_REPLACEMENT_NOT_CONFIRMED', namespace: 'analytics',
      secretName: 'trino-ingress-tls' }), 409);

    const err = await deploy();

    expect(err.message).toBe(`Submit failed: 409: ${reason}`);
    expect(err.status).toBe(409);
    expect(unconfirmedTlsReplacement(err)).toEqual({ namespace: 'analytics', secretName: 'trino-ingress-tls' });
  });

  it('any other refusal stays a plain error', async () => {
    answer(JSON.stringify({ error: 'The ingress certificate Secret analytics/trino-ingress-tls is managed by cert-manager' }), 400);

    const err = await deploy();

    expect(err.message).toContain('Submit failed: 400: ');
    expect(err.code).toBeUndefined();
    expect(unconfirmedTlsReplacement(err)).toBeNull();
  });
});
