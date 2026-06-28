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
import type { ClusterCapabilities } from '../../api/client';

/**
 * Pure field/option capability gate used by DynamicFormField. A field (or a select
 * option) names a required `capability`; it renders only when that capability is
 * present on the cluster.
 *
 * - `openshift` gates on the detected platform and is fail-CLOSED: an OpenShift-only
 *   field stays hidden until the probe confirms platform==="openshift", so it never
 *   flashes on vanilla Kubernetes while capabilities are still loading.
 * - The installed-CRD capabilities (`certManager`, `externalSecrets`) stay fail-OPEN
 *   while caps are loading, preserving the original behaviour.
 * - An unknown capability defaults to visible.
 */
export const fieldCapabilityAvailable = (
  cap: string | undefined,
  caps: ClusterCapabilities | undefined
): boolean => {
  if (!cap) return true;
  if (cap === 'openshift') return caps?.platform === 'openshift';
  if (!caps) return true; // fail-open while loading
  if (cap === 'certManager') return !!caps.certManager?.installed;
  if (cap === 'externalSecrets') return !!caps.externalSecrets?.installed;
  return true;
};
