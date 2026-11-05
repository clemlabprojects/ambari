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
import { applyFormDefaults } from '../chartValues';
import { requiredFieldErrors } from '../requiredUnless';

/**
 * Required fields left empty stop the wizard, against the REAL SUPERSET/service.json: without its SECRET_KEY, Superset
 * refuses to start ("Refusing to start due to insecure SECRET_KEY") once Helm has installed it.
 */
const def = JSON.parse(fs.readFileSync(
  path.join(__dirname, '../../../../../KDPS/services/SUPERSET/service.json'), 'utf8'));

const install = (): any => {
  const values: any = { releaseName: 'superset', namespace: 'analytics' };
  applyFormDefaults(def.form, values);
  return values;
};

describe('required form fields', () => {
  it('an empty SECRET_KEY stops the wizard', () => {
    const errors = requiredFieldErrors(def.form, install(), 'openshift');

    expect(errors.some(e => e.includes('SECRET_KEY'))).toBe(true);
  });

  it('a filled form passes', () => {
    const values = install();
    values.superset = { ...(values.superset || {}), secretKey: 'Key-From-The-Form-1' };

    expect(requiredFieldErrors(def.form, values, 'openshift')).toEqual([]);
  });

  it('a field hidden by its condition is not asked for', () => {
    const form = [
      { name: 'ingress.enabled', label: 'Enable Ingress', type: 'boolean', required: true },
      { name: 'ingress.host', label: 'Hostname', type: 'string', required: true,
        condition: { field: 'ingress.enabled', value: true } },
    ];

    expect(requiredFieldErrors(form as any, { ingress: { enabled: false } })).toEqual([]);
    expect(requiredFieldErrors(form as any, { ingress: { enabled: true } })).toEqual(['Hostname: this field is required.']);
  });
});
