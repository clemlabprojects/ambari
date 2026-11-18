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
import { render, fireEvent } from '@testing-library/react';
import { Form } from 'antd';
import type { FormInstance } from 'antd';
import DynamicFormField from '../DynamicFormField';

jest.mock('../../../api/client', () => ({
  getClusterCapabilities: jest.fn(() => Promise.resolve({ platform: 'kubernetes' })),
}));

/** The company certificate of "Upload cert + key", as the service definitions declare it. */
const certField: any = { name: 'ingress.tlsUpload.cert', label: 'TLS certificate (PEM, leaf + chain)', type: 'content' };

const PEM = '-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIU\nAbCd\n-----END CERTIFICATE-----\n'
  + '-----BEGIN CERTIFICATE-----\nMIIBqjCCAU+gAwIBAgIU\n-----END CERTIFICATE-----\n';

describe('DynamicFormField pasted files', () => {
  it('keeps the line breaks of a pasted PEM certificate', () => {
    let form: FormInstance | undefined;
    const Harness = () => {
      const [f] = Form.useForm();
      form = f;
      return <Form form={f}><DynamicFormField field={certField} /></Form>;
    };
    const { container } = render(<Harness />);

    const box = container.querySelector('textarea');
    expect(box).not.toBeNull();
    fireEvent.change(box!, { target: { value: PEM } });

    expect(form!.getFieldValue(['ingress', 'tlsUpload', 'cert'])).toBe(PEM);
  });
});
