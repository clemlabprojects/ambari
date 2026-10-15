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

import React from 'react';
import * as fs from 'fs';
import * as path from 'path';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { applyFormDefaults, buildChartValues } from '../../ServiceInstallationModal/chartValues';
import { applySize, currentSize, CUSTOM_SIZE, formOnly, formWithout, sizingOf } from '../sizing';
import SizingCard from '../SizingCard';

jest.mock('../../../api/client', () => ({
  getClusterCapabilities: jest.fn(() => Promise.resolve({ platform: 'kubernetes' })),
}));
jest.mock('../../ServiceInstallationModal', () => ({
  resolveAuthCascade: jest.fn(),
  applyAuthCascadeToFields: jest.fn((fields: any[]) => fields),
}));

/**
 * The sizes of the wizard's chart step only fill fields the service definition already has: M is their defaults, so
 * a deploy that keeps it sends what it sent before sizes existed, and a size the operator picks reaches the chart.
 */
const load = (svc: string) => JSON.parse(fs.readFileSync(
  path.join(__dirname, `../../../../../KDPS/services/${svc}/service.json`), 'utf8'));

const names = (form: any[]): string[] =>
  form.flatMap((f: any) => (f.type === 'group' ? names(f.fields || []) : [f.name]));

describe.each(['TRINO', 'SUPERSET', 'OPENMETADATA'])('%s sizes', (svc) => {
  const def = load(svc);
  const sizing = sizingOf(def)!;
  const defaults = () => {
    const form: any = { releaseName: 'rel', namespace: 'proj' };
    applyFormDefaults(def.form, form);
    return form;
  };
  const chartValues = (form: any) => buildChartValues({
    form: def.form, bindings: def.bindings, variables: def.variables, formValues: form,
    platform: 'openshift', chartVersion: def.version, retiredValues: def.retiredValues,
  });

  it('offers S, M, L and XL, and the defaults are M', () => {
    expect(sizing.presets.map((p) => p.key)).toEqual(['S', 'M', 'L', 'XL']);
    expect(currentSize(sizing, defaults())).toBe('M');
  });

  it('M sends exactly what the defaults send', () => {
    const m = sizing.presets.find((p) => p.key === 'M')!;
    expect(chartValues(applySize(defaults(), m))).toEqual(chartValues(defaults()));
  });

  it('a size reaches the chart values and is recognised again, as on an upgrade', () => {
    const l = sizing.presets.find((p) => p.key === 'L')!;
    const form = applySize(defaults(), l);
    expect(currentSize(sizing, form)).toBe('L');
    const values = chartValues(form);
    Object.entries(l.values).forEach(([p, v]) => {
      const leaf = p.split('.').reduce((o: any, k) => (o == null ? undefined : o[k]), values);
      expect(String(leaf)).toBe(String(v));
    });
  });

  it('moves its fields from the form to the sizing dialog, and only those', () => {
    const main = names(formWithout(def.form, sizing.fields));
    const dialog = names(formOnly(def.form, sizing.fields));
    expect(main.filter((n) => sizing.fields.includes(n))).toEqual([]);
    expect([...dialog].sort()).toEqual([...sizing.fields].sort());
    expect(main.length + dialog.length).toBe(names(def.form).length);
  });
});

describe('currentSize', () => {
  const sizing = { fields: ['a.cpu'], presets: [{ key: 'S', label: 'Small', summary: '', values: { 'a.cpu': 1 } }] };
  it('is Custom when the values match no size, and accepts a number typed as text', () => {
    expect(currentSize(sizing, { a: { cpu: '1' } })).toBe('S');
    expect(currentSize(sizing, { a: { cpu: '3' } })).toBe(CUSTOM_SIZE);
  });
});

describe('SizingCard', () => {
  const def = load('SUPERSET');
  it('shows the size of the values and applies the one picked', async () => {
    const form: any = { releaseName: 'rel', namespace: 'proj' };
    applyFormDefaults(def.form, form);
    const onChange = jest.fn();
    render(<SizingCard definition={def} sizing={sizingOf(def)!} data={form} onChange={onChange} />);

    expect(screen.getByText(/Medium: 4 worker processes/)).toBeInTheDocument();
    await userEvent.click(screen.getByText('L'));
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][0].supersetWorker.concurrency).toBe(8);
    expect(onChange.mock.calls[0][0].supersetWorker.resources.limits.memory).toBe('4Gi');
  });
});
