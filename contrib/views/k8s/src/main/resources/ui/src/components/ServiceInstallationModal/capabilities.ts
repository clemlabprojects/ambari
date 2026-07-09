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
import { getClusterCapabilities, type ClusterCapabilities } from '../../api/client';

// Module-level memoization: the capability probe is the same for every form/field on the
// page, so a single fetch is enough — and we cache the Promise so concurrent first renders
// don't fan out into N network calls. Refresh on full reload (or when /cluster/capabilities
// is invalidated server-side, which already happens on a 60s TTL). `lastResolved` holds the
// last successfully-resolved value so non-hook callers (e.g. a submit handler building the
// deploy values) can read the detected platform synchronously.
let capabilitiesPromise: Promise<ClusterCapabilities> | null = null;
let lastResolved: ClusterCapabilities | undefined;

/** Shared capabilities hook: one cached probe drives OpenShift/CRD-aware field gating and
 *  the platform-conditional binding engine alike. */
export const useCapabilities = (): ClusterCapabilities | undefined => {
  const [caps, setCaps] = React.useState<ClusterCapabilities | undefined>(lastResolved);
  React.useEffect(() => {
    if (!capabilitiesPromise) capabilitiesPromise = getClusterCapabilities();
    capabilitiesPromise
      .then(c => { lastResolved = c; setCaps(c); })
      .catch(() => {
        // On error leave caps undefined; consumers fail-open (or fail-closed for the
        // OpenShift gate). Reset the cached promise so a future render can retry.
        capabilitiesPromise = null;
      });
  }, []);
  return caps;
};

/** The detected platform from the last resolved probe, or undefined if it hasn't resolved.
 *  Lets non-hook code (deploy/preview value builders) branch bindings on the platform. */
export const cachedPlatform = (): string | undefined => lastResolved?.platform;

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
  // `kubernetes` is the inverse of `openshift` and fail-OPEN: an nginx-Ingress-only field (class,
  // annotations, proxy buffers) shows everywhere EXCEPT a confirmed OpenShift cluster — on OpenShift
  // the platform router/Route owns host+port+TLS+tuning, so those fields are hidden as irrelevant.
  if (cap === 'kubernetes') return caps?.platform !== 'openshift';
  if (!caps) return true; // fail-open while loading
  if (cap === 'certManager') return !!caps.certManager?.installed;
  if (cap === 'externalSecrets') return !!caps.externalSecrets?.installed;
  return true;
};
