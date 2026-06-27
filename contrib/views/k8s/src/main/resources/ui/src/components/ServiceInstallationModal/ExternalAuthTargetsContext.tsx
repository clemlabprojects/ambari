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
import type { ExternalServiceTarget } from '../../types/ServiceTypes';

/**
 * React context that surfaces the current service definition's
 * `externalServiceTargets` map to deep descendants — specifically the
 * `external-auth-target` DynamicFormField renderer. Avoids drilling the service
 * def through every group-nesting layer.
 *
 * Providers:
 *   - {@link ServiceInstallationModal} (legacy single-modal deploy path)
 *   - {@link ServiceWizardPage}/InstallStep (multi-step wizard, the real flow)
 *
 * Both wrap their form trees with this provider so the auth-target field
 * resolves its `target` attribute regardless of which deploy path is active.
 */
export const ExternalAuthTargetsContext =
    React.createContext<Record<string, ExternalServiceTarget> | undefined>(undefined);

export const useExternalAuthTarget = (key: string | undefined): ExternalServiceTarget | undefined => {
    const map = React.useContext(ExternalAuthTargetsContext);
    if (!map || !key) return undefined;
    return map[key];
};

/**
 * Names of form fields whose behaviour depends on the selected Platform Context — i.e. the
 * `when` triggers of the service definition's `requiresContext` rules (e.g.
 * `atlasFederation.enabled`). Surfaced to {@link DynamicFormField} so it can render a subtle
 * accent + cue on the field at ANY nesting depth (the toggle lives inside a group), tying it
 * visually to the right-pane Platform Context selector.
 */
export const ContextLinkedFieldsContext = React.createContext<Set<string>>(new Set());

export const useIsContextLinked = (name: string | undefined): boolean => {
    const set = React.useContext(ContextLinkedFieldsContext);
    return !!name && set.has(name);
};

/**
 * The resolved Platform Context for the current wizard selection — surfaced to
 * {@link DynamicFormField} so a {@code context-resolved} field can render the value KDPS
 * derives (host:port / scheme / auth, computed live from Ambari for a MANAGED context)
 * as a read-only "blue" value, instead of a free-text box the operator must fill in.
 *
 * - {@code kind}: MANAGED (live Ambari resolution) | EXTERNAL (operator-provided).
 * - {@code resolvedFields}: map of {@code <capability>.<field>} → resolved value.
 *
 * When a field has no resolved value AND the context is EXTERNAL (e.g. an external context
 * without a backing Ambari), the field renders an editable raw-property editor that the KDPS
 * engine uses to compute the scheme — keeping scheme derivation in the engine, not the operator.
 */
export interface ResolvedContextInfo {
    kind?: string;
    resolvedFields?: Record<string, string>;
    secretFieldsSet?: string[];
}

export const ResolvedContextValuesContext = React.createContext<ResolvedContextInfo | undefined>(undefined);

/** Resolved value for a {@code <capability>.<field>} key, or undefined when unresolved. */
export const useResolvedContextValue = (key: string | undefined): { value?: string; kind?: string; resolved: boolean } => {
    const info = React.useContext(ResolvedContextValuesContext);
    if (!info || !key) return { resolved: false };
    const value = info.resolvedFields?.[key];
    return { value, kind: info.kind, resolved: value != null && value !== '' };
};
