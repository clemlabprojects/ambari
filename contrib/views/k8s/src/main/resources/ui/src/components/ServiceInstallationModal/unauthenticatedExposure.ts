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

import type { ResolvedContext } from '../../api/client';

/**
 * A service published outside the cluster without sign-in.
 *
 * Security profiles are opt-in: a deploy without one (or with a profile whose mode is "none") leaves the chart on its
 * own authentication, which for Trino is none at all (any client names the user it runs as), and for Superset and
 * OpenMetadata only their local accounts. That is fine inside the cluster, but once the Ingress (a Route on OpenShift)
 * is on, anyone who reaches the address gets the same access. The wizard says so and deploys only once the operator
 * confirmed it. A service opts in with service.json `securityCoupling.unauthenticatedExposure`, the sentence saying
 * what that access is.
 */

/** What the wizard tells the operator before deploying a service exposed without sign-in. */
export interface UnauthenticatedExposure {
  title: string;
  lines: string[];
}

const text = (v: unknown): string => (v == null ? '' : String(v).trim());

/**
 * Whether the deploy registers the service with the platform context's identity provider even without a security
 * profile: the service declares an OIDC client, and the context says the server registers clients there
 * (`oidc.clientRegistration`, resolved by the server with the rule the deploy applies: the context carries the
 * Keycloak administrator credentials, or it is the default one and the cluster names a Keycloak administration URL).
 * Users then sign in through single sign-on, so the service is not open. An OIDC issuer alone (Ambari's own single
 * sign-on, say) registers nothing.
 */
export function oidcLoginWithoutProfile(def: any, context: ResolvedContext | null | undefined): boolean {
  const entries = Array.isArray(def?.oidc) ? def.oidc : [];
  if (!entries.some((e: any) => e && text(e.enabled).toLowerCase() !== 'false')) return false;
  return text(context?.resolvedFields?.['oidc.clientRegistration']).toLowerCase() === 'true';
}

/**
 * The warning for a deploy that publishes the service without sign-in, or null when there is nothing to warn about:
 * the service does not declare the risk, its Ingress is off, a security profile with an authentication mode is
 * selected, or the platform context signs users in through its identity provider anyway.
 *
 * @param def       the service definition (service.json)
 * @param form      the wizard's form values
 * @param profiles  the security profiles by name, as loaded by the wizard
 * @param context   the selected platform context, resolved
 */
export function unauthenticatedExposure(def: any, form: any, profiles: Record<string, any> | undefined,
                                        context?: ResolvedContext | null): UnauthenticatedExposure | null {
  const risk = text(def?.securityCoupling?.unauthenticatedExposure);
  if (!risk || form?.ingress?.enabled !== true) return null;
  const profileName = text(form?.securityProfile);
  if (profileName) {
    const profile = profiles?.[profileName];
    // A profile the wizard could not load says nothing about its mode: the server checks it exists.
    if (!profile) return null;
    const mode = text(profile.mode).toLowerCase();
    if (mode && mode !== 'none') return null;
  }
  if (oidcLoginWithoutProfile(def, context)) return null;

  const label = text(def?.label) || 'This service';
  const host = text(form?.ingress?.host);
  const where = host ? `at ${host}` : 'at its Ingress address';
  const why = profileName
    ? `The security profile "${text(profiles?.[profileName]?.displayName) || profileName}" has no authentication mode.`
    : 'No security profile is selected.';
  return {
    title: `${label} will be reachable from outside the cluster without sign-in`,
    lines: [
      `The Ingress (a Route on OpenShift) publishes ${label} ${where}. ${why}`,
      risk,
      'To require sign-in, pick an LDAP, Active Directory or OIDC Security Profile in the first step. '
        + 'To keep the service inside the cluster, turn the Ingress off.',
    ],
  };
}
