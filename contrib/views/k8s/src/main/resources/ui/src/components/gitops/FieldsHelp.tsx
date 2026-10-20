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
import { Button, Drawer, Space, Table, Tooltip, Typography } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import type { GitOpsProfile } from '../../api/client';
import type { ExampleDeploy } from './virtualRepository';

/** A field a path or a template can use, and its value for the example deploy when the page knows it. */
export interface FieldRow {
  field: string;
  holds: string;
  example?: string;
}

/** The fields KDPS fills in for every deploy, then the profile's own variables. */
export function fieldRows(profile: GitOpsProfile, example: ExampleDeploy): FieldRow[] {
  const rows: FieldRow[] = [
    { field: 'release', holds: 'The name of the release.', example: example.release },
    { field: 'namespace', holds: 'The namespace of the release. A path naming it gives one file per environment.', example: example.namespace },
    { field: 'service', holds: 'The KDPS service deployed, such as TRINO or SUPERSET.' },
    { field: 'chart.name', holds: 'The chart deployed, as its repository names it.', example: example.chart },
    { field: 'chart.version', holds: 'Its version.', example: example.version || undefined },
    { field: 'chart.repository', holds: 'The chart repository, as oci://… or https://….' },
    { field: 'chartPath', holds: 'The folder the profile copies the chart to.', example: profile.chartPath || undefined },
    { field: 'values', holds: 'The chart values of the deploy, passwords and secrets left out. A map: write it with toYaml or toJson.' },
    { field: 'secretValues.secretName', holds: 'The Kubernetes Secret holding the passwords and secrets of the deploy, for the tool deploying the chart to add to its values.',
      example: example.release ? `${example.release}-kdps-values` : undefined },
    { field: 'secretValues.key', holds: 'The key of the values document in that Secret.', example: 'values.yaml' },
    { field: 'images', holds: 'The container images the values name. A list: write it with toYaml or toJson.' },
    { field: 'instances', holds: 'Every namespace the repository holds an instance of the release in, this one included. A list.' },
    { field: 'deployedBy', holds: 'The Ambari user who deployed.' },
    { field: 'timestamp', holds: 'When the deploy ran, as an ISO 8601 time.' },
  ];
  Object.entries(profile.variables || {})
    .filter(([name]) => name.trim())
    .forEach(([name, value]) => rows.push({ field: `vars.${name.trim()}`, holds: 'A variable of this profile (Settings).', example: value }));
  return rows;
}

const FILTERS: Array<[string, string]> = [
  ['{{ values | toYaml }}', 'A map or a list as YAML.'],
  ['{{ instances | toJson }}', 'As JSON, on one line.'],
  ['{{ release | quote }}', 'As a quoted string: "trino".'],
  ['{{ vars.team | trim }}', 'Without spaces around it.'],
  ['{{ values | toYaml | indent 4 }}', 'Every line indented by 4 spaces, to nest it under a key.'],
  ['{{ vars.team | default "data" }}', 'A value used when the field is empty.'],
];

interface Props {
  profile: GitOpsProfile;
  example: ExampleDeploy;
  /** Inserts a field at the cursor of the template being edited; omitted when no file is open. */
  onInsertTemplate?: (text: string) => void;
  /** Appends a field to the path of the file being edited. */
  onInsertPath?: (text: string) => void;
  /** The button: an icon only, or with its label. */
  compact?: boolean;
}

/** A button opening the reference of the fields and filters a GitOps profile's paths and templates can use. */
const FieldsHelp: React.FC<Props> = ({ profile, example, onInsertTemplate, onInsertPath, compact }) => {
  const [open, setOpen] = React.useState(false);
  const insert = (field: string) => `{{ ${field} }}`;
  return (
    <>
      <Tooltip title="The fields and filters paths and templates can use">
        <Button icon={<QuestionCircleOutlined />} onClick={() => setOpen(true)} type={compact ? 'text' : 'default'}>
          {compact ? null : 'Fields and filters'}
        </Button>
      </Tooltip>
      <Drawer open={open} onClose={() => setOpen(false)} width={720} title="Fields and filters">
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Paragraph style={{ marginBottom: 0 }}>
            KDPS fills these fields in for every deploy. Use them between double braces, in a file's path as in its
            content: <Typography.Text code>{'environments/{{ namespace }}/values.yaml'}</Typography.Text>. A field
            or filter KDPS does not know stops the preview and the deploy, naming the file and the line.
          </Typography.Paragraph>
          <Table
            size="small"
            rowKey="field"
            pagination={false}
            dataSource={fieldRows(profile, example)}
            columns={[
              { title: 'Field', dataIndex: 'field', width: 210, render: (f: string) => <Typography.Text code copyable={{ text: insert(f) }}>{f}</Typography.Text> },
              { title: 'What it holds', dataIndex: 'holds' },
              { title: 'Example', dataIndex: 'example', width: 140, render: (v?: string) => (v ? <Typography.Text type="secondary">{v}</Typography.Text> : null) },
              ...(onInsertTemplate || onInsertPath ? [{
                title: '', key: 'insert', width: 150,
                render: (_: unknown, r: FieldRow) => (
                  <Space size={4}>
                    {onInsertTemplate && <Button size="small" onClick={() => { onInsertTemplate(insert(r.field)); setOpen(false); }}>Template</Button>}
                    {onInsertPath && <Button size="small" onClick={() => { onInsertPath(insert(r.field)); setOpen(false); }}>Path</Button>}
                  </Space>
                ),
              }] : []),
            ]}
          />
          <div>
            <Typography.Title level={5}>Filters</Typography.Title>
            <Typography.Paragraph type="secondary">Written after a field, separated by a bar, one after the other.</Typography.Paragraph>
            {FILTERS.map(([example_, what]) => (
              <div key={example_} style={{ display: 'flex', gap: 12, padding: '4px 0' }}>
                <Typography.Text code style={{ flex: '0 0 300px' }}>{example_}</Typography.Text>
                <Typography.Text>{what}</Typography.Text>
              </div>
            ))}
          </div>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            Text that is not between double braces is written as it is, such as{' '}
            <Typography.Text code>{'${IMAGE_TAG}'}</Typography.Text> for another tool to fill in.
          </Typography.Paragraph>
        </Space>
      </Drawer>
    </>
  );
};

export default FieldsHelp;
