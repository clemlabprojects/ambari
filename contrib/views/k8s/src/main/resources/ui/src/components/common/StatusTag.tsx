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
      return <Tag icon={<CheckCircleTwoTone twoToneColor="#52c41a" />} color="success">Déployé</Tag>;
    case 'pending_upgrade':
      return <Tag icon={<WarningTwoTone twoToneColor="#faad14" />} color="warning">En attente</Tag>;
    case 'failed':
    case 'NotReady':
      return <Tag icon={<CloseCircleTwoTone twoToneColor="#ff4d4f" />} color="error">Échoué</Tag>;
    case 'uninstalling':
      return <Tag icon={<SyncOutlined spin />} color="processing">Suppression...</Tag>;
    case 'SchedulingDisabled':
       return <Tag color="default">Désactivé</Tag>;
    default:
      return <Tag>{status}</Tag>;
  }
});

StatusTag.displayName = 'StatusTag';

export default StatusTag;

