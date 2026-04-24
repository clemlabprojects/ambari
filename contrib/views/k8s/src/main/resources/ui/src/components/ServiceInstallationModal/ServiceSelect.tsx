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
import { Form, Select, AutoComplete, Button, Divider, Space, Typography } from 'antd';
import { PlusOutlined, SettingOutlined } from '@ant-design/icons';
import type { ClusterService } from '../../types/ServiceTypes';
import { getClusterServices, getDiscoveredK8sServices, getMonitoringDiscovery } from '../../api/client';
import { useNavigate } from 'react-router-dom';

const { Option } = Select;
const { Text } = Typography;

type ServiceSelectProps = { field: any; onValueSelect?: (value: any) => void };

const ServiceSelect: React.FC<ServiceSelectProps> = ({ field, onValueSelect }) => {
  const [services, setServices] = useState<ClusterService[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const navigate = useNavigate();

  // Parse lookupLabel to see if this is a configuration lookup
  // e.g. "ambari.clemlab.com/config-type=superset-python"
  const isConfigLookup = field.lookupLabel?.includes('config-type');

  const fetchServices = () => {
    setIsLoading(true);
    let promise;
    if (field.type === 'service-select' || field.type === 'hadoop-discovery') {
      if (!field.serviceType) {
        console.warn('service-select called without serviceType');
        setServices([]);
        setIsLoading(false);
        return;
      }
      promise = getClusterServices(field.serviceType);
    } else if (field.type === 'monitoring-discovery') {
      promise = getMonitoringDiscovery().then((res) => {
        if (!res) return [];
        return [{ label: `${res.release} (${res.namespace})`, value: JSON.stringify(res) }];
      }).catch(() => []);
    } else {
      if (!field.lookupLabel) {
        console.warn('k8s-discovery called without lookupLabel');
        setServices([]);
        setIsLoading(false);
        return;
      }
      promise = getDiscoveredK8sServices(field.lookupLabel);
    }

    promise
        .then(setServices)
        .catch(console.error)
        .finally(() => setIsLoading(false));
  };

  useEffect(() => {
    fetchServices();
  }, [field.lookupLabel, field.serviceType, field.type]);

  // Handler for "Create New" button
  const handleCreateNew = () => {
    // We navigate to the management page.
    // Ideally, pass state to open the modal automatically.
    navigate('/managed-configs', {
      state: {
        openCreate: true,
        // Try to guess type from label if possible, or let user pick
        suggestedType: isConfigLookup ? extractConfigType(field.lookupLabel) : undefined
      }
    });
  };

  const nameParts = field.name.replace(/\\\./g, '__DOT__').split('.').map((p: string) => p.replace(/__DOT__/g, '.'));

  // freeform: true → AutoComplete (discovered options as suggestions + free text input)
  if (field.freeform) {
    return (
      <Form.Item name={nameParts} label={field.label} help={field.help}>
        <AutoComplete
          options={services.map(s => ({ label: s.label, value: s.value }))}
          allowClear
          placeholder={isLoading ? 'Discovering...' : 'http://host:8181'}
          onChange={(val) => { if (onValueSelect) onValueSelect(val); }}
        />
      </Form.Item>
    );
  }

  return (
      <Form.Item name={nameParts} label={field.label} help={field.help}>
        <Select
            loading={isLoading}
            allowClear
            placeholder={isLoading ? 'Loading...' : 'Select...'}
            onChange={(val) => {
              if (onValueSelect) {
                const svc = services.find(s => s.value === val);
                onValueSelect(svc ?? val);
              }
            }}
            dropdownRender={(menu) => (
                <>
                  {menu}
                  {isConfigLookup && (
                      <>
                        <Divider style={{ margin: '8px 0' }} />
                        <Space style={{ padding: '0 8px 4px' }}>
                          <Button type="text" block icon={<PlusOutlined />} onClick={handleCreateNew}>
                            Create New Profile
                          </Button>
                        </Space>
                      </>
                  )}
                </>
            )}
        >
          {services.map(s => (
              <Option key={s.value} value={s.value}>
                {/* Show "Default" tag if applicable */}
                <Space>
                  {s.label}
                  {s.label.toLowerCase().includes('default') && <Text type="secondary" style={{fontSize: 10}}>(Default)</Text>}
                </Space>
              </Option>
          ))}
        </Select>
      </Form.Item>
  );
};

// Helper to extract "superset-python" from "ambari.../config-type=superset-python"
function extractConfigType(labelSelector: string): string | undefined {
  const match = labelSelector.match(/config-type=([^,]+)/);
  return match ? match[1] : undefined;
}

export default ServiceSelect;
