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
import { render, screen, waitFor, act } from '@testing-library/react';

jest.mock('../../../api/client');

import {
  listCommands,
  getCommandStatus,
  listChildCommands,
  getCommandLogs,
  refreshDependencies,
  cancelCommand
} from '../../../api/client';

import BackgroundOperationsModal from '../BackgroundOperationsModal';

describe('BackgroundOperationsModal', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (listChildCommands as jest.Mock).mockResolvedValue([]);
    (getCommandLogs as jest.Mock).mockResolvedValue({ content: '', nextOffset: 0, eof: true });
    (refreshDependencies as jest.Mock).mockResolvedValue(undefined);
    (cancelCommand as jest.Mock).mockResolvedValue(undefined);
  });

  it('shows progress for running leaf commands', async () => {
    const runningLeaf = {
      id: 'cmd-1',
      state: 'RUNNING',
      percent: 0,
      step: 0,
      message: 'Leaf',
      type: 'TEST',
      hasChildren: false,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    } as any;

    (listCommands as jest.Mock).mockResolvedValue([runningLeaf]);
    (getCommandStatus as jest.Mock).mockResolvedValue(runningLeaf);

    render(<BackgroundOperationsModal open onClose={() => undefined} />);

    await waitFor(() => expect(listCommands).toHaveBeenCalled());
    await screen.findByText('RUNNING');

    const progressBars = screen.getAllByRole('progressbar');
    const hasActiveProgress = progressBars.some((bar) => {
      const raw = bar.getAttribute('aria-valuenow');
      const value = raw ? Number(raw) : 0;
      return Number.isFinite(value) && value >= 50;
    });

    expect(hasActiveProgress).toBe(true);
  });

  it('keeps polling active commands when watched command fails', async () => {
    const failedCommand = { id: 'cmd-failed', state: 'FAILED', percent: 100, step: 0, message: 'Failed', type: 'TEST', hasChildren: false,
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() } as any;
    const runningCommand = { id: 'cmd-running', state: 'RUNNING', percent: 10, step: 0, message: 'Running', type: 'TEST', hasChildren: false,
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() } as any;
    (listCommands as jest.Mock).mockResolvedValue([failedCommand, runningCommand]);
    (getCommandStatus as jest.Mock).mockImplementation(async (id: string) => (id === runningCommand.id ? runningCommand : failedCommand));
    const onAutoClose = jest.fn();
    render(<BackgroundOperationsModal open onClose={() => undefined} watchCommandId={failedCommand.id} onAutoClose={onAutoClose} />);
    await waitFor(() => expect(listCommands).toHaveBeenCalled());
    // initial prefetch touches every listed row once
    await waitFor(() => expect(getCommandStatus).toHaveBeenCalledWith(runningCommand.id));
    (getCommandStatus as jest.Mock).mockClear();
    // the 2s poller refreshes only the non-terminal command; the failed one is never re-polled
    await waitFor(() => expect(getCommandStatus).toHaveBeenCalledWith(runningCommand.id), { timeout: 5000 });
    expect(getCommandStatus).not.toHaveBeenCalledWith(failedCommand.id);
    // a FAILED watched command never auto-closes the modal
    expect(onAutoClose).not.toHaveBeenCalled();
  }, 10000);

  const gitOpsDeploy = (result?: Record<string, unknown>) => ({
    id: 'cmd-gitops', state: 'SUCCEEDED', percent: 100, step: 0, message: 'Install release trino with GitOps',
    type: 'K8S_MANAGER_RELEASE_DEPLOY', hasChildren: true, result,
    createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
  }) as any;

  it('stays open on a GitOps deploy that waits for a merge and says what it waits for', async () => {
    const waiting = gitOpsDeploy({
      waitingForMerge: true,
      pullRequestUrl: 'https://git.example.org/platform/gitops/-/merge_requests/7',
      message: 'Waiting for the pull request https://git.example.org/platform/gitops/-/merge_requests/7 to be merged '
        + 'into branch main: Flux applies the change once it is merged. Not run until then: Ambari: provision view '
        + 'instance for trino. Redeploy the release once the change is applied to run them.',
    });
    (listCommands as jest.Mock).mockResolvedValue([waiting]);
    (getCommandStatus as jest.Mock).mockResolvedValue(waiting);
    const onAutoClose = jest.fn();

    render(<BackgroundOperationsModal open onClose={() => undefined} watchCommandId={waiting.id} onAutoClose={onAutoClose} />);

    await screen.findByText('Committed to Git, not applied yet');
    expect(screen.getByText(/Not run until then: Ambari: provision view instance for trino/)).toBeTruthy();
    expect(screen.getByText('Open the pull request').closest('a')?.getAttribute('href'))
      .toBe('https://git.example.org/platform/gitops/-/merge_requests/7');
    expect(screen.getByText('WAITING FOR MERGE')).toBeTruthy();
    // past a tick of the poller, which closes the modal on an operation that ended otherwise
    await act(async () => { await new Promise((r) => setTimeout(r, 2500)); });
    expect(onAutoClose).not.toHaveBeenCalled();
  }, 10000);

  it('closes on a deploy that ended applied', async () => {
    const applied = gitOpsDeploy();
    (listCommands as jest.Mock).mockResolvedValue([applied]);
    (getCommandStatus as jest.Mock).mockResolvedValue(applied);
    const onAutoClose = jest.fn();

    render(<BackgroundOperationsModal open onClose={() => undefined} watchCommandId={applied.id} onAutoClose={onAutoClose} />);

    await waitFor(() => expect(onAutoClose).toHaveBeenCalled(), { timeout: 5000 });
    expect(screen.queryByText('Committed to Git, not applied yet')).toBeNull();
  }, 10000);
});
