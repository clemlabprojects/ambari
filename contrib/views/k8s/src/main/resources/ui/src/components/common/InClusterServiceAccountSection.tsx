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
import { Alert, Button, Descriptions, Space, Spin, Tag, Typography, message } from 'antd';
import { CheckCircleOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import {
    getInClusterServiceAccount,
    connectWithInClusterServiceAccount,
    type InClusterServiceAccountStatus,
} from '../../api/client';

const { Title, Paragraph, Text } = Typography;

/** The reason the server gave for a failed call: fetchJson errors read "HTTP <code> – <body>", with a JSON body. */
const serverReason = (e: any, fallback: string): string => {
    const text: string = e?.message || '';
    const start = text.indexOf('{');
    if (start >= 0) {
        try {
            const body = JSON.parse(text.slice(start));
            return body?.error || body?.message || text;
        } catch { /* not JSON: keep the text */ }
    }
    return text || fallback;
};

interface Props {
    /** Called once KDPS is connected with the ServiceAccount, to refresh the connection status of the page. */
    onConnected?: () => void;
}

/**
 * Cluster connection with the ServiceAccount of the pod Ambari runs in, when Ambari runs in the cluster it manages
 * (the ambari chart with inClusterServiceAccount.enabled=true). Nothing to upload and no password to keep: the way to
 * connect to an OpenShift cluster whose console only signs in through SSO.
 */
const InClusterServiceAccountSection: React.FC<Props> = ({ onConnected }) => {
    const [status, setStatus] = React.useState<InClusterServiceAccountStatus | null>(null);
    const [loadError, setLoadError] = React.useState<string | null>(null);
    const [connecting, setConnecting] = React.useState(false);

    const load = React.useCallback(async () => {
        try {
            setStatus(await getInClusterServiceAccount());
            setLoadError(null);
        } catch (e) {
            setLoadError(serverReason(e, "Could not check the Ambari pod's ServiceAccount."));
        }
    }, []);

    React.useEffect(() => { void load(); }, [load]);

    const connect = async () => {
        setConnecting(true);
        try {
            const res = await connectWithInClusterServiceAccount();
            message.success(res?.message || "KDPS now connects with the Ambari pod's ServiceAccount.");
            onConnected?.();
        } catch (e) {
            message.error(serverReason(e, "Could not connect with the Ambari pod's ServiceAccount."), 10);
        } finally {
            setConnecting(false);
            // Saved even when the cluster check failed, as for the other modes: show what is in use now.
            void load();
        }
    };

    return (
        <div>
            <Title level={5} style={{ marginTop: 0 }}>Ambari's ServiceAccount — Ambari runs in this cluster</Title>
            <Paragraph type="secondary" style={{ marginBottom: 12 }}>
                When Ambari runs in the cluster it manages (the <code>ambari</code> Helm chart), KDPS can connect with the
                ServiceAccount of its own pod: no kubeconfig to upload and no password to store, and Kubernetes renews the
                token itself. This is the way to connect to an OpenShift cluster whose console only signs in through SSO.
            </Paragraph>
            {status === null && !loadError && <Spin size="small" />}
            {loadError && <Alert type="warning" showIcon message={loadError} />}
            {status && !status.available && (
                <Alert
                    type="info"
                    showIcon
                    message="Not available here"
                    description={status.reason || "Ambari's pod has no ServiceAccount to connect with."}
                />
            )}
            {status && status.available && (
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                    <Descriptions
                        size="small"
                        column={1}
                        style={{ maxWidth: 620 }}
                        items={[
                            {
                                key: 'identity',
                                label: 'Connects as',
                                children: <Text code>{status.username || `ServiceAccount of namespace ${status.namespace ?? '?'}`}</Text>,
                            },
                            { key: 'apiServer', label: 'API server', children: <Text>{status.apiServer}</Text> },
                        ]}
                    />
                    <Space>
                        <Button type="primary" icon={<SafetyCertificateOutlined />} loading={connecting} onClick={connect}>
                            {status.active ? 'Reconnect' : 'Connect with this ServiceAccount'}
                        </Button>
                        {status.active && <Tag color="green" icon={<CheckCircleOutlined />}>In use</Tag>}
                    </Space>
                    <Text type="secondary">
                        Grant this ServiceAccount the rights to deploy in each project KDPS deploys to: on OpenShift, the
                        admin and monitoring-edit roles. Cluster prerequisites, below, checks them.
                    </Text>
                </Space>
            )}
        </div>
    );
};

export default InClusterServiceAccountSection;
