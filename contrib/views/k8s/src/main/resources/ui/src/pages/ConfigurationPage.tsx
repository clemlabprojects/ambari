// ui/src/pages/ConfigurationPage.tsx
import React from 'react';
import { Typography, Card, Upload, Button, Alert, Collapse, Space, Layout, message } from 'antd';
import { UploadOutlined, FileTextOutlined, ApiOutlined } from '@ant-design/icons';
import { usePermissions } from '../hooks/usePermissions';
import { useClusterStatus } from '../context/ClusterStatusContext';
import { useNavigate } from 'react-router-dom';
import './Page.css';
import type { UploadRequestOption as RcCustomRequestOptions } from 'rc-upload/lib/interface';

const { Title, Paragraph, Text } = Typography;
const { Header, Content } = Layout;
const { Panel } = Collapse;

const kubeconfigExample = `
# Example file to be placed on the Ambari server, e.g., in /etc/ambari-views/k8s-view/kubeconfig.yaml
apiVersion: v1
clusters:
- cluster:
    certificate-authority-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0t...
    server: https://your-k8s-api-server:6443
  name: my-cluster
# ... (rest of the file)
`;

const ConfigurationPage: React.FC = () => {
    const { permissions } = usePermissions();
    const { status, fetchData, setClusterStatus } = useClusterStatus();

    const handleCustomRequest = (options: RcCustomRequestOptions) => {
        const { onSuccess, onError, file, onProgress } = options;

        const xhr = new XMLHttpRequest();

        xhr.upload.onprogress = event => {
            if (event.lengthComputable && onProgress) {
                const percent = Math.floor((event.loaded / event.total) * 100);
                onProgress({ percent });
            }
        };

        xhr.onload = () => {
            if (xhr.status >= 200 && xhr.status < 300) {
                if (onSuccess) onSuccess(xhr.responseText, xhr);
            } else {
                if (onError) onError(new Error(`Erreur ${xhr.status}: ${xhr.statusText}`), xhr);
            }
        };
        
        xhr.onerror = () => {
            if (onError) onError(new Error('Failed to upload the kubeconfig yaml'), xhr);
        };

        xhr.open('POST', `/api/v1/views/K8S-VIEW/versions/1.0.0.1/instances/K8S_VIEW_INSTANCE/resources/api/cluster/config`, true);
        xhr.setRequestHeader('X-Requested-By', 'ambari');
        xhr.setRequestHeader('Content-Type', 'application/octet-stream'); // On envoie le fichier comme un flux binaire
        xhr.send(file as Blob); // Le fichier est directement le corps de la requête
    };

    let navigate = useNavigate();
    const uploadProps = {
        name: 'file',
        customRequest: handleCustomRequest,
        showUploadList: true,
        onChange(info: any) {
            if (info.file.status === 'done') {
                message.success(`${info.file.name} successfully uploaded. Reconnecting...`);
                console.log('DEBUG: Upload successful, fetching cluster data...');
                setClusterStatus('connected')
                console.log('DEBUG: Fetching cluster data after upload...');
                navigate("/"); // Redirects to the dashboard after successful uploads
                fetchData();
            } else if (info.file.status === 'error') {
                message.error(`Échec du téléversement de ${info.file.name}.`);
            }
        },
    };

    if (!permissions?.canConfigure) {
        return <Alert message="Insufficient Permissions" description="You do not have the required permissions to configure this view." type="error" showIcon style={{ margin: 24 }} />;
    }

    // A self-contained layout for the configuration page
    return (
        <Layout style={{ minHeight: '100vh', background: '#f0f2f5' }}>
            <Header style={{ background: '#fff', borderBottom: '1px solid #f0f0f0', display: 'flex', alignItems: 'center' }}>
                <ApiOutlined style={{color: '#1677ff', fontSize: '28px'}}/>
                <Title level={3} style={{ margin: '0 0 0 12px' }}>Configuration de la Vue Ambari K8S</Title>
            </Header>
            <Content style={{ padding: '24px', maxWidth: '960px', margin: '0 auto' }}>
                <Card title="Connexion au Cluster Kubernetes">
                    <Space direction="vertical" size="large" style={{ width: '100%' }}>
                        {status === 'unconfigured' && (
                            <Alert
                                message="Configuration requise"
                                description="Veuillez téléverser un fichier kubeconfig valide pour continuer."
                                type="info"
                                showIcon
                            />
                        )}
                        <Paragraph>
                            Téléversez ici le fichier <code>kubeconfig</code> pour permettre à la vue de se connecter
                            à votre cluster Kubernetes ou OpenShift.
                        </Paragraph>
                        
                        <Upload {...uploadProps}>
                            <Button icon={<UploadOutlined />}>Sélectionner le fichier Kubeconfig</Button>
                        </Upload>

                        <Collapse ghost>
                            <Panel 
                                header={<Text strong>Voir un exemple de fichier</Text>} 
                                key="1"
                            >
                                <pre style={{ backgroundColor: '#2b2b2b', color: '#f8f8f2', padding: '16px', borderRadius: '8px' }}>
                                    <code>{kubeconfigExample.trim()}</code>
                                </pre>
                            </Panel>
                        </Collapse>
                    </Space>
                </Card>
            </Content>
        </Layout>
    );
};

export default ConfigurationPage;