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
import { Card, Modal, Typography } from 'antd';
import { CATALOG_TEMPLATES, type CatalogTemplate } from './trinoCatalogTemplates';

const { Text } = Typography;

/**
 * Connector logos as small inline SVG marks (no external asset fetch: the view may run on an
 * air-gapped Ambari). Shapes evoke each product; colours follow the usual brand palettes.
 */
export function ConnectorLogo({ id, size = 40 }: { id: string; size?: number }) {
  const common = { width: size, height: size, viewBox: '0 0 48 48', role: 'img' as const, 'aria-label': id };
  switch (id) {
    case 'postgresql': // elephant-blue roundel with the "Pg" mark
      return (<svg {...common}><circle cx="24" cy="24" r="22" fill="#336791" /><text x="24" y="30" textAnchor="middle" fontFamily="Helvetica, Arial, sans-serif" fontWeight="700" fontSize="18" fill="#fff">Pg</text></svg>);
    case 'mysql': // dolphin teal/orange
      return (<svg {...common}><circle cx="24" cy="24" r="22" fill="#00618A" /><path d="M12 30c4-10 12-14 24-10-6 1-10 4-12 9-3 1-6 1-12 1z" fill="#E48E00" /><circle cx="33" cy="20" r="1.6" fill="#fff" /></svg>);
    case 's3': // bucket
      return (<svg {...common}><circle cx="24" cy="24" r="22" fill="#1F7A3F" /><path d="M14 16h20l-3 18H17z" fill="#8CC84B" /><ellipse cx="24" cy="16" rx="10" ry="3" fill="#E6F4EA" /></svg>);
    case 'hdfs': // yellow elephant-ish block "HDFS"
      return (<svg {...common}><circle cx="24" cy="24" r="22" fill="#F2B705" /><rect x="12" y="18" width="24" height="14" rx="3" fill="#2D2D2D" /><text x="24" y="28.5" textAnchor="middle" fontFamily="Helvetica, Arial, sans-serif" fontWeight="700" fontSize="9" fill="#F2B705">HDFS</text></svg>);
    case 'impala': // Cloudera-orange antelope horns
      return (<svg {...common}><circle cx="24" cy="24" r="22" fill="#F96702" /><path d="M16 14c0 8 4 12 8 12s8-4 8-12" stroke="#fff" strokeWidth="3" fill="none" strokeLinecap="round" /><circle cx="24" cy="31" r="4" fill="#fff" /></svg>);
    default: // generic connector: plug
      return (<svg {...common}><circle cx="24" cy="24" r="22" fill="#5B6B7F" /><rect x="18" y="12" width="4" height="8" rx="1" fill="#fff" /><rect x="26" y="12" width="4" height="8" rx="1" fill="#fff" /><path d="M15 20h18v6a9 9 0 0 1-18 0z" fill="#fff" /><rect x="22" y="33" width="4" height="5" fill="#fff" /></svg>);
  }
}

/**
 * Starburst-style connector picker: one card per connector (logo, name, tagline). Picking a card
 * hands the template to the catalog form. Same connector list as the templates module.
 */
export default function ConnectorGallery({ open, onPick, onClose, templates = CATALOG_TEMPLATES }: {
  open: boolean; onPick: (t: CatalogTemplate) => void; onClose: () => void; templates?: CatalogTemplate[];
}) {
  return (
    <Modal title="Add a connector" open={open} onCancel={onClose} footer={null} width={860} destroyOnClose>
      <Text type="secondary">Pick the kind of data source. You will fill its connection details next; the result is an ordinary Trino catalog you can also edit as raw properties.</Text>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 12, marginTop: 16 }} data-testid="connector-gallery">
        {templates.map((t) => (
          <Card
            key={t.id}
            hoverable
            size="small"
            onClick={() => onPick(t)}
            role="button"
            aria-label={`connector ${t.label}`}
            styles={{ body: { display: 'flex', gap: 12, alignItems: 'center', padding: 14 } }}
          >
            <ConnectorLogo id={t.id} />
            <div style={{ minWidth: 0 }}>
              <div style={{ fontWeight: 600 }}>{t.label}</div>
              <Text type="secondary" style={{ fontSize: 12 }}>{t.tagline}</Text>
            </div>
          </Card>
        ))}
      </div>
    </Modal>
  );
}
