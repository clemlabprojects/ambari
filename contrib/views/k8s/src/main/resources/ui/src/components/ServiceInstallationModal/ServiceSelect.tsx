import React, { useEffect, useState } from 'react';
import { Form, Select, Spin } from 'antd';
import type { ClusterService } from '../../types/ServiceTypes';
import { getClusterServices } from '../../api/client';

const { Option } = Select;

const ServiceSelect: React.FC<{ field: any }> = ({ field }) => {
  const [services, setServices] = useState<ClusterService[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    getClusterServices(field.serviceType)
      .then(s => !cancelled && setServices(s))
      .finally(() => !cancelled && setIsLoading(false));
    return () => {
      cancelled = true;
    };
  }, [field.serviceType]);

  const nameParts = field.name.replace(/\\\./g, '__DOT__').split('.').map((p: string) => p.replace(/__DOT__/g, '.'));
  return (
    <Form.Item name={nameParts} label={field.label} help={field.help}>
      <Select loading={isLoading} allowClear placeholder={isLoading ? 'Loading…' : 'No service selected'}>
        {services.map(s => (
          <Option key={s.value} value={s.value}>
            {s.label}
          </Option>
        ))}
      </Select>
    </Form.Item>
  );
};

export default ServiceSelect;
