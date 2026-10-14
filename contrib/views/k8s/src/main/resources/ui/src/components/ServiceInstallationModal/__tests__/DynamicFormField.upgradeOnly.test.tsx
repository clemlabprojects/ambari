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
import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Form } from 'antd';
import DynamicFormField from '../DynamicFormField';
import { UpgradeModeContext } from '../fieldSync';

jest.mock('../../../api/client', () => ({
  getClusterCapabilities: jest.fn(() => Promise.resolve({ platform: 'kubernetes' })),
}));

/** A select option marked `upgradeOnly` ("Keep current certificate") is offered on Upgrade/Config only. */
const tlsMode: any = {
  name: 'ingress.tlsMode',
  label: 'TLS mode',
  type: 'select',
  options: [
    { label: 'Keep current certificate', value: 'keepCurrent', upgradeOnly: true },
    { label: 'Sign with Company CA (production)', value: 'signedByCompanyCA' },
    { label: 'Sign with Ambari Internal CA (testing)', value: 'signedByAmbariCA' },
  ],
};

const Harness: React.FC<{ upgrade: boolean; mode: string }> = ({ upgrade, mode }) => (
  <UpgradeModeContext.Provider value={upgrade}>
    <Form initialValues={{ ingress: { tlsMode: mode } }}>
      <DynamicFormField field={tlsMode} />
    </Form>
  </UpgradeModeContext.Provider>
);

describe('DynamicFormField upgrade-only options', () => {
  it('Upgrade/Config shows and offers "Keep current certificate"', async () => {
    const user = userEvent.setup();
    render(<Harness upgrade mode="keepCurrent" />);
    expect(screen.getByTitle('Keep current certificate')).toBeInTheDocument();
    await user.click(screen.getByRole('combobox'));
    expect(await screen.findByText('Sign with Company CA (production)')).toBeInTheDocument();
  });

  it('an install does not offer it', async () => {
    const user = userEvent.setup();
    render(<Harness upgrade={false} mode="signedByAmbariCA" />);
    await user.click(screen.getByRole('combobox'));
    expect(await screen.findByText('Sign with Company CA (production)')).toBeInTheDocument();
    expect(screen.queryByText('Keep current certificate')).not.toBeInTheDocument();
  });
});
