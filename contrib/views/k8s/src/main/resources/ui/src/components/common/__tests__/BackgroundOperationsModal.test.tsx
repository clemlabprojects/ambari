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
import userEvent from '@testing-library/user-event';

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

  it('stays open on a deploy that says where to read a generated password', async () => {
    const notice = "Superset administrator 'admin': its password is in the Secret bi/bi-superset-admin. Read it with: "
      + "kubectl -n bi get secret bi-superset-admin -o jsonpath='{.data.password}' | base64 -d.";
    const deployed = gitOpsDeploy({ notices: [notice] });
    (listCommands as jest.Mock).mockResolvedValue([deployed]);
    (getCommandStatus as jest.Mock).mockResolvedValue(deployed);
    const onAutoClose = jest.fn();

    render(<BackgroundOperationsModal open onClose={() => undefined} watchCommandId={deployed.id} onAutoClose={onAutoClose} />);

    await screen.findByText('Before you sign in');
    expect(screen.getByText(notice)).toBeTruthy();
    expect(screen.queryByText('Committed to Git, not applied yet')).toBeNull();
    await act(async () => { await new Promise((r) => setTimeout(r, 2500)); });
    expect(onAutoClose).not.toHaveBeenCalled();
  }, 10000);

  it('shows what a running GitOps deploy committed, while it waits for Flux', async () => {
    const committed = {
      ...gitOpsDeploy({
        gitOps: {
          mode: 'FLUX_GITOPS', commit: '3f0c9a52e7d14b6c8a9e', branch: 'main', noChanges: false,
          prUrl: 'https://git.example.org/platform/gitops/pulls/7', prState: 'merged',
        },
      }),
      state: 'RUNNING', percent: 60,
    };
    (listCommands as jest.Mock).mockResolvedValue([committed]);
    (getCommandStatus as jest.Mock).mockResolvedValue(committed);

    render(<BackgroundOperationsModal open onClose={() => undefined} watchCommandId={committed.id} />);

    // A running operation is not opened for the operator: they open it.
    await userEvent.click(await screen.findByText('Install release trino with GitOps'));
    await screen.findByText('Committed to Git');
    expect(screen.getByText('Commit 3f0c9a52e7d1 · Branch main · Pull request (merged)')).toBeTruthy();
    expect(screen.getByText('Open the pull request').closest('a')?.getAttribute('href'))
      .toBe('https://git.example.org/platform/gitops/pulls/7');
    expect(screen.queryByText('Committed to Git, not applied yet')).toBeNull();
  }, 10000);

  const running = (id: string, title: string) => ({ id, state: 'RUNNING', percent: 30, step: 0, message: title,
    type: 'K8S_MANAGER_RELEASE_DEPLOY', hasChildren: true, createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString() }) as any;

  it('opens no operation by itself, the running one it watches included', async () => {
    const deploy = running('cmd-run', 'Install release trino with GitOps');
    (listCommands as jest.Mock).mockResolvedValue([deploy]);
    (getCommandStatus as jest.Mock).mockResolvedValue(deploy);

    render(<BackgroundOperationsModal open onClose={() => undefined} watchCommandId={deploy.id} />);

    await screen.findByText('Install release trino with GitOps');
    await act(async () => { await new Promise((r) => setTimeout(r, 2500)); });
    expect(document.querySelector('.ant-collapse-item-active')).toBeNull();
    expect(listChildCommands).not.toHaveBeenCalled();
    expect(getCommandLogs).not.toHaveBeenCalled();
  }, 10000);

  it('keeps the operation the operator opened open across refreshes, while another one runs', async () => {
    const deploy = running('cmd-run', 'Install release trino with GitOps');
    const older = { ...gitOpsDeploy(), id: 'cmd-old', message: 'Install release superset' };
    (listCommands as jest.Mock).mockResolvedValue([deploy, older]);
    (getCommandStatus as jest.Mock).mockImplementation(async (id: string) => (id === deploy.id ? deploy : older));

    render(<BackgroundOperationsModal open onClose={() => undefined} watchCommandId={deploy.id} />);
    await userEvent.click(await screen.findByText('Install release superset'));
    await waitFor(() => expect(document.querySelector('.ant-collapse-item-active')?.textContent)
      .toContain('Install release superset'));

    await act(async () => { await new Promise((r) => setTimeout(r, 4500)); });

    const active = document.querySelectorAll('.ant-collapse-item-active');
    expect(active.length).toBe(1);
    expect(active[0].textContent).toContain('Install release superset');
    expect(listChildCommands).not.toHaveBeenCalledWith(deploy.id);
  }, 15000);

  it('reads the steps of an ended operation once, not on every refresh', async () => {
    const failed = { ...gitOpsDeploy(), id: 'cmd-failed', state: 'FAILED', message: 'Install release trino with GitOps' };
    (listCommands as jest.Mock).mockResolvedValue([failed]);
    (getCommandStatus as jest.Mock).mockResolvedValue(failed);

    render(<BackgroundOperationsModal open onClose={() => undefined} />);
    await userEvent.click(await screen.findByText('Install release trino with GitOps'));
    await waitFor(() => expect(listChildCommands).toHaveBeenCalledWith(failed.id));

    // Three refreshes later: the open operation ended, so its steps were read when it was opened and once after.
    await act(async () => { await new Promise((r) => setTimeout(r, 6500)); });
    expect((listChildCommands as jest.Mock).mock.calls.filter(([id]) => id === failed.id).length).toBeLessThanOrEqual(2);
  }, 15000);

  it('closes on a deploy that ended applied', async () => {
    const applied = gitOpsDeploy();
    (listCommands as jest.Mock).mockResolvedValue([applied]);
    (getCommandStatus as jest.Mock).mockResolvedValue(applied);
    const onAutoClose = jest.fn();

    render(<BackgroundOperationsModal open onClose={() => undefined} watchCommandId={applied.id} onAutoClose={onAutoClose} />);

    await waitFor(() => expect(onAutoClose).toHaveBeenCalled(), { timeout: 5000 });
    expect(screen.queryByText('Committed to Git, not applied yet')).toBeNull();
  }, 10000);

  describe('Abort', () => {
    const running = {
      id: 'cmd-abort', state: 'RUNNING', percent: 40, step: 0, message: 'Install release trino', type: 'TEST',
      hasChildren: false, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    } as any;

    const clickAbort = async () => {
      (listCommands as jest.Mock).mockResolvedValue([running]);
      (getCommandStatus as jest.Mock).mockResolvedValue(running);
      render(<BackgroundOperationsModal open onClose={() => undefined} />);
      await userEvent.click(await screen.findByRole('button', { name: 'Abort' }));
    };

    it('asks for confirmation and aborts nothing until it is given', async () => {
      await clickAbort();

      expect(await screen.findByText('Abort "Install release trino"?')).toBeTruthy();
      expect(screen.getByText(/The step that is running now will finish, the remaining steps will be skipped, and the operation cannot be resumed/)).toBeTruthy();
      expect(cancelCommand).not.toHaveBeenCalled();

      await userEvent.click(screen.getByRole('button', { name: 'Keep running' }));
      expect(cancelCommand).not.toHaveBeenCalled();
    });

    it('aborts the operation once confirmed', async () => {
      await clickAbort();

      await userEvent.click(await screen.findByRole('button', { name: 'Abort operation' }));

      await waitFor(() => expect(cancelCommand).toHaveBeenCalledWith('cmd-abort'));
    });
  });
});
