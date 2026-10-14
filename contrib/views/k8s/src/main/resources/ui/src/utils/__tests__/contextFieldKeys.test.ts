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
import { storageKey, storedValue } from '../contextFieldKeys';

/**
 * The Platform Contexts form saves every capability field into one flat config map. The Kerberos, single sign-on and
 * Polaris realms are all named "realm", so each is stored under its own key; otherwise the last one entered replaces
 * the other two, and editing the context shows the same value in all three.
 */
const CAPABILITIES = path.join(__dirname, '../../../../KDPS/contexts/capabilities');
const fragments = fs.readdirSync(CAPABILITIES).filter((f) => f.endsWith('.json'))
  .map((f) => JSON.parse(fs.readFileSync(path.join(CAPABILITIES, f), 'utf8')));
const field = (capability: string, name: string) =>
  fragments.find((c) => c.capability === capability).fields.find((f: any) => f.name === name);

describe('context field storage keys', () => {
  it('gives every bundled field its own storage key', () => {
    const keys = fragments.flatMap((c) => c.fields.map((f: any) => storageKey(f)));
    expect(keys.filter((k, i) => keys.indexOf(k) !== i)).toEqual([]);
  });

  it('stores the three realms apart, the Kerberos one under its historical key', () => {
    expect(storageKey(field('kerberos', 'realm'))).toBe('realm');
    expect(storageKey(field('oidc', 'realm'))).toBe('oidcRealm');
    expect(storageKey(field('polaris', 'realm'))).toBe('polarisRealm');
  });

  it('fills each realm input from its own stored value', () => {
    const config = { realm: 'BANK.LOCAL', oidcRealm: 'kdps', polarisRealm: 'POLARIS' };
    expect(storedValue(config, 'EXTERNAL', field('kerberos', 'realm'))).toBe('BANK.LOCAL');
    expect(storedValue(config, 'EXTERNAL', field('oidc', 'realm'))).toBe('kdps');
    expect(storedValue(config, 'CDP', field('polaris', 'realm'))).toBe('POLARIS');
  });

  it('reads the single realm of an older REMOTE context as its Polaris realm', () => {
    expect(storedValue({ realm: 'POLARIS' }, 'REMOTE', field('polaris', 'realm'))).toBe('POLARIS');
    expect(storedValue({ realm: 'POLARIS', polarisRealm: 'CATALOG' }, 'REMOTE', field('polaris', 'realm')))
      .toBe('CATALOG');
  });

  it('reads the single realm of an older EXTERNAL context as its Kerberos realm only', () => {
    const config = { realm: 'BANK.LOCAL' };
    expect(storedValue(config, 'EXTERNAL', field('kerberos', 'realm'))).toBe('BANK.LOCAL');
    expect(storedValue(config, 'EXTERNAL', field('oidc', 'realm'))).toBeUndefined();
    expect(storedValue(config, 'EXTERNAL', field('polaris', 'realm'))).toBeUndefined();
  });
});
