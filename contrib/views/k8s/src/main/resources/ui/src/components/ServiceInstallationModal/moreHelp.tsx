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
import { Tooltip } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';

/** A form field (or group) whose help has details: `help` is the line under it, `helpMore` the rest. */
type WithMoreHelp = { label?: string; helpMore?: string };

const tooltipText = (text: string) => <span style={{ whiteSpace: 'pre-line' }}>{text}</span>;
const TOOLTIP_STYLES = { root: { maxWidth: 440 } };

/** The `tooltip` of a Form.Item: the field's `helpMore` behind an ⓘ next to its label, or nothing. */
export function moreHelpTooltip(field: WithMoreHelp | undefined) {
  return field?.helpMore ? { title: tooltipText(field.helpMore), styles: TOOLTIP_STYLES } : undefined;
}

/** The same icon as a Form.Item tooltip's, for a label the Form.Item does not render: a checkbox's, a group's. Clicking it toggles nothing. */
export function MoreHelpIcon({ field }: { field: WithMoreHelp | undefined }) {
  if (!field?.helpMore) return null;
  return (
    <Tooltip title={tooltipText(field.helpMore)} styles={TOOLTIP_STYLES}>
      <QuestionCircleOutlined
        role="img"
        tabIndex={0}
        aria-label={`More about ${field.label || 'this field'}`}
        style={{ marginLeft: 6, color: 'inherit', opacity: 0.65, cursor: 'help' }}
        onClick={(e) => { e.preventDefault(); e.stopPropagation(); }}
      />
    </Tooltip>
  );
}
