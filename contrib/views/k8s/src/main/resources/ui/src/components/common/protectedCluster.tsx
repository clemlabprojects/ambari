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

import { Input, Modal, Space, Typography } from 'antd';
import type { ProtectedClusterError } from '../../api/client';

/** Whether an error is the refusal of a protected cluster ({@link ProtectedClusterError}), told by its name. */
export const isProtectedClusterRefusal = (e: unknown): e is ProtectedClusterError =>
  e instanceof Error && e.name === 'ProtectedClusterError' && !!(e as ProtectedClusterError).cluster;

/** What an action resolves to when the operator cancels it at the cluster's name. */
export const CANCELLED = Symbol('cancelled');

/** Asks the operator to type the name of a protected cluster: what was typed, or null when cancelled. */
export const askClusterName = (refusal: ProtectedClusterError): Promise<string | null> =>
  new Promise((resolve) => {
    let typed = '';
    Modal.confirm({
      title: `Type the name of the cluster ${refusal.cluster.name}`,
      content: (
        <Space direction="vertical" style={{ width: '100%' }}>
          <Typography.Text>{refusal.message}</Typography.Text>
          <Input autoFocus aria-label="Cluster name" placeholder={refusal.cluster.name}
            onChange={(e) => { typed = e.target.value; }} />
        </Space>
      ),
      okText: 'Confirm',
      okButtonProps: { danger: true },
      cancelText: 'Cancel',
      onOk: () => resolve(typed),
      onCancel: () => resolve(null),
    });
  });

/**
 * Runs an uninstall, a rollback or an upgrade. When the release's cluster is protected, the server refuses it until the
 * operator types the cluster's name: it is asked for, and the action runs again with it, until it is the right one or
 * the operator cancels.
 */
export async function confirmingProtectedCluster<T>(
  run: (confirmCluster?: string) => Promise<T>,
): Promise<T | typeof CANCELLED> {
  let confirmCluster: string | undefined;
  for (;;) {
    try {
      return await run(confirmCluster);
    } catch (e) {
      if (!isProtectedClusterRefusal(e)) throw e;
      const typed = await askClusterName(e);
      if (typed === null) return CANCELLED;
      confirmCluster = typed;
    }
  }
}
