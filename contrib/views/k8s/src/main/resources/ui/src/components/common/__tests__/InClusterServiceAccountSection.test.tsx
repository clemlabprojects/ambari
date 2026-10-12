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
import InClusterServiceAccountSection from '../InClusterServiceAccountSection';
import { getInClusterServiceAccount, connectWithInClusterServiceAccount } from '../../../api/client';

jest.mock('../../../api/client');

const inPod = {
  available: true, active: false, authMode: 'kubeconfig',
  username: 'system:serviceaccount:kdps:ambari', serviceAccount: 'ambari', namespace: 'kdps',
  apiServer: 'https://172.30.0.1:443',
};

/** The Cluster connection section for the Ambari pod's own ServiceAccount. */
describe('InClusterServiceAccountSection', () => {
  beforeEach(() => jest.clearAllMocks());

  it('outside a pod, says why and offers no button', async () => {
    (getInClusterServiceAccount as jest.Mock).mockResolvedValue({
      available: false, active: false, authMode: 'kubeconfig',
      reason: 'The Ambari pod has no ServiceAccount token at /var/run/secrets/kubernetes.io/serviceaccount/token. '
        + 'With the ambari chart, set inClusterServiceAccount.enabled=true so that Kubernetes mounts it.',
    });
    render(<InClusterServiceAccountSection />);

    expect(await screen.findByText(/inClusterServiceAccount\.enabled=true/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Connect with this ServiceAccount/ })).toBeNull();
  });

  it('shows who KDPS connects as, connects, and reports it in use', async () => {
    (getInClusterServiceAccount as jest.Mock)
      .mockResolvedValueOnce(inPod)
      .mockResolvedValue({ ...inPod, active: true, authMode: 'in-cluster' });
    const onConnected = jest.fn();
    render(<InClusterServiceAccountSection onConnected={onConnected} />);

    expect(await screen.findByText('system:serviceaccount:kdps:ambari')).toBeInTheDocument();
    expect(screen.getByText('https://172.30.0.1:443')).toBeInTheDocument();
    expect(screen.queryByText('In use')).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: /Connect with this ServiceAccount/ }));

    await waitFor(() => expect(connectWithInClusterServiceAccount).toHaveBeenCalledTimes(1));
    expect(onConnected).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('In use')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Reconnect/ })).toBeInTheDocument();
  });

  it("shows the server's reason when the connection fails, and does not report it connected", async () => {
    (getInClusterServiceAccount as jest.Mock).mockResolvedValue(inPod);
    (connectWithInClusterServiceAccount as jest.Mock).mockRejectedValueOnce(new Error(
      'HTTP 503 – {"error":"The connection with the ServiceAccount system:serviceaccount:kdps:ambari was saved, '
        + 'but KDPS could not connect with it: Cluster API is unreachable."}'));
    const onConnected = jest.fn();
    render(<InClusterServiceAccountSection onConnected={onConnected} />);

    await userEvent.click(await screen.findByRole('button', { name: /Connect with this ServiceAccount/ }));

    expect(await screen.findByText(/but KDPS could not connect with it: Cluster API is unreachable\./)).toBeInTheDocument();
    expect(onConnected).not.toHaveBeenCalled();
  });
});
