import React, { useEffect, useState } from 'react';
import { Modal, Table, Progress, Typography, Space, Button, Spin, Tree, Tag, message } from 'antd';
import { cancelCommand, getCommandStatus, listCommands, listChildCommands, refreshDependencies, getCommandLogs, type CommandStatus } from '../../api/client';

type BackgroundOperationsModalProps = {
  open: boolean;
  onClose: () => void;
  watchCommandId?: string;
  onAutoClose?: () => void;
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
  const [treeDataMap, setTreeDataMap] = useState<Record<string, any[]>>({});
  const [loading, setLoading] = useState(false);
  const [offset, setOffset] = useState(0);
  const [hasMore, setHasMore] = useState(true);
  const [expandedRowKeys, setExpandedRowKeys] = useState<React.Key[]>([]);
  const expandedRowKeysRef = React.useRef<React.Key[]>([]);
  useEffect(() => { expandedRowKeysRef.current = expandedRowKeys; }, [expandedRowKeys]);

  const [logState, setLogState] = useState<Record<string, { content: string; offset: number; eof: boolean; loading: boolean }>>({});
  const logStateRef = React.useRef(logState);
  useEffect(() => { logStateRef.current = logState; }, [logState]);
  const [polling, setPolling] = useState<NodeJS.Timeout | null>(null);
  const logRefs = React.useRef<Record<string, HTMLDivElement | null>>({});
  const commandsRef = React.useRef<CommandStatus[]>([]);
  useEffect(() => { commandsRef.current = commands; }, [commands]);

  const isTerminal = (c?: CommandStatus | null): boolean => {
    if (!c || !c.state) return false;
    return c.state === 'SUCCEEDED' || c.state === 'FAILED' || c.state === 'CANCELED' || c.state === 'CANCELLED';
  };
  useEffect(() => {
    Object.entries(logState).forEach(([id, state]) => {
      const el = logRefs.current[id];
      if (el && state?.content) {
        el.scrollTop = el.scrollHeight;
      }
    });
  }, [logState]);

  const displayPercent = (cmd?: CommandStatus | null): number => {
    if (!cmd) return 0;
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

  useEffect(() => {
    if (open) {
      setSelectedId(watchCommandId || null);
      setSelectedStatus(null);
      setTreeDataMap({});
      setExpandedRowKeys([]);
      loadPage(true);
      setLogState({});

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
          if (watchCommandId) {
            const watched = updates.find(u => u && u.id === watchCommandId);
            if (watched && watched.state === 'SUCCEEDED' && onAutoClose) {
              onAutoClose();
              return;
            }
          }
          // refresh children + logs for expanded rows only
          for (const key of expandedRowKeysRef.current) {
            const row = (current as any).find((c: any) => c.id === key);
            if (row) {
              try {
                if (!isTerminal(row)) {
                  const kids = await listChildCommands(row.id);
                  const trees = await Promise.all(kids.map(async (ch) => await buildTree(ch)));
                  setTreeDataMap(prev => ({ ...prev, [row.id]: trees.flat() }));
                }
              } catch {
                // ignore child refresh errors
              }
              // auto-append logs when expanded (force if still running)
              const logInfo = logStateRef.current[row.id];
              const forceLogs = !isTerminal(row) && (row.state === 'RUNNING' || row.state === 'PENDING');
              const needsLogs = (!logInfo?.eof) || forceLogs;
              if (needsLogs && !logInfo?.loading) {
                void loadLogs(row.id, false, true, forceLogs);
              }
            }
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

  const buildTree = async (node: CommandStatus): Promise<any> => {
    // Always attempt to fetch children; some backends omit the hasChildren flag
    let children: CommandStatus[] = [];
    try {
      children = await listChildCommands(node.id);
    } catch {
      children = [];
    }
    const childNodes = await Promise.all(
      children.map(async (ch) => await buildTree(ch))
    );

    const status = node.state === 'FAILED' ? 'exception' : node.state === 'SUCCEEDED' ? 'success' : 'active';
    const durationMs = node.createdAt && node.updatedAt ? (new Date(node.updatedAt).getTime() - new Date(node.createdAt).getTime()) : undefined;
    const duration = durationMs ? `${(durationMs / 1000).toFixed(2)}s` : '';

    const title = (
      <div style={{ width: '100%', minWidth: 720 }}>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'minmax(320px, 1fr) 120px 90px',
            alignItems: 'center',
            gap: 8,
            minHeight: 24
          }}
        >
          <Typography.Text
            ellipsis
            title={node.message || node.type || node.id}
            style={{ maxWidth: '100%', whiteSpace: 'nowrap' }}
          >
            {node.message || node.type || node.id}
          </Typography.Text>
          <Tag color={status === 'success' ? 'green' : status === 'exception' ? 'red' : 'blue'} style={{ justifySelf: 'end' }}>
            {node.state}
          </Tag>
          {duration ? (
            <Typography.Text type="secondary" style={{ fontSize: 12, justifySelf: 'end' }}>{duration}</Typography.Text>
          ) : (
            <span />
          )}
        </div>
        <div style={{ marginTop: 4 }}>
          <Progress
            percent={displayPercent(node)}
            size="small"
            status={status as any}
            showInfo
            style={{ width: '100%' }}
          />
        </div>
      </div>
    );

    return {
      key: node.id,
      title,
      children: childNodes
    };
  };

  const onExpandRow = async (expanded: boolean, record: CommandStatus) => {
    if (!expanded) {
      setExpandedRowKeys(keys => keys.filter(k => k !== record.id));
      return;
    }
    setExpandedRowKeys(keys => [...keys, record.id]);
    // kick off log load on expand
    void loadLogs(record.id, true);
    try {
      const st = await getCommandStatus(record.id);
      setSelectedId(record.id);
      setSelectedStatus(st);
      setCommands(prev => prev.map(p => p.id === st.id ? st : p));
      const tree = await buildTree(st);

      // Attach auxiliary commands (e.g., webhook keytab/secret tasks) under the deploy root for context
      let extras: any[] = [];
      if (st.type === 'K8S_MANAGER_RELEASE_DEPLOY' && auxCommands.length > 0) {
        extras = await Promise.all(auxCommands.map(async (aux) => await buildTree(aux)));
      }

      setTreeDataMap(prev => ({ ...prev, [record.id]: [...[tree], ...extras] }));
    } catch {
      setTreeDataMap(prev => ({ ...prev, [record.id]: [] }));
    }
  };

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

  return (
    <Modal
      title="Background operations"
      open={open}
      onCancel={onClose}
      footer={null}
      width="95vw"
      style={{ top: 20 }}
      bodyStyle={{ maxHeight: '80vh', overflow: 'auto' }}
      destroyOnClose
    >
      <Table
        size="small"
        loading={loading}
        pagination={false}
        rowKey="id"
        dataSource={commands}
        onExpand={onExpandRow}
        expandedRowKeys={expandedRowKeys}
          expandable={{
            expandRowByClick: true,
            expandedRowRender: (record) => {
              const tree = treeDataMap[record.id] || [];
              const status = commands.find(c => c.id === record.id);
              const logs = logState[record.id];
              return (
                <div style={{ padding: '8px 16px' }}>
                  {!status ? (
                    <Spin />
                  ) : (
                  <>
                    <Space size="middle" style={{ marginBottom: 8 }}>
                      <Tag color={status.state === 'FAILED' ? 'red' : status.state === 'SUCCEEDED' ? 'green' : 'blue'}>
                        {status.state}
                      </Tag>
                      {status.createdBy && <Typography.Text type="secondary">User: {status.createdBy}</Typography.Text>}
                      {status.updatedAt && <Typography.Text type="secondary">{formatTimestamp(status.updatedAt)}</Typography.Text>}
                    </Space>
                    <Progress percent={displayPercent(status)} status={status.state === 'FAILED' ? 'exception' : status.state === 'SUCCEEDED' ? 'success' : 'active'} />
                    {tree.length > 0 ? (
                      <Tree defaultExpandAll selectable={false} treeData={tree} style={{ marginTop: 8 }} />
                    ) : (
                      <Typography.Text type="secondary">No steps available.</Typography.Text>
                    )}
                    <div style={{ marginTop: 10 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <Typography.Text strong>Logs</Typography.Text>
                        <Space>
                          <Button size="small" onClick={() => loadLogs(record.id, true)}>Refresh</Button>
                          <Button size="small" onClick={() => loadLogs(record.id, false)} disabled={logs?.eof} loading={logs?.loading}>
                            {logs?.eof ? 'End' : 'Load more'}
                          </Button>
                        </Space>
                      </div>
                      <div
                        ref={(el) => { logRefs.current[record.id] = el; }}
                        style={{ background: '#0d1117', color: '#c9d1d9', padding: 8, borderRadius: 6, marginTop: 6, minHeight: 80, maxHeight: 220, overflow: 'auto', fontFamily: 'monospace', whiteSpace: 'pre-wrap', fontSize: 12 }}
                      >
                        {logs?.content ? logs.content : (logs?.loading ? 'Loading…' : 'No logs yet.')}
                      </div>
                    </div>
                  </>
                )}
              </div>
            );
          },
          rowExpandable: (record) => true
        }}
        columns={[
          {
            title: 'Operation',
            dataIndex: 'message',
            render: (text: string | undefined, row) => (
              <span>{text || `${row.type ?? 'Operation'}`}</span>
            )
          },
          {
            title: 'Status',
            dataIndex: 'state',
            width: 200,
            render: (_: any, row) => {
              const status = row.state === 'FAILED' ? 'exception' : row.state === 'SUCCEEDED' ? 'success' : 'active';
              return (
                <div>
                  <Typography.Text>{row.state}</Typography.Text>
                  <Progress percent={displayPercent(row)} size="small" status={status as any} showInfo />
                </div>
              );
            }
          },
          {
            title: 'User',
            dataIndex: 'createdBy',
            width: 140,
            render: (v: string | undefined) => v || '—'
          },
          {
            title: 'Updated',
            dataIndex: 'updatedAt',
            render: (v: string | undefined) => formatTimestamp(v)
          },
          {
            title: 'Duration',
            dataIndex: 'duration',
            width: 140,
            render: (_: any, row) => formatDuration(row.createdAt, row.updatedAt)
          },
          {
            title: 'Actions',
            width: 120,
            render: (_: any, row) => {
              const canCancel = row.state === 'RUNNING' || row.state === 'PENDING';
              return (
                <Space>
                  {canCancel && (
                    <Button size="small" danger onClick={async (e) => {
                      e.stopPropagation();
                      try {
                        await cancelCommand(row.id);
                        message.success('Abort requested');
                        loadPage(true);
                      } catch (err: any) {
                        message.error(err?.message || 'Abort failed');
                      }
                    }}>Abort</Button>
                  )}
                </Space>
              );
            }
          }
        ]}
      />
      {hasMore && (
        <div style={{ marginTop: 12, textAlign: 'center' }}>
          <Button onClick={() => loadPage(false)}>Load more</Button>
        </div>
      )}
      <div style={{ marginTop: 8, textAlign: 'space-between', display: 'flex' }}>
        <Button size="small" onClick={async () => {
          try {
            await refreshDependencies();
            message.success('Webhook refresh requested');
          } catch (err: any) {
            message.error(err?.message || 'Refresh failed');
          }
        }}>Refresh dependencies</Button>
        <Button size="small" onClick={() => loadPage(true)}>Reload list</Button>
      </div>
    </Modal>
  );
};

export default BackgroundOperationsModal;
