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
import { Alert, Form, InputNumber, Modal, Skeleton, Switch, Typography } from 'antd';
import type { HelmRelease } from '../../types';
import { getKedaDiscovery, getReleaseValues } from '../../api/client';

/** Worker scaling of a Trino release: autoscaling bounds when on, a fixed worker count when off. */
export interface TrinoScaling {
  enabled: boolean;
  workers: number;
  minWorkers: number;
  maxWorkers: number;
}

/** Reads the worker scaling from a release's values (chart defaults where unset). */
export const scalingFromValues = (values: any): TrinoScaling => {
  const keda = values?.server?.keda || {};
  return {
    enabled: keda.enabled === true || keda.enabled === 'true',
    workers: Number(values?.server?.workers ?? 3),
    minWorkers: Number(keda.minReplicaCount ?? 1),
    maxWorkers: Number(keda.maxReplicaCount ?? 10),
  };
};

/** The release's values with the given worker scaling; the input is not modified. */
export const valuesWithScaling = (values: any, scaling: TrinoScaling): any => {
  const next = JSON.parse(JSON.stringify(values || {}));
  next.server = next.server || {};
  next.server.keda = next.server.keda || {};
  next.server.keda.enabled = scaling.enabled;
  if (scaling.enabled) {
    next.server.keda.minReplicaCount = scaling.minWorkers;
    next.server.keda.maxReplicaCount = scaling.maxWorkers;
  } else {
    next.server.workers = scaling.workers;
  }
  return next;
};

type Props = {
  release: HelmRelease | null;
  onClose: () => void;
  /** Redeploys the release with these values (same chart version). */
  onApply: (release: HelmRelease, values: any) => Promise<void>;
};

/** Turns worker autoscaling on or off on a deployed Trino release. */
const TrinoAutoscalingModal: React.FC<Props> = ({ release, onClose, onApply }) => {
  const [values, setValues] = React.useState<any>(null);
  const [scaling, setScaling] = React.useState<TrinoScaling | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);

  React.useEffect(() => {
    setValues(null); setScaling(null); setError(null);
    if (!release) return;
    getReleaseValues(release.namespace, release.name)
      .then((v: any) => { setValues(v || {}); setScaling(scalingFromValues(v)); })
      .catch((e: any) => setError(e?.message || 'Could not read the release values'));
  }, [release]);

  const apply = async () => {
    if (!release || !scaling) return;
    setError(null);
    if (scaling.enabled && scaling.minWorkers > scaling.maxWorkers) {
      setError('The minimum number of workers is above the maximum.');
      return;
    }
    setBusy(true);
    try {
      if (scaling.enabled && !scalingFromValues(values).enabled) {
        // The autoscaler is a KEDA ScaledObject: without a KEDA operator the redeploy would fail.
        const keda = await getKedaDiscovery().catch(() => null);
        if (!keda?.present) {
          setError('No KEDA operator was found on the cluster, so autoscaling cannot be turned on. Install KEDA '
            + '(on OpenShift: the Custom Metrics Autoscaler operator) first.');
          return;
        }
      }
      await onApply(release, valuesWithScaling(values, scaling));
      onClose();
    } catch (e: any) {
      setError(e?.message || 'The redeploy could not be started');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={!!release}
      title={release ? `Worker autoscaling — ${release.name}` : 'Worker autoscaling'}
      okText="Apply"
      onOk={apply}
      okButtonProps={{ disabled: !scaling, loading: busy }}
      onCancel={onClose}
      destroyOnClose
    >
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      {!scaling && !error && <Skeleton active />}
      {scaling && (
        <Form layout="vertical">
          <Form.Item label="Worker autoscaling">
            <Switch checked={scaling.enabled} onChange={(enabled) => setScaling({ ...scaling, enabled })} />
          </Form.Item>
          {scaling.enabled ? (
            <>
              <Form.Item label="Minimum workers">
                <InputNumber min={0} value={scaling.minWorkers} onChange={(v) => setScaling({ ...scaling, minWorkers: Number(v ?? 0) })} />
              </Form.Item>
              <Form.Item label="Maximum workers">
                <InputNumber min={1} value={scaling.maxWorkers} onChange={(v) => setScaling({ ...scaling, maxWorkers: Number(v ?? 1) })} />
              </Form.Item>
            </>
          ) : (
            <Form.Item label="Number of workers" help="Workers run at this fixed count once the autoscaler is removed.">
              <InputNumber min={0} value={scaling.workers} onChange={(v) => setScaling({ ...scaling, workers: Number(v ?? 0) })} />
            </Form.Item>
          )}
          <Typography.Text type="secondary">
            Applying redeploys the release with its current chart version and values; only the worker scaling changes.
          </Typography.Text>
        </Form>
      )}
    </Modal>
  );
};

export default TrinoAutoscalingModal;
