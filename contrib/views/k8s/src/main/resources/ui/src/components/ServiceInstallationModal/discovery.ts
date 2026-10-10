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
import { formatLabels } from './bindings';

/**
 * The discovery result behind a picker choice. The picker hands over either the option's value (a JSON string)
 * or the whole option ({ label, value }), depending on how it was chosen; both are accepted.
 */
export const parseDiscoveryChoice = (choice: any): any => {
  const raw = choice && typeof choice === 'object' && 'value' in choice ? choice.value : choice;
  if (raw == null || raw === '') return undefined;
  if (typeof raw !== 'string') return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return undefined;
  }
};

/**
 * The monitoring form fields a discovered Prometheus fills. Only its in-cluster address (`queryUrl`) goes into the
 * deploy: an external one (an Ambari property, for the view itself) is left out.
 */
export const monitoringFieldsFromDiscovery = (found: any, current: any = {}): any => ({
  ...current,
  namespace: found.namespace,
  release: found.release,
  url: found.queryUrl || '',
  serviceMonitorLabels: formatLabels(found.serviceMonitorLabels),
});
