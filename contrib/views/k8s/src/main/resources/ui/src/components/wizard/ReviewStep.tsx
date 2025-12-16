import React from 'react';
import { Descriptions, Typography, Card } from 'antd';

type Props = {
  def: any;
  install: Record<string, any>;
  repoLabel?: string;
};

const ReviewStep: React.FC<Props> = ({ def, install, repoLabel }) => {
  return (
    <Card bordered>
      <Typography.Title level={4}>Review</Typography.Title>
      <Descriptions column={1} bordered size="small">
        <Descriptions.Item label="Service">{def?.label || def?.name}</Descriptions.Item>
        <Descriptions.Item label="Chart">{def?.chart}</Descriptions.Item>
        <Descriptions.Item label="Release name">{install?.releaseName}</Descriptions.Item>
        <Descriptions.Item label="Namespace">{install?.namespace}</Descriptions.Item>
        {repoLabel && <Descriptions.Item label="Repository">{repoLabel}</Descriptions.Item>}
      </Descriptions>
    </Card>
  );
};

export default ReviewStep;
