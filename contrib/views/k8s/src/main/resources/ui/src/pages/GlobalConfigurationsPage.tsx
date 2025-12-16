import React, { useState, useEffect } from 'react';
import { Table, Button, Modal, Form, Input, Select, Space, message, Tag, Typography, Popconfirm, Alert } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined, LockOutlined } from '@ant-design/icons';
import Editor from '@monaco-editor/react';
import { listManagedConfigs, saveManagedConfig, deleteManagedConfig, getManagedConfigContent } from '../api/client';
import type { ManagedConfig } from '../api/client';
import { useLocation } from 'react-router-dom';

const { Title, Text } = Typography;
const { Option } = Select;

// Templates for "Create New"
const CONFIG_TEMPLATES: Record<string, Partial<ManagedConfig>> = {
    'superset-python': {
        filename: 'superset_config.py',
        language: 'python',
        content: `# Superset Configuration Override
import os
from flask_appbuilder.security.manager import AUTH_DB

# Uncomment to enable OAuth
# from flask_appbuilder.security.manager import AUTH_OAUTH
# AUTH_TYPE = AUTH_OAUTH
# OAUTH_PROVIDERS = []

ROW_LIMIT = 5000
`
    },
    'trino-properties': {
        filename: 'config.properties',
        language: 'ini',
        content: `coordinator=true
node-scheduler.include-coordinator=true
http-server.http.port=8080
query.max-memory=5GB
`
    }
};

const GlobalConfigurationsPage: React.FC = () => {
    const [configs, setConfigs] = useState<ManagedConfig[]>([]);
    const [loading, setLoading] = useState(false);
    const [isModalVisible, setIsModalVisible] = useState(false);
    const [editorContent, setEditorContent] = useState('');
    const [currentLanguage, setCurrentLanguage] = useState('plaintext');
    const [editingConfig, setEditingConfig] = useState<ManagedConfig | null>(null);
    const [form] = Form.useForm();
    const location = useLocation();

    const fetchConfigs = async () => {
        setLoading(true);
        try {
            const data = await listManagedConfigs();
            setConfigs(data);
        } catch (error) {
            message.error('Failed to load configurations');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchConfigs();
        // Handle "Create New" navigation from ServiceSelect
        if (location.state && (location.state as any).openCreate) {
            handleOpenCreate((location.state as any).suggestedType);
            // clear state to prevent reopening on refresh
            window.history.replaceState({}, document.title);
        }
    }, [location]);

    const handleOpenCreate = (suggestedType?: string) => {
        setEditingConfig(null);
        form.resetFields();

        const type = suggestedType || 'superset-python';
        const template = CONFIG_TEMPLATES[type] || CONFIG_TEMPLATES['superset-python'];

        form.setFieldsValue({
            namespace: 'dashboarding', // Default
            type: type,
            filename: template.filename,
            language: template.language
        });
        setEditorContent(template.content || '');
        setCurrentLanguage(template.language || 'plaintext');
        setIsModalVisible(true);
    };

    const handleEdit = async (record: ManagedConfig) => {
        setEditingConfig(record);
        try {
            const content = await getManagedConfigContent(record.namespace, record.name, record.filename);
            form.setFieldsValue(record);
            setEditorContent(content);
            setCurrentLanguage(record.language || 'plaintext');
            setIsModalVisible(true);
        } catch (e) {
            message.error("Could not load file content");
        }
    };

    const handleSave = async () => {
        try {
            const values = await form.validateFields();
            await saveManagedConfig({
                ...values,
                content: editorContent
            });
            message.success('Configuration saved');
            setIsModalVisible(false);
            fetchConfigs();
        } catch (e) {
            message.error('Save failed');
        }
    };

    const handleTypeChange = (type: string) => {
        const template = CONFIG_TEMPLATES[type];
        if (template) {
            form.setFieldsValue({
                filename: template.filename,
                language: template.language
            });
            setCurrentLanguage(template.language || 'plaintext');
            if (!editorContent && !editingConfig) {
                setEditorContent(template.content || '');
            }
        }
    };

    const columns = [
        { title: 'Name', dataIndex: 'name', key: 'name', render: (text: string) => <b>{text}</b> },
        { title: 'Namespace', dataIndex: 'namespace', key: 'namespace' },
        {
            title: 'Type',
            dataIndex: 'type',
            key: 'type',
            render: (type: string) => <Tag color="blue">{type}</Tag>
        },
        {
            title: 'Filename',
            dataIndex: 'filename',
            key: 'filename',
            render: (text: string, r: ManagedConfig) => (
                <span>{text} {r.isDefault && <Tag icon={<LockOutlined />} color="warning">Default</Tag>}</span>
            )
        },
        { title: 'Description', dataIndex: 'description', key: 'description', ellipsis: true },
        {
            title: 'Actions',
            key: 'actions',
            render: (_: any, record: ManagedConfig) => (
                <Space>
                    <Button icon={<EditOutlined />} onClick={() => handleEdit(record)}>Edit</Button>
                    {!record.isDefault && (
                        <Popconfirm title="Delete?" onConfirm={() => deleteManagedConfig(record.name, record.namespace).then(fetchConfigs)}>
                            <Button danger icon={<DeleteOutlined />} />
                        </Popconfirm>
                    )}
                </Space>
            ),
        },
    ];

    return (
        <div>
            <div className="page-header" style={{display: 'flex', justifyContent: 'space-between', marginBottom: 16}}>
                <Title level={3}>Configuration Profiles</Title>
                <Space>
                    <Button icon={<ReloadOutlined />} onClick={fetchConfigs}>Refresh</Button>
                    <Button type="primary" icon={<PlusOutlined />} onClick={() => handleOpenCreate()}>
                        Create Profile
                    </Button>
                </Space>
            </div>

            <Alert
                message="Config Profiles"
                description="Create reusable configuration files (like OAuth settings or database properties) here. You can select them during Helm chart installation."
                type="info"
                showIcon
                style={{ marginBottom: 16 }}
            />

            <Table
                columns={columns}
                dataSource={configs}
                rowKey={(r) => r.namespace + '/' + r.name}
                loading={loading}
                pagination={{ pageSize: 10 }}
            />

            <Modal
                title={editingConfig ? `Edit ${editingConfig.name}` : "Create Configuration Profile"}
                open={isModalVisible}
                onOk={handleSave}
                onCancel={() => setIsModalVisible(false)}
                width={900}
                maskClosable={false}
                okText="Save Profile"
            >
                <Form form={form} layout="vertical">
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }}>
                        <Form.Item name="name" label="Profile Name" rules={[{ required: true }]}>
                            <Input placeholder="e.g. corp-sso-config" disabled={!!editingConfig} />
                        </Form.Item>
                        <Form.Item name="namespace" label="Namespace" rules={[{ required: true }]}>
                            <Input placeholder="dashboarding" disabled={!!editingConfig} />
                        </Form.Item>
                        <Form.Item name="type" label="Type" rules={[{ required: true }]}>
                            <Select onChange={handleTypeChange}>
                                <Option value="superset-python">Superset (Python)</Option>
                                <Option value="trino-properties">Trino (Properties)</Option>
                                <Option value="generic">Generic File</Option>
                            </Select>
                        </Form.Item>
                    </div>

                    <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 16 }}>
                        <Form.Item name="filename" label="Target Filename (in container)" rules={[{ required: true }]}>
                            <Input />
                        </Form.Item>
                        <Form.Item name="language" label="Syntax">
                            <Select onChange={setCurrentLanguage}>
                                <Option value="python">Python</Option>
                                <Option value="ini">Properties / INI</Option>
                                <Option value="yaml">YAML</Option>
                                <Option value="json">JSON</Option>
                                <Option value="xml">XML</Option>
                            </Select>
                        </Form.Item>
                    </div>

                    <Form.Item name="description" label="Description">
                        <Input.TextArea rows={1} />
                    </Form.Item>

                    <Text strong>Content</Text>
                    <div style={{ border: '1px solid #d9d9d9', marginTop: 8 }}>
                        <Editor
                            height="350px"
                            language={currentLanguage}
                            value={editorContent}
                            onChange={(value) => setEditorContent(value || '')}
                            options={{ minimap: { enabled: false }, scrollBeyondLastLine: false }}
                        />
                    </div>
                </Form>
            </Modal>
        </div>
    );
};

export default GlobalConfigurationsPage;
