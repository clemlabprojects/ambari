/*
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
import * as fs from 'fs';
import * as path from 'path';
import { applyFormDefaults, buildChartValues } from '../chartValues';

/**
 * JupyterHub on OpenShift, run against the REAL JUPYTERHUB/service.json: the user scheduler is off, so the chart
 * renders no ClusterRole or ClusterRoleBinding and an account limited to its own projects can install it. User pods
 * then use the cluster's default scheduler. On plain Kubernetes the chart's default stays.
 */
const def = JSON.parse(
  fs.readFileSync(path.join(__dirname, '../../../../../KDPS/services/JUPYTERHUB/service.json'), 'utf8'),
);

const formValues = (): any => {
  const values: any = {
    releaseName: 'jupyterhub', namespace: 'jupyterhub', jupyterHost: 'jupyter.example.com',
    kerberosHostFqdn: 'jupyterhub.jupyterhub.k8s',
  };
  applyFormDefaults(def.form, values);
  return values;
};

const build = (platform?: string, editorValues?: Record<string, any>) => buildChartValues({
  form: def.form, bindings: def.bindings, variables: def.variables, formValues: formValues(),
  platform, editorValues, chartVersion: def.version,
});

describe('JupyterHub on OpenShift', () => {
  it('turns the user scheduler off, next to the other OpenShift adjustments', () => {
    const values = build('openshift');
    expect(values.scheduling.userScheduler.enabled).toBe(false);
    // Same binding as the other OpenShift adjustments, which stay.
    expect(values.prePuller.hook.enabled).toBe(false);
    expect(values.proxy.service.type).toBe('ClusterIP');
    expect(values.ingress.ingressClassName).toBe('openshift-default');
    // Only the scheduler is turned off; its image settings stay for an operator who turns it back on.
    expect(values.scheduling.userScheduler.image.pullSecrets).toEqual(['registry-credentials']);
  });

  it('keeps the chart default on plain Kubernetes and while the platform is unknown', () => {
    expect(build('kubernetes').scheduling.userScheduler.enabled).toBeUndefined();
    expect(build(undefined).scheduling.userScheduler.enabled).toBeUndefined();
  });

  it('lets an operator turn it back on in the YAML editor', () => {
    const values = build('openshift', { scheduling: { userScheduler: { enabled: true } } });
    expect(values.scheduling.userScheduler.enabled).toBe(true);
  });

  it('declares the same default for deploys that do not run the wizard bindings', () => {
    expect(def.openshiftValueDefaults).toEqual({ 'scheduling.userScheduler.enabled': false });
  });

  it('no longer asks for an SCC for the user scheduler service account', () => {
    const accounts = def.openshiftScc.flatMap((g: any) => g.serviceAccounts);
    expect(accounts).not.toContain('user-scheduler');
    expect(accounts).toContain('hub');
  });
});
