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
import { Table, Tag, Tooltip, Typography } from 'antd';
import type { ReleasePrincipal } from '../types';

const { Text } = Typography;

/** Who owns the account in its KDC, as the release's record tells. */
export function accountOrigin(p: ReleasePrincipal): { label: string; color: string; tip: string } {
  if (p.created === true) {
    return { label: 'Created by KDPS', color: 'blue', tip: 'KDPS created this account for the release\'s namespace. Once no release uses it, it can be deleted from the KDC.' };
  }
  if (p.created === false) {
    return { label: 'Existing account', color: 'default', tip: 'The account existed before KDPS issued its keytab: KDPS never deletes it.' };
  }
  return { label: 'Not recorded', color: 'default', tip: 'Issued before KDPS recorded who created the account, or by the Ambari cluster\'s KDC.' };
}

/**
 * The Kerberos principals KDPS issued keytabs of for a release: the KDC, who owns the account, the Secret holding the
 * keytab and the last issuance. Kept with the release's record, so it is known what KDPS created in which KDC.
 */
export default function ReleasePrincipals({ principals }: { principals?: ReleasePrincipal[] }) {
  if (!principals || principals.length === 0) return null;
  return (
    <div>
      <Text strong>Kerberos principals</Text>
      <Table<ReleasePrincipal> size="small" rowKey={p => `${p.principal}/${p.secret || ''}`} pagination={false}
        style={{ marginTop: 6 }} dataSource={principals}
        columns={[
          { title: 'Principal', dataIndex: 'principal', render: (v: string) => <Text code style={{ fontSize: 11 }}>{v}</Text> },
          { title: 'KDC', key: 'kdc', width: 150,
            render: (_: unknown, p) => p.kdcContextId ? <Text style={{ fontSize: 12 }}>context {p.kdcContextId}</Text>
              : <Text type="secondary" style={{ fontSize: 12 }}>Ambari cluster</Text> },
          { title: 'Account', key: 'origin', width: 140,
            render: (_: unknown, p) => { const o = accountOrigin(p); return <Tooltip title={o.tip}><Tag color={o.color} style={{ margin: 0 }}>{o.label}</Tag></Tooltip>; } },
          { title: 'Keytab Secret', dataIndex: 'secret', render: (v?: string | null) => v ? <Text code style={{ fontSize: 11 }}>{v}</Text> : '—' },
          { title: 'Last issued', key: 'issued', width: 170,
            render: (_: unknown, p) => p.issuedAt ? (
              <Tooltip title={p.kvno != null ? `Key version ${p.kvno}` : undefined}>
                <Text style={{ fontSize: 12 }}>{new Date(p.issuedAt).toLocaleString()}{p.issuedBy ? ` · ${p.issuedBy}` : ''}</Text>
              </Tooltip>
            ) : '—' },
        ]}
      />
    </div>
  );
}
