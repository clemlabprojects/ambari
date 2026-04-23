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

// ui/src/components/common/StatusTag.tsx
import React from 'react';
import { Tag } from 'antd';
import { CheckCircleTwoTone, CloseCircleTwoTone, SyncOutlined, WarningTwoTone } from '@ant-design/icons';

type StatusType =
  | 'deployed'
  | 'pending_upgrade'
  | 'failed'
  | 'uninstalling'
  | 'Ready'
  | 'NotReady'
  | 'SchedulingDisabled'
  | 'unknown'
  | 'UNKNOWN';

interface StatusTagProps {
  status: StatusType;
}

const StatusTag: React.FC<StatusTagProps> = React.memo(({ status }) => {
  switch (status) {
    case 'deployed':
    case 'Ready':
      return <Tag icon={<CheckCircleTwoTone twoToneColor="#52c41a" />} color="success">Deployed</Tag>;
    case 'pending_upgrade':
      return <Tag icon={<WarningTwoTone twoToneColor="#faad14" />} color="warning">Pending upgrade</Tag>;
    case 'failed':
    case 'NotReady':
      return <Tag icon={<CloseCircleTwoTone twoToneColor="#ff4d4f" />} color="error">Failed</Tag>;
    case 'uninstalling':
      return <Tag icon={<SyncOutlined spin />} color="processing">Uninstalling…</Tag>;
    case 'SchedulingDisabled':
       return <Tag color="default">Disabled</Tag>;
    default:
      return <Tag>{status}</Tag>;
  }
});

StatusTag.displayName = 'StatusTag';

export default StatusTag;

