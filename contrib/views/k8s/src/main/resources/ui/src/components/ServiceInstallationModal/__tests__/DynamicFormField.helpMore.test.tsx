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

jest.mock('../../../api/client', () => ({
  getClusterCapabilities: jest.fn(() => Promise.resolve({ platform: 'kubernetes' })),
}));

/** A field shows one line of help under it; its details (`helpMore`) wait behind an ⓘ next to its label. */
const render1 = (field: any) => render(<Form><DynamicFormField field={field} /></Form>);

describe('DynamicFormField help details', () => {
  it('a text field shows its line and keeps the details behind the ⓘ', async () => {
    const user = userEvent.setup();
    render1({ name: 'ingress.host', label: 'Hostname (DNS)', type: 'string',
      help: 'Public DNS name of the service.', helpMore: 'On OpenShift it must sit under the ingress domain.' });

    expect(screen.getByText('Public DNS name of the service.')).toBeInTheDocument();
    expect(screen.queryByText('On OpenShift it must sit under the ingress domain.')).not.toBeInTheDocument();
    await user.hover(screen.getByRole('img', { name: /question-circle|info-circle/ }));
    expect(await screen.findByText('On OpenShift it must sit under the ingress domain.')).toBeInTheDocument();
  });

  it('a checkbox gets the ⓘ in its text, and hovering it does not tick the box', async () => {
    const user = userEvent.setup();
    render1({ name: 'tls.https.enabled', label: 'Enable internal HTTPS', type: 'boolean',
      help: 'Trino serves HTTPS itself.', helpMore: 'Turned off, traffic crosses the pod network in clear text.' });

    const icon = screen.getByRole('img', { name: 'More about Enable internal HTTPS' });
    await user.hover(icon);
    expect(await screen.findByText('Turned off, traffic crosses the pod network in clear text.')).toBeInTheDocument();
    await user.click(icon);
    expect(screen.getByRole('checkbox')).not.toBeChecked();
  });

  it('a field without details has no ⓘ', () => {
    render1({ name: 'ingress.path', label: 'HTTP path', type: 'string', help: "Base path, usually '/'." });

    expect(screen.queryByRole('img', { name: /info-circle|More about/ })).not.toBeInTheDocument();
  });
});
