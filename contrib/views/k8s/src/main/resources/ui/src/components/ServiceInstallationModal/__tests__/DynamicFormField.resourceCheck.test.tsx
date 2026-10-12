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
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Form } from 'antd';
import DynamicFormField from '../DynamicFormField';

jest.mock('../../../api/client', () => ({
  getClusterCapabilities: jest.fn(() => Promise.resolve({ platform: 'kubernetes' })),
}));

/**
 * A field with a `resourceCheck` shows the check's error inline, keeps its help visible, and is re-checked when the
 * limit it depends on changes.
 */
const limitField: any = { name: 'coordinator.resources.limits.memory', label: 'Memory limit', type: 'string' };
const heapField: any = {
  name: 'coordinator.jvm.maxHeapSize',
  label: 'Java heap size (optional)',
  type: 'string',
  placeholder: '75% of the memory limit',
  help: 'Keep it at 70-80% of the memory limit.',
  resourceCheck: { kind: 'javaHeap', limitField: 'coordinator.resources.limits.memory' },
};

const Harness: React.FC = () => (
  <Form initialValues={{ coordinator: { resources: { limits: { memory: '8Gi' } }, jvm: { maxHeapSize: '' } } }}>
    <DynamicFormField field={limitField} />
    <DynamicFormField field={heapField} />
  </Form>
);

describe('DynamicFormField resourceCheck', () => {
  it('flags a heap too close to the memory limit and clears it when the limit grows', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const heap = screen.getByLabelText('Java heap size (optional)');
    expect(heap).toHaveAttribute('placeholder', '75% of the memory limit');

    await user.type(heap, '8G');
    expect(await screen.findByText(/A 8G heap leaves too little of the 8Gi memory limit/)).toBeInTheDocument();
    expect(screen.getByText('Keep it at 70-80% of the memory limit.')).toBeInTheDocument();

    const limit = screen.getByLabelText('Memory limit');
    await user.clear(limit);
    await user.type(limit, '16Gi');
    await waitFor(() => expect(screen.queryByText(/A 8G heap leaves too little/)).not.toBeInTheDocument());
  });

  it('accepts a heap at 75% of the limit, and a blank one', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const heap = screen.getByLabelText('Java heap size (optional)');
    await user.type(heap, '7Gi');
    expect(await screen.findByText(/"7Gi" is not a Java heap size/)).toBeInTheDocument();

    await user.clear(heap);
    await user.type(heap, '6G');
    await waitFor(() => expect(screen.queryByText(/is not a Java heap size/)).not.toBeInTheDocument());
    expect(screen.queryByText(/heap leaves too little/)).not.toBeInTheDocument();

    await user.clear(heap);
    await user.type(heap, '7G');
    expect(await screen.findByText(/A 7G heap leaves too little/)).toBeInTheDocument();
    await user.clear(heap);
    await waitFor(() => expect(screen.queryByText(/heap leaves too little/)).not.toBeInTheDocument());
  });
});
