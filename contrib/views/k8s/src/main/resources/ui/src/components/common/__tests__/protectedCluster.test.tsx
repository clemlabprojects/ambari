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
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ProtectedClusterError } from '../../../api/client';
import { CANCELLED, confirmingProtectedCluster } from '../protectedCluster';

const refusal = (mistyped = false) => new ProtectedClusterError(
  `${mistyped ? "The name typed is not the cluster's. " : ''}The Kubernetes cluster 'Production' is protected: type its`
  + ' name to confirm the uninstall of analytics/trino. Nothing was changed.', { id: 'prod', name: 'Production' });

/** An action a protected cluster refuses asks the operator the cluster's name, and runs again with it. */
describe('confirmingProtectedCluster', () => {
  it('runs the action at once on a cluster that is not protected', async () => {
    const run = jest.fn(async () => 'done');

    await expect(confirmingProtectedCluster(run)).resolves.toBe('done');
    expect(run).toHaveBeenCalledWith(undefined);
  });

  it('asks for the name until the cluster takes it', async () => {
    const run = jest.fn()
      .mockRejectedValueOnce(refusal())
      .mockRejectedValueOnce(refusal(true))
      .mockResolvedValueOnce('done');
    const result = confirmingProtectedCluster(run);

    await userEvent.type(await screen.findByLabelText('Cluster name'), 'production');
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText(/^The name typed is not the cluster's\./)).toBeTruthy();
    const inputs = await screen.findAllByLabelText('Cluster name');
    await userEvent.type(inputs[inputs.length - 1], 'Production');
    const confirms = screen.getAllByRole('button', { name: 'Confirm' });
    await userEvent.click(confirms[confirms.length - 1]);

    await expect(result).resolves.toBe('done');
    expect(run.mock.calls).toEqual([[undefined], ['production'], ['Production']]);
  });

  it('runs nothing more when the operator cancels', async () => {
    const run = jest.fn().mockRejectedValueOnce(refusal());
    const result = confirmingProtectedCluster(run);

    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));

    await expect(result).resolves.toBe(CANCELLED);
    await waitFor(() => expect(run).toHaveBeenCalledTimes(1));
  });

  it('lets any other refusal through', async () => {
    await expect(confirmingProtectedCluster(async () => { throw new Error('HTTP 500'); })).rejects.toThrow('HTTP 500');
  });
});
