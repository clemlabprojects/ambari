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

import React, { useEffect, useState } from 'react';
import { Modal, Alert, Collapse, Progress, Typography, Space, Button, Spin, Tree, Tag, message } from 'antd';
import { LoadingOutlined, CheckCircleTwoTone, CloseCircleTwoTone, ClockCircleOutlined } from '@ant-design/icons';
import { cancelCommand, resumeCommand, getCommandStatus, listCommands, listChildCommands, refreshDependencies, getCommandLogs, type CommandStatus } from '../../api/client';
import { describeGitOps, gitOpsResult, operationNotices, waitingForMerge } from './operationOutcome';

type BackgroundOperationsModalProps = {
  open: boolean;
  onClose: () => void;
  watchCommandId?: string;
  onAutoClose?: () => void;
};

/**
 * A step in an operation's plan, stored as PLAIN DATA (not pre-rendered JSX) so it can be merged in
 * place across refreshes. `key` is the command id and never changes for a given step.
 */
type StepNode = {
  key: string;
  message: string;
  state: string;
  percent: number;
  createdAt?: string;
  children: StepNode[];
};

/**
 * Merge freshly-fetched steps into the ones already shown WITHOUT changing the displayed list:
 * existing steps keep their position and only their state/percent update; genuinely new steps
 * (plan growth) are appended; steps are never reordered or removed. This makes the planned step
 * list immutable once shown, as the operator expects — only statuses move.
 */
const mergeSteps = (existing: StepNode[] | undefined, fresh: StepNode[]): StepNode[] => {
  if (!existing || existing.length === 0) return fresh;
  const freshByKey = new Map(fresh.map((n) => [n.key, n]));
  const merged: StepNode[] = existing.map((old) => {
    const f = freshByKey.get(old.key);
    if (!f) return old; // keep steps that momentarily disappear from the fetch
    return { ...old, message: f.message, state: f.state, percent: f.percent, children: mergeSteps(old.children, f.children) };
  });
  const existingKeys = new Set(existing.map((n) => n.key));
  fresh.forEach((f) => { if (!existingKeys.has(f.key)) merged.push(f); });
  return merged;
};

/** Flatten the step tree into leaf nodes (leaves carry the actual logs). */
const flattenLeaves = (nodes: StepNode[]): StepNode[] =>
  nodes.flatMap((n) => (n.children.length ? flattenLeaves(n.children) : [n]));

/** Flatten every node (leaves and parents) — used to resolve a selected step's name by key. */
const flattenAll = (nodes: StepNode[]): StepNode[] =>
  nodes.flatMap((n) => [n, ...flattenAll(n.children)]);

/** Pick the most useful step to show logs for: a running/failed leaf, else the last leaf. */
const pickDefaultStep = (nodes: StepNode[]): string | null => {
  const leaves = flattenLeaves(nodes);
  if (leaves.length === 0) return null;
  const running = leaves.find((n) => n.state === 'RUNNING');
  if (running) return running.key;
  const failed = leaves.find((n) => n.state === 'FAILED');
  if (failed) return failed.key;
  return leaves[leaves.length - 1].key;
};

/**
 * Shared modal to display background operations/commands.
 * Fetches from backend with pagination and shows live status when a row is selected.
 */
const BackgroundOperationsModal: React.FC<BackgroundOperationsModalProps> = ({ open, onClose, watchCommandId, onAutoClose }) => {
  const [commands, setCommands] = useState<CommandStatus[]>([]);
  const [auxCommands, setAuxCommands] = useState<CommandStatus[]>([]); // e.g. keytab/webhook helpers
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedStatus, setSelectedStatus] = useState<CommandStatus | null>(null);
  // Step trees kept as plain data, merged in place so the displayed list stays stable (see mergeSteps).
  const [stepMap, setStepMap] = useState<Record<string, StepNode[]>>({});
  const [loading, setLoading] = useState(false);
  const [offset, setOffset] = useState(0);
  const [hasMore, setHasMore] = useState(true);
  // The single currently-open (accordion) operation. The 2s poller refreshes only its steps + logs.
  const selectedIdRef = React.useRef<string | null>(null);
  useEffect(() => { selectedIdRef.current = selectedId; }, [selectedId]);
  // The sub-step whose logs the panel shows (a child command id). Null → the operation root itself.
  const [selectedStepId, setSelectedStepId] = useState<string | null>(null);
  const selectedStepIdRef = React.useRef<string | null>(null);
  useEffect(() => { selectedStepIdRef.current = selectedStepId; }, [selectedStepId]);

  const [logState, setLogState] = useState<Record<string, { content: string; offset: number; eof: boolean; loading: boolean }>>({});
  const logStateRef = React.useRef(logState);
  useEffect(() => { logStateRef.current = logState; }, [logState]);
  const [polling, setPolling] = useState<NodeJS.Timeout | null>(null);
  const [watchCommandCompleted, setWatchCommandCompleted] = useState(false);
  const logRefs = React.useRef<Record<string, HTMLDivElement | null>>({});
  const commandsRef = React.useRef<CommandStatus[]>([]);
  useEffect(() => { commandsRef.current = commands; }, [commands]);
  const auxCommandsRef = React.useRef<CommandStatus[]>([]);
  useEffect(() => { auxCommandsRef.current = auxCommands; }, [auxCommands]);

  // #8: logs are verbose and secondary to the step tree, so they are opt-in per
  // row (collapsed by default) and only fetched/auto-appended when shown.
  const [showLogs, setShowLogs] = useState<Record<string, boolean>>({});
  const showLogsRef = React.useRef<Record<string, boolean>>({});
  useEffect(() => { showLogsRef.current = showLogs; }, [showLogs]);

  // #7: only auto-scroll a log pane to the bottom when the user is already
  // parked there ("tail -f" behavior). Once they scroll up to read, the 2s log
  // refresh must NOT yank them back to the bottom. Pinned defaults to true.
  const logPinnedRef = React.useRef<Record<string, boolean>>({});
  const isPinned = (id: string) => logPinnedRef.current[id] !== false;
  const handleLogScroll = (id: string) => (e: React.UIEvent<HTMLDivElement>) => {
    const el = e.currentTarget;
    logPinnedRef.current[id] = (el.scrollHeight - el.scrollTop - el.clientHeight) < 24;
  };

  const isTerminal = (c?: CommandStatus | null): boolean => {
    if (!c || !c.state) return false;
    return c.state === 'SUCCEEDED' || c.state === 'FAILED' || c.state === 'CANCELED' || c.state === 'CANCELLED';
  };
  useEffect(() => {
    Object.entries(logState).forEach(([id, state]) => {
      const el = logRefs.current[id];
      if (el && state?.content && isPinned(id)) {
        el.scrollTop = el.scrollHeight;
      }
    });
  }, [logState]);

  // #9: collapse over-precise ISO timestamps in raw log lines to
  // "YYYY-MM-DD HH:MM:SS" — drop sub-second precision and the trailing Z/offset.
  const LOG_TS_RE = /(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?/g;
  const formatLogContent = (raw?: string): string =>
    (raw || '').replace(LOG_TS_RE, '$1 $2');

  const displayPercent = (cmd?: CommandStatus | null): number => {
    if (!cmd) return 0;
    const isLeafCommand = cmd.hasChildren === false;

    // For leaf commands, show an "in progress" indication while running so the bar is not stuck at 0%.
    if (isLeafCommand && cmd.state === 'RUNNING') {
      return Math.max(50, cmd.percent ?? 0);
    }
    if (isLeafCommand && cmd.state === 'PENDING') {
      return Math.max(0, cmd.percent ?? 0);
    }

    if (cmd.percent !== undefined && cmd.percent !== null) return cmd.percent;
    if (cmd.state === 'SUCCEEDED' || cmd.state === 'FAILED') return 100;
    return 0;
  };

  const formatTimestamp = (iso?: string): string => {
    if (!iso) return '—';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    const now = new Date();
    const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    const startOfYesterday = startOfToday - 24 * 60 * 60 * 1000;
    const ts = d.getTime();
    const timePart = d.toTimeString().slice(0, 5);
    if (ts >= startOfToday) {
      return `Today ${timePart}`;
    }
    if (ts >= startOfYesterday) {
      return `Yesterday ${timePart}`;
    }
    return `${d.toDateString()} ${timePart}`;
  };

  const formatDuration = (start?: string, end?: string): string => {
    if (!start || !end) return '—';
    const ms = new Date(end).getTime() - new Date(start).getTime();
    if (isNaN(ms) || ms < 0) return '—';
    const totalSec = Math.floor(ms / 1000);
    const h = Math.floor(totalSec / 3600);
    const m = Math.floor((totalSec % 3600) / 60);
    const s = totalSec % 60;
    if (h > 0) return `${h}h ${m}m ${s}s`;
    if (m > 0) return `${m}m ${s}s`;
    return `${s}s`;
  };

  const loadPage = async (reset = false) => {
    setLoading(true);
    try {
      const nextOffset = reset ? 0 : offset;
      const page = await listCommands(10, nextOffset);
      const primary: CommandStatus[] = [];
      const aux: CommandStatus[] = [];
      (page || []).forEach((c) => {
        if (c.type === 'KEYTAB_REQUEST_ROOT') {
          aux.push(c);
        } else {
          primary.push(c);
        }
      });

      if (reset) {
        setCommands(primary);
        setAuxCommands(aux);
        setOffset(10);
      } else {
        setCommands(prev => [...prev, ...primary.filter(p => !prev.find(x => x.id === p.id))]);
        setAuxCommands(prev => [...prev, ...aux.filter(p => !prev.find(x => x.id === p.id))]);
        setOffset(nextOffset + 10);
      }
      setHasMore(page.length === 10);
      // Prefetch status for visible rows
      page.forEach(async (c) => {
        try {
          const st = await getCommandStatus(c.id);
          setCommands(prev => prev.map(p => p.id === st.id ? st : p));
          setAuxCommands(prev => prev.map(p => p.id === st.id ? st : p));
          if (selectedId === st.id) setSelectedStatus(st);
        } catch {
          // ignore
        }
      });
    } catch (err) {
      // ignore
    } finally {
      setLoading(false);
    }
  };

  // Whether the operator opened or closed an operation since the modal opened: from then on nothing is opened for them.
  const operatorChoseRef = React.useRef(false);
  // Whether the watched operation was opened once it ended with something to read.
  const endOpenedRef = React.useRef(false);

  useEffect(() => {
    if (open) {
      // Nothing is open at first: an operation's steps and logs are read once the operator opens it.
      setSelectedId(null);
      operatorChoseRef.current = false;
      endOpenedRef.current = false;
      setSelectedStatus(null);
      setStepMap({});
      loadPage(true);
      setLogState({});
      setShowLogs({});
      logPinnedRef.current = {};
      setWatchCommandCompleted(false);

      // start lightweight polling for status refresh without rebuilding the table
      const t = setInterval(async () => {
        const current = commandsRef.current;
        if (!current || current.length === 0) return;
        try {
          const active = current.filter(c => !isTerminal(c));
          const updates = await Promise.all(
            active.map(async (c) => {
              try {
                return await getCommandStatus(c.id);
              } catch {
                return null;
              }
            })
          );
          setCommands(prev =>
            prev.map(c => {
              const upd = updates.find(u => u && u.id === c.id);
              return upd ? { ...c, ...upd } : c;
            })
          );
          if (watchCommandId && !watchCommandCompleted) {
            // Only apply watch behavior for the current command; keep polling other commands.
            const watched =
              updates.find(u => u && u.id === watchCommandId) ||
              current.find(c => c.id === watchCommandId) ||
              null;
            if (watched && isTerminal(watched)) {
              setWatchCommandCompleted(true);
              // A GitOps change still waiting for a merge also ends SUCCEEDED, with nothing applied yet: the modal
              // stays open so the operator reads what it waits for. So it does for a deploy with notices (where to
              // read a password KDPS generated for the release).
              if (watched.state === 'SUCCEEDED' && onAutoClose && !waitingForMerge(watched)
                  && operationNotices(watched).length === 0) {
                onAutoClose();
              }
            }
          }
          // Refresh the one open operation only. Steps are merged in place (mergeSteps) so the
          // displayed tree stays put and the accordion never re-renders/collapses — only statuses
          // and the tailing log move. IMPORTANT: tail the log FIRST and do NOT await fetchSteps,
          // otherwise a slow child-step fetch (many steps / busy backend) starves the log stream and
          // the operator has to hit Refresh to see output.
          const openId = selectedIdRef.current;
          if (openId) {
            const row = (current as any).find((c: any) => c.id === openId) || { id: openId };
            const running = !isTerminal(row) && (row.state === 'RUNNING' || row.state === 'PENDING');
            // Stream the operation's aggregate log (root command id) every tick. While the op is
            // running we force (eof=false) so it keeps following as new output is appended.
            const logInfo = logStateRef.current[openId];
            if (!logInfo?.loading && (running || !logInfo?.eof)) {
              void loadLogs(openId, false, true, running);
            }
            // Refresh steps in the background (fire-and-forget) so it can't block the log stream.
            void fetchSteps(openId);
          }
        } catch {
          // ignore poll errors
        }
      }, 2000);
      setPolling(t as any);
      return () => {
        clearInterval(t);
      };
    }
    return () => {
      if (polling) clearInterval(polling);
    };
  }, [open]);

  // Recursively fetch an operation's subtree as PLAIN DATA (no JSX). Children are sorted by
  // createdAt (then id) so the order is stable across refreshes.
  const buildStepData = async (node: CommandStatus): Promise<StepNode> => {
    let children: CommandStatus[] = [];
    try {
      children = await listChildCommands(node.id);
    } catch {
      children = [];
    }
    // Do NOT re-sort: the backend returns children in execution order (childListJson). Sorting by
    // createdAt scrambled them because steps are planned with near-identical timestamps.
    const childNodes = await Promise.all(children.map((ch) => buildStepData(ch)));
    return {
      key: node.id,
      message: node.message || node.type || node.id,
      state: node.state,
      percent: displayPercent(node),
      createdAt: node.createdAt,
      children: childNodes,
    };
  };

  // Fetch the steps of an operation (its root command's children) and merge them into what is already
  // shown, so the planned list stays put and only statuses update. Aux webhook tasks are appended for
  // deploy roots. Ignores results for an operation that is no longer the open one (avoids races when
  // the operator switches accordion panels mid-fetch).
  const fetchSteps = async (opId: string) => {
    let kids: CommandStatus[] = [];
    try {
      kids = await listChildCommands(opId);
    } catch {
      return;
    }
    // Preserve backend execution order (childListJson) — no createdAt re-sort.
    const fresh = await Promise.all(kids.map((ch) => buildStepData(ch)));
    const rootCmd = commandsRef.current.find((c) => c.id === opId);
    let extras: StepNode[] = [];
    if (rootCmd?.type === 'K8S_MANAGER_RELEASE_DEPLOY' && auxCommandsRef.current.length > 0) {
      extras = await Promise.all(auxCommandsRef.current.map((aux) => buildStepData(aux)));
    }
    const all = [...fresh, ...extras];
    // Only apply if this op is still the open one (guards the switch-panels race).
    if (selectedIdRef.current !== opId) return;
    setStepMap((prev) => ({ ...prev, [opId]: mergeSteps(prev[opId], all) }));
  };

  // Open an operation (accordion): make it the single tracked op, load its steps + logs, and keep logs
  // open. selectedIdRef is set synchronously so the poller and fetchSteps' race-guard see it at once.
  const selectOperation = async (record: CommandStatus) => {
    setSelectedId(record.id);
    selectedIdRef.current = record.id;
    setSelectedStepId(null);          // default to auto-picking a step once steps load
    selectedStepIdRef.current = null;
    setShowLogs(s => ({ ...s, [record.id]: true }));
    logPinnedRef.current[record.id] = true;
    try {
      const st = await getCommandStatus(record.id);
      setSelectedStatus(st);
      setCommands(prev => prev.map(p => p.id === st.id ? st : p));
    } catch {
      // ignore; steps/logs below still attempt to load
    }
    await fetchSteps(record.id);
    if (!logStateRef.current[record.id]?.content) void loadLogs(record.id, true);
  };

  // The watched operation opens once, when it ended with something to read (a change waiting for a merge, where to read
  // a generated password): the reason the modal stays open. Never while it runs, and never once the operator opened or
  // closed an operation: the 2s refresh must not take them away from the one they are reading.
  useEffect(() => {
    if (!open || !watchCommandId || endOpenedRef.current || operatorChoseRef.current) return;
    const watched = commands.find((row) => row.id === watchCommandId);
    if (!watched || !isTerminal(watched)) return;
    if (!waitingForMerge(watched) && operationNotices(watched).length === 0) return;
    endOpenedRef.current = true;
    if (selectedIdRef.current !== watched.id) void selectOperation(watched);
  }, [open, watchCommandId, commands]);

  // (The Logs panel shows the operation's aggregate root log directly — no per-step auto-selection.)

  const loadLogs = async (id: string, reset = false, quiet = false, force = false) => {
    setLogState(prev => ({
      ...prev,
      [id]: { content: reset ? '' : (prev[id]?.content || ''), offset: reset ? 0 : (prev[id]?.offset || 0), eof: reset ? false : (prev[id]?.eof || false), loading: quiet ? prev[id]?.loading ?? false : true }
    }));
    try {
      const state = logStateRef.current[id] || { offset: 0, content: '', eof: false };
      const res = await getCommandLogs(id, reset ? 0 : state.offset);
      setLogState(prev => ({
        ...prev,
        [id]: {
          content: (reset ? res.content : (prev[id]?.content || '') + res.content),
          offset: res.nextOffset,
          eof: force ? false : res.eof,
          loading: quiet ? prev[id]?.loading ?? false : false
        }
      }));
    } catch (err: any) {
      message.error(err?.message || 'Failed to load logs');
      setLogState(prev => ({
        ...prev,
        [id]: { ...(prev[id] || { content: '', offset: 0, eof: false }), loading: false }
      }));
    }
  };

  const stateColor = (s?: string) => s === 'FAILED' ? 'red' : s === 'SUCCEEDED' ? 'green' : 'blue';
  const progressStatus = (s?: string) => s === 'FAILED' ? 'exception' : s === 'SUCCEEDED' ? 'success' : 'active';

  // Render StepNode data → antd Tree nodes. Keys are stable command ids so antd reconciles in place
  // (no remount / no lost expansion) across refreshes — only each title's status/percent changes.
  const stepIcon = (state?: string) => {
    if (state === 'SUCCEEDED') return <CheckCircleTwoTone twoToneColor="#52c41a" />;
    if (state === 'FAILED') return <CloseCircleTwoTone twoToneColor="#ff4d4f" />;
    if (state === 'RUNNING') return <LoadingOutlined style={{ color: 'var(--accent, #1677ff)' }} />;
    return <ClockCircleOutlined style={{ color: 'var(--ink-dim, #8a97a9)' }} />; // PENDING / other
  };

  const stepToTreeData = (nodes: StepNode[]): any[] => nodes.map((n) => ({
    key: n.key,
    // width:100% + right-aligned fixed columns → the state Tag and progress bar line up on the panel's
    // right edge at EVERY tree depth (paired with the .bgops-steps CSS that makes the node fill the row).
    // The leading status icon signals this is a tracked step executed by Ambari.
    title: (
      <div style={{ display: 'grid', gridTemplateColumns: '18px minmax(0, 1fr) 96px 130px', alignItems: 'center', gap: 10, width: '100%', minHeight: 34 }}>
        <span style={{ fontSize: 14, lineHeight: 1 }}>{stepIcon(n.state)}</span>
        <Typography.Text ellipsis title={n.message} style={{ minWidth: 0, fontWeight: 500 }}>{n.message}</Typography.Text>
        <Tag color={stateColor(n.state)} style={{ margin: 0, textAlign: 'center', width: '100%' }}>{n.state}</Tag>
        <Progress percent={n.percent} size="small" status={progressStatus(n.state) as any} showInfo style={{ marginBottom: 0, width: 130 }} />
      </div>
    ),
    children: n.children.length ? stepToTreeData(n.children) : undefined,
  }));

  // Accordion toggle: opening a panel loads its steps + logs; closing clears the selection so the
  // 2s poller stops tailing it.
  const onAccordionChange = (key: string | string[]) => {
    operatorChoseRef.current = true;
    const id = Array.isArray(key) ? key[0] : key;
    if (!id) {
      setSelectedId(null);
      selectedIdRef.current = null;
      setSelectedStepId(null);
      selectedStepIdRef.current = null;
      return;
    }
    const op = commands.find(c => c.id === id);
    if (op) void selectOperation(op);
  };

  // Body of an expanded operation: FAILED banner (full width) on top, then Steps | Logs side by side.
  const renderOperationBody = (op: CommandStatus) => {
    const status = (selectedStatus?.id === op.id ? selectedStatus : null) || op;
    const steps = stepMap[op.id] || [];
    const running = status.state === 'RUNNING' || status.state === 'PENDING';
    // Logs panel shows the operation's AGGREGATE log (the root command id): every step tees its
    // output into the root as it runs, so this is the full, in-order operation log — no per-step
    // clicking needed. The steps tree beside it is a progress display only.
    const logId = op.id;
    const logs = logState[logId];
    // From the row the poller keeps current: the status fetched when the operation was opened may predate its end.
    const pendingMerge = op.state === 'SUCCEEDED' ? waitingForMerge(op) : null;
    const notices = op.state === 'SUCCEEDED' ? operationNotices(op) : [];
    // What a GitOps deploy did in Git, from the moment its commit step ran: the commit, the branch, the pull request.
    const gitOps = gitOpsResult(op);
    const gitOpsSummary = gitOps ? describeGitOps(gitOps) : null;
    const pullRequestUrl = pendingMerge?.pullRequestUrl || gitOps?.prUrl;
    return (
      <>
        {notices.length > 0 && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="Before you sign in"
            description={(
              <Space direction="vertical" size={4}>
                {notices.map((notice, i) => (
                  <span key={i} style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{notice}</span>
                ))}
              </Space>
            )}
          />
        )}
        {(pendingMerge || gitOpsSummary) && (
          <Alert
            type={pendingMerge ? 'warning' : 'info'}
            showIcon
            style={{ marginBottom: 12 }}
            message={pendingMerge ? 'Committed to Git, not applied yet' : gitOpsSummary?.title}
            description={(
              <Space direction="vertical" size={4}>
                {gitOpsSummary && gitOpsSummary.lines.length > 0 && (
                  <span style={{ wordBreak: 'break-word' }}>{gitOpsSummary.lines.join(' · ')}</span>
                )}
                {pendingMerge && (
                  <span style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{pendingMerge.message}</span>
                )}
                {pullRequestUrl && (
                  <Typography.Link href={pullRequestUrl} target="_blank" rel="noopener noreferrer">
                    Open the pull request
                  </Typography.Link>
                )}
              </Space>
            )}
          />
        )}
        {status.state === 'FAILED' && status.error && (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 12 }}
            message="Operation failed"
            description={<span style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{status.error}</span>}
          />
        )}
        <div style={{ display: 'flex', gap: 16, alignItems: 'stretch' }}>
          {/* Steps panel */}
          <div style={{ flex: '0 0 42%', minWidth: 0, maxHeight: '50vh', overflow: 'auto', borderRight: '1px solid var(--line)', paddingRight: 12 }}>
            <Typography.Text strong style={{ display: 'block', marginBottom: 6 }}>Execution steps</Typography.Text>
            {steps.length > 0 ? (
              <Tree
                className="bgops-steps"
                defaultExpandAll
                selectable={false}
                treeData={stepToTreeData(steps)}
              />
            ) : (
              <Typography.Text type="secondary">{running ? 'Planning steps…' : 'No steps available.'}</Typography.Text>
            )}
          </div>
          {/* Logs panel — the operation's full aggregate log */}
          <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6, gap: 8 }}>
              <Space size={6} style={{ minWidth: 0 }}>
                <Typography.Text strong>Logs</Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>— full operation</Typography.Text>
              </Space>
              <Space>
                <Button size="small" onClick={() => { logPinnedRef.current[logId] = true; loadLogs(logId, true); }}>Refresh</Button>
                <Button size="small" onClick={() => loadLogs(logId, false)} disabled={logs?.eof} loading={logs?.loading}>
                  {logs?.eof ? 'End' : 'Load more'}
                </Button>
              </Space>
            </div>
            <div
              ref={(el) => { logRefs.current[logId] = el; }}
              onScroll={handleLogScroll(logId)}
              style={{ background: '#0d1117', color: '#c9d1d9', padding: 10, borderRadius: 6, flex: 1, minHeight: 200, maxHeight: '50vh', overflow: 'auto', fontFamily: 'var(--mono)', whiteSpace: 'pre-wrap', fontSize: 12 }}
            >
              {logs?.content ? formatLogContent(logs.content) : (logs?.loading ? 'Loading…' : (running ? 'Waiting for output…' : 'No logs yet.'))}
            </div>
          </div>
        </div>
      </>
    );
  };

  return (
    <Modal
      title="Background operations"
      open={open}
      onCancel={onClose}
      footer={null}
      width="95vw"
      style={{ top: 20 }}
      styles={{ body: { height: '80vh', overflow: 'hidden', padding: 0, display: 'flex', flexDirection: 'column' } }}
      destroyOnHidden
    >
      <div style={{ flex: 1, overflow: 'auto', padding: 16, minHeight: 0 }}>
        {loading && commands.length === 0 ? (
          <Spin />
        ) : commands.length === 0 ? (
          <Typography.Text type="secondary">No operations.</Typography.Text>
        ) : (
          <Collapse
            accordion
            activeKey={selectedId || undefined}
            onChange={onAccordionChange}
            items={commands.map((op) => ({
              key: op.id,
              // Header sits on top of the two panels; shows name + state + progress + time.
              // The Abort/Resume control lives INSIDE the header row (last flex item), not in antd's
              // `extra` slot: `extra` overlaps a full-width label, so keeping everything in one flex
              // row (name ellipsises, the rest flexShrink:0) guarantees no overlap with the timestamp.
              label: (
                <div style={{ display: 'flex', alignItems: 'center', gap: 12, minWidth: 0, width: '100%' }}>
                  <Typography.Text strong ellipsis style={{ flex: 1, minWidth: 0 }} title={op.message || op.type || op.id}>
                    {op.message || op.type || op.id}
                  </Typography.Text>
                  {op.state === 'SUCCEEDED' && waitingForMerge(op) && (
                    <Tag color="gold" style={{ margin: 0, flexShrink: 0 }}>WAITING FOR MERGE</Tag>
                  )}
                  {/* Notices are read in the operation's body, which stays closed until opened. */}
                  {operationNotices(op).length > 0 && (
                    <Tag color="gold" style={{ margin: 0, flexShrink: 0 }}>NOTICE</Tag>
                  )}
                  {/* Fixed columns, so the state, progress, time and action line up from one operation to the next
                      whether or not it has an action. */}
                  <div style={{ display: 'grid', gridTemplateColumns: '120px 150px 190px 96px', columnGap: 12, alignItems: 'center', flexShrink: 0 }}>
                  <Tag color={stateColor(op.state)} style={{ margin: 0, width: '100%', textAlign: 'center' }}>{op.state}</Tag>
                  <Progress percent={displayPercent(op)} size="small" status={progressStatus(op.state) as any} style={{ width: '100%', marginBottom: 0 }} />
                  <Typography.Text type="secondary" style={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>{formatTimestamp(op.updatedAt)}</Typography.Text>
                  {(op.state === 'RUNNING' || op.state === 'PENDING') ? (
                    <Button size="small" danger block onClick={async (e) => {
                      e.stopPropagation();
                      Modal.confirm({
                        title: `Abort "${op.message || op.type || op.id}"?`,
                        content: 'The step that is running now will finish, the remaining steps will be skipped, and the '
                          + 'operation cannot be resumed afterwards. To complete it, run the wizard again.',
                        okText: 'Abort operation',
                        okButtonProps: { danger: true },
                        cancelText: 'Keep running',
                        onOk: async () => {
                          try { await cancelCommand(op.id); message.success('Abort requested'); loadPage(true); }
                          catch (err: any) { message.error(err?.message || 'Abort failed'); }
                        },
                      });
                    }}>Abort</Button>
                  ) : (op.state === 'FAILED') ? (
                    // Resume from the failed step: re-runs that sub-step and continues, skipping the
                    // succeeded steps — for transient sub-step failures, so the whole KDPS wizard flow
                    // need not be repeated. Blue, in the same slot Abort uses while running.
                    <Button size="small" type="primary" block onClick={async (e) => {
                      e.stopPropagation();
                      try { await resumeCommand(op.id); message.success('Resuming from the failed step'); loadPage(true); }
                      catch (err: any) { message.error(err?.message || 'Resume failed'); }
                    }}>Resume</Button>
                  ) : <span />}
                  </div>
                </div>
              ),
              children: renderOperationBody(op),
            }))}
          />
        )}
        {hasMore && (
          <div style={{ marginTop: 12, textAlign: 'center' }}>
            <Button size="small" onClick={() => loadPage(false)}>Load more</Button>
          </div>
        )}
      </div>

      {/* footer actions */}
      <div style={{ padding: '8px 16px', borderTop: '1px solid var(--line)', display: 'flex', justifyContent: 'space-between', flexShrink: 0 }}>
        <Button size="small" onClick={async () => {
          try { await refreshDependencies(); message.success('Webhook refresh requested'); }
          catch (err: any) { message.error(err?.message || 'Refresh failed'); }
        }}>Refresh dependencies</Button>
        <Button size="small" onClick={() => loadPage(true)}>Reload list</Button>
      </div>
    </Modal>
  );
};

export default BackgroundOperationsModal;
