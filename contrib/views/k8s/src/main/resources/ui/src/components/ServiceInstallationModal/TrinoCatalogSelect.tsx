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
import { Select, Tag, Typography } from 'antd';
import { getTrinoCatalogs, type TrinoCatalog } from '../../api/client';

/**
 * Multi-select of reusable Trino catalogs (value = catalog ids). Rendered by DynamicFormField for
 * the 'trino-catalog-select' field type (TRINO/service.json customCatalogs.refs). The backend
 * resolves the ids to catalog properties at deploy time (CommandService.applyCustomCatalogs).
 */
export default function TrinoCatalogSelect({ value, onChange, disabled }: {
  value?: string[]; onChange?: (v: string[]) => void; disabled?: boolean;
}) {
  const [catalogs, setCatalogs] = useState<TrinoCatalog[]>([]);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    let alive = true;
    setLoading(true);
    getTrinoCatalogs()
      .then((c) => { if (alive) setCatalogs(c || []); })
      .catch(() => { if (alive) setCatalogs([]); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);
  const known = new Set(catalogs.map((c) => c.id));
  // Ids seeded from a deployed release whose catalog was deleted since: show them so the operator
  // sees what is missing instead of a silently shrunken selection.
  const stale = (value || []).filter((id) => !known.has(id));
  return (
    <Select
      mode="multiple"
      allowClear
      loading={loading}
      disabled={disabled}
      value={value || []}
      onChange={(v) => onChange?.(v as string[])}
      placeholder={catalogs.length ? 'Pick reusable catalogs' : 'No reusable catalog defined yet (Trino Catalogs page)'}
      optionFilterProp="label"
      options={[
        ...catalogs.map((c) => ({
          value: c.id as string,
          label: `${c.name}${c.connectorName ? ` (${c.connectorName})` : ''}`,
        })),
        ...stale.map((id) => ({ value: id, label: `${id} — no longer exists` })),
      ]}
      tagRender={(p) => (
        <Tag closable={p.closable} onClose={p.onClose} color={stale.includes(String(p.value)) ? 'red' : 'blue'} style={{ marginInlineEnd: 4 }}>
          {p.label}
        </Tag>
      )}
      notFoundContent={<Typography.Text type="secondary">Define catalogs on the Trino Catalogs page first</Typography.Text>}
    />
  );
}
