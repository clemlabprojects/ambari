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

import type { TruststoreSummary } from '../api/client';

/*
 * The truststores a release trusts, as the deploy wizard sends them: `<namespace>/<name>` of a truststore the
 * Truststores tab lists as managed. A shared one (in the truststore project set in Cluster Settings) may be trusted by
 * any release; a project one only by the releases of its project, and the server refuses it to any other. Releases
 * deployed by earlier versions recorded a bare name, which the server reads from the shared project.
 */

/** Whether a truststore of the Truststores tab is shared: any release may trust it. */
export const isSharedTruststore = (t: Pick<TruststoreSummary, 'managed' | 'scope'>): boolean =>
  !!t.managed && (t.scope ?? 'shared') === 'shared';

/**
 * The truststores a release of `releaseNamespace` may trust: the shared ones, then those of its own project. Without a
 * project yet, the shared ones only.
 */
export const selectableTruststores = (listed: TruststoreSummary[], releaseNamespace?: string): TruststoreSummary[] => [
  ...listed.filter(isSharedTruststore),
  ...(releaseNamespace
    ? listed.filter((t) => t.managed && t.scope === 'project' && t.namespace === releaseNamespace)
    : []),
];

/** The reference of a truststore of the listing. */
export const truststoreRef = (t: Pick<TruststoreSummary, 'namespace' | 'name'>): string => `${t.namespace}/${t.name}`;

/**
 * The recorded truststores of a release, each as the wizard's picker names it when it can be matched: a bare name is
 * replaced by the one shared truststore of that name (with or without its `-truststore` suffix). A Secret of the same
 * name elsewhere, a project truststore included, never matches. One that matches none is kept as recorded, and the
 * server decides.
 */
export const normalizeTruststoreRefs = (refs: string[] | undefined, listed: TruststoreSummary[]): string[] => {
  return (refs || []).map((ref) => {
    if (!ref || ref.includes('/')) return ref;
    const secretName = ref.endsWith('-truststore') ? ref : `${ref}-truststore`;
    const matches = listed.filter((t) => isSharedTruststore(t) && t.name === secretName);
    return matches.length === 1 ? truststoreRef(matches[0]) : ref;
  });
};
