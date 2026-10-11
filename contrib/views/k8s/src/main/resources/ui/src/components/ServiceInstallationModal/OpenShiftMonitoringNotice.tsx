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
import { Alert } from 'antd';
import { getMonitoringDiscovery } from '../../api/client';

/** What to tell the operator about OpenShift monitoring, from whether user workload monitoring is on. */
export const openShiftMonitoringNotice = (userWorkloadMonitoring: boolean | null | undefined): {
  type: 'info' | 'warning' | 'success'; message: string; description: string;
} => {
  const grant = 'The account KDPS connects with also needs the monitoring-edit role in the release\'s project '
    + '(oc policy add-role-to-user monitoring-edit <account> -n <project>).';
  if (userWorkloadMonitoring === false) {
    return {
      type: 'warning',
      message: 'User workload monitoring is off on this cluster',
      description: 'The service\'s metrics will not be collected, so worker autoscaling will not scale. A cluster '
        + 'administrator turns it on with enableUserWorkload: true in the openshift-monitoring/cluster-monitoring-config '
        + 'ConfigMap. ' + grant,
    };
  }
  if (userWorkloadMonitoring === true) {
    return {
      type: 'success',
      message: 'OpenShift user workload monitoring is on',
      description: 'The service\'s metrics are collected by OpenShift monitoring, which worker autoscaling reads. ' + grant,
    };
  }
  return {
    type: 'info',
    message: 'Metrics and autoscaling use OpenShift user workload monitoring',
    description: 'A cluster administrator must have enabled it (enableUserWorkload: true in openshift-monitoring/'
      + 'cluster-monitoring-config); this account cannot check it. ' + grant,
  };
};

/** OpenShift: replaces the Prometheus fields, which do not apply there, with what monitoring needs. */
const OpenShiftMonitoringNotice: React.FC = () => {
  const [state, setState] = React.useState<boolean | null | undefined>(undefined);
  React.useEffect(() => {
    let alive = true;
    getMonitoringDiscovery()
      .then((res: any) => { if (alive) setState(res?.userWorkloadMonitoring ?? null); })
      .catch(() => { if (alive) setState(null); });
    return () => { alive = false; };
  }, []);
  if (state === undefined) return null;
  const notice = openShiftMonitoringNotice(state);
  return <Alert showIcon type={notice.type} message={notice.message} description={notice.description} style={{ marginBottom: 16 }} />;
};

export default OpenShiftMonitoringNotice;
