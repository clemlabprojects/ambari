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

import { fieldRows } from '../FieldsHelp';
import { blankProfile } from '../profileDraft';

describe('the fields a profile can use', () => {
  it('lists the fields KDPS fills in, with the example deploy, then the profile variables', () => {
    const p = { ...blankProfile('environments'), variables: { team: 'data', ' ': 'ignored' } };
    const rows = fieldRows(p, { release: 'trino', namespace: 'analytics', chart: 'trino', version: '1.43.20', cluster: 'k8s21' });
    const byField = Object.fromEntries(rows.map(r => [r.field, r.example]));
    expect(byField.namespace).toBe('analytics');
    expect(byField.cluster).toBe('k8s21');
    expect(byField['chart.version']).toBe('1.43.20');
    expect(byField.chartPath).toBe('chart');
    expect(byField['secretValues.secretName']).toBe('trino-kdps-values');
    expect(byField['vars.team']).toBe('data');
    expect(rows.map(r => r.field)).toEqual(expect.arrayContaining(['values', 'instances', 'images', 'deployedBy', 'timestamp']));
    expect(rows.some(r => r.field === 'vars.')).toBe(false);
  });
});
