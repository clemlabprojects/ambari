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
