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
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RangerPolicyModal, { toRequest } from '../RangerPolicyModal';
import { createReleaseRangerPolicy } from '../../../api/client';

jest.mock('../../../api/client');

const release: any = { name: 'trino-sec', namespace: 'trino-sec', serviceKey: 'TRINO' };

describe('RangerPolicyModal', () => {
  it('maps a resource mask into the backend request shape', () => {
    const body = toRequest({ target: 'resource', policyType: 1, catalog: 'tpch', schema: 'sf1', table: 'customer', column: 'name', users: 'admin', maskType: 'MASK_HASH' });
    expect(body).toEqual({ target: 'resource', policyType: 1, users: 'admin', groups: undefined, policyName: undefined, description: undefined,
      resources: { catalog: 'tpch', schema: 'sf1', table: 'customer', column: 'name' }, maskType: 'MASK_HASH' });
  });

  it('maps a tag row filter for a group, defaulting blank resources to *', () => {
    const body = toRequest({ target: 'tag', policyType: 2, tag: 'Sensitive', groups: 'public', rowFilterExpr: "region = 'EU'" });
    expect(body.target).toBe('tag');
    expect(body.tag).toBe('Sensitive');
    expect(body.rowFilterExpr).toBe("region = 'EU'");
    expect(body.resources).toBeUndefined();
    const allow = toRequest({ target: 'resource', policyType: 0, users: 'bob', accessTypes: ['select', 'show'] });
    expect(allow.resources).toEqual({ catalog: '*', schema: '*', table: '*', column: '*' });
    expect(allow.accessTypes).toBe('select,show');
  });

  it('submits through the client and shows the Ranger answer', async () => {
    render(<RangerPolicyModal release={release} onClose={() => undefined} />);
    await userEvent.type(screen.getByPlaceholderText('alice'), 'admin');
    await userEvent.click(screen.getByRole('button', { name: 'Create policy' }));
    await waitFor(() => expect(createReleaseRangerPolicy).toHaveBeenCalledWith('trino-sec', 'trino-sec',
      expect.objectContaining({ target: 'resource', policyType: 0, users: 'admin', accessTypes: 'select' })));
    expect(await screen.findByText(/Policy 'p' on 'trino-ns'/)).toBeInTheDocument();
  });
});
