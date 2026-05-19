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
import { Select, Tooltip } from 'antd';
import { ApartmentOutlined } from '@ant-design/icons';
import { useNamespace } from '../../context/NamespaceContext';
import { getNamespaces } from '../../api/client';
import { useClusterStatus } from '../../context/ClusterStatusContext';

/**
 * Topbar namespace selector. Lists all namespaces from the cluster and
 * persists the selection in localStorage via NamespaceContext. The literal
 * value "*" means "all namespaces" — pages that respect it should treat it
 * as no filter. Hidden when the cluster is unconfigured/disconnected because
 * the namespace list call would fail noisily.
 */
const NamespaceSelector: React.FC = () => {
  const { status } = useClusterStatus();
  const { namespace, setNamespace } = useNamespace();
  const [namespaces, setNamespaces] = React.useState<string[]>([]);
  const [loading, setLoading] = React.useState(false);

  React.useEffect(() => {
    if (status !== 'connected') { setNamespaces([]); return; }
    setLoading(true);
    getNamespaces()
      .then(ns => setNamespaces((ns || []).map((n: any) => n.name).filter(Boolean).sort()))
      .catch(() => setNamespaces([]))
      .finally(() => setLoading(false));
  }, [status]);

  if (status !== 'connected') return null;

  const options = [
    { value: '*', label: 'All namespaces' },
    ...namespaces.map(n => ({ value: n, label: n })),
  ];

  return (
    <Tooltip title="Default namespace for workload-scoped pages">
      <Select
        size="small"
        showSearch
        loading={loading}
        value={namespace}
        onChange={setNamespace}
        options={options}
        suffixIcon={<ApartmentOutlined />}
        style={{ minWidth: 180 }}
        popupMatchSelectWidth={240}
        filterOption={(input, option) =>
          (option?.label as string).toLowerCase().includes(input.toLowerCase())
        }
        aria-label="namespace selector"
      />
    </Tooltip>
  );
};

export default NamespaceSelector;
