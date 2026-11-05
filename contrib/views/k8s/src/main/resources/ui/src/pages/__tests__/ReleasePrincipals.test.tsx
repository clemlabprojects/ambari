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
import { render, screen } from '@testing-library/react';
import ReleasePrincipals, { accountOrigin } from '../ReleasePrincipals';

describe('ReleasePrincipals', () => {
  it('lists the principals KDPS issued, their KDC, account owner and Secret', () => {
    render(<ReleasePrincipals principals={[
      { principal: 'superset-dashboard-bi@AD.EXAMPLE.COM', secret: 'superset-keytab', kdcContextId: 'cdp-prod', created: true, kvno: 3, issuedAt: '2026-11-05T10:00:00Z', issuedBy: 'admin' },
      { principal: 'hive-bi@EXAMPLE.COM', secret: 'hive-keytab' },
    ]} />);
    expect(screen.getByText('Kerberos principals')).toBeTruthy();
    expect(screen.getByText('superset-dashboard-bi@AD.EXAMPLE.COM')).toBeTruthy();
    expect(screen.getByText('context cdp-prod')).toBeTruthy();
    expect(screen.getByText('Created by KDPS')).toBeTruthy();
    expect(screen.getByText('Ambari cluster')).toBeTruthy();
    expect(screen.getByText('Not recorded')).toBeTruthy();
    expect(screen.getByText('superset-keytab')).toBeTruthy();
  });

  it('shows nothing for a release without principals', () => {
    const { container } = render(<ReleasePrincipals principals={[]} />);
    expect(container.innerHTML).toBe('');
  });

  it('never invites deleting an account KDPS did not create', () => {
    expect(accountOrigin({ principal: 'hive@R', created: false }).tip).toContain('never deletes it');
  });
});
