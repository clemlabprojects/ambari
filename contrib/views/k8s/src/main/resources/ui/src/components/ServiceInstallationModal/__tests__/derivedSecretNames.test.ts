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
import * as fs from 'fs';
import * as path from 'path';
import { followReleaseName, keytabSecretName, tlsSecretName } from '../derivedSecretNames';

/**
 * The keytab and keystore Secret names the wizard derives from the release name follow the name the operator types,
 * against the REAL service definitions: a release 'kdc-superset' used to keep its keytab in 'superset-keytab', the
 * name of the release the wizard proposed first, which a keytab regeneration then missed.
 */
const def = (svc: string) => JSON.parse(fs.readFileSync(
  path.join(__dirname, `../../../../../KDPS/services/${svc}/service.json`), 'utf8'));

/** The wizard state as it is seeded for the release name it proposes. */
const seeded = (d: any, releaseName: string): any => {
  const values: any = { releaseName, namespace: 'analytics', kerberos: {}, tls: {} };
  for (const k of d.kerberos || []) values.kerberos[k.key] = { secretName: keytabSecretName(k, values) };
  for (const t of d.tls || []) {
    const secretName = tlsSecretName(t, values);
    values.tls[t.key] = { secretName, passwordSecretName: t.defaults?.passwordSecretName || `${secretName}-pass` };
  }
  return values;
};

describe('Secret names derived from the release name', () => {
  it("Superset's keytab Secret follows the typed release name", () => {
    const superset = def('SUPERSET');
    const typed = { ...seeded(superset, 'superset'), releaseName: 'kdc-superset' };

    const next = followReleaseName(superset, typed, 'superset');

    expect(next.kerberos.service.secretName).toBe('kdc-superset-keytab');
  });

  it("Trino's keytab and keystore Secrets follow it too", () => {
    const trino = def('TRINO');
    const typed = { ...seeded(trino, 'trino'), releaseName: 'kdc-trino' };

    const next = followReleaseName(trino, typed, 'trino');

    for (const entry of Object.values<any>(next.kerberos)) expect(entry.secretName).toBe('kdc-trino-keytab');
    for (const t of trino.tls || []) {
      expect(next.tls[t.key].secretName).toBe(tlsSecretName(t, { releaseName: 'kdc-trino' }));
      expect(next.tls[t.key].secretName).toContain('kdc-trino');
    }
  });

  it('a name the operator typed is kept', () => {
    const superset = def('SUPERSET');
    const typed = { ...seeded(superset, 'superset'), releaseName: 'kdc-superset' };
    typed.kerberos.service.secretName = 'my-keytab';

    expect(followReleaseName(superset, typed, 'superset').kerberos.service.secretName).toBe('my-keytab');
  });
});
