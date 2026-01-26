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
    jest.useFakeTimers();

    const failedCommand = {
      id: 'cmd-failed',
      state: 'FAILED',
      percent: 100,
      step: 0,
      message: 'Failed',
      type: 'TEST',
      hasChildren: false,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    } as any;

    const runningCommand = {
      id: 'cmd-running',
      state: 'RUNNING',
      percent: 10,
      step: 0,
      message: 'Running',
      type: 'TEST',
      hasChildren: false,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    } as any;

    (listCommands as jest.Mock).mockResolvedValue([failedCommand, runningCommand]);
    (getCommandStatus as jest.Mock).mockImplementation(async (id: string) => {
      return id === runningCommand.id ? runningCommand : failedCommand;
    });

    const onAutoClose = jest.fn();
    render(
      <BackgroundOperationsModal
        open
        onClose={() => undefined}
        watchCommandId={failedCommand.id}
        onAutoClose={onAutoClose}
      />
    );

    await waitFor(() => expect(listCommands).toHaveBeenCalled());
    await waitFor(() => expect(getCommandStatus).toHaveBeenCalled());

    (getCommandStatus as jest.Mock).mockClear();

    await act(async () => {
      jest.advanceTimersByTime(2000);
    });

    await waitFor(() => expect(getCommandStatus).toHaveBeenCalled());

    expect(getCommandStatus).toHaveBeenCalledWith(runningCommand.id);
    expect(getCommandStatus).not.toHaveBeenCalledWith(failedCommand.id);
    expect(onAutoClose).not.toHaveBeenCalled();

    jest.useRealTimers();
  });
});
