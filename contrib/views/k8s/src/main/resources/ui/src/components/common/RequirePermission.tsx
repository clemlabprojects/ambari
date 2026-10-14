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

// ui/src/components/common/RequirePermission.tsx
import React from 'react';
import { Result } from 'antd';
import { NavLink } from 'react-router-dom';
import PermissionGuard from './PermissionGuard';

type PermissionCheck = 'canWrite' | 'canConfigure';

const DENIED: Record<PermissionCheck, { title: string; subTitle: string }> = {
  canConfigure: {
    title: 'This page is for KDPS administrators',
    subTitle: 'Your account cannot change the KDPS configuration. Ask an Ambari administrator to add you to '
      + "the view's admin users if you need to.",
  },
  canWrite: {
    title: 'Deploying services needs the operator role',
    subTitle: 'Your account has read-only access to KDPS. Ask an Ambari administrator to add you to '
      + "the view's operator users if you need to deploy or change releases.",
  },
};

/**
 * Renders a page only for users whose role allows it, and explains the refusal to the others. The backend refuses
 * the same calls anyway; this keeps a read-only user from landing on a page where every action fails.
 */
const RequirePermission: React.FC<{ requires: PermissionCheck; children: React.ReactNode }> = ({ requires, children }) => (
  <PermissionGuard
    requires={requires}
    fallback={
      <Result
        status="403"
        title={DENIED[requires].title}
        subTitle={DENIED[requires].subTitle}
        extra={<NavLink to="/">Back to the dashboard</NavLink>}
      />
    }
  >
    {children}
  </PermissionGuard>
);

export default RequirePermission;
