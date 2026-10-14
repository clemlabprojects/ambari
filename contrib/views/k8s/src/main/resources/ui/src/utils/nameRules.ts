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

import type { Rule } from 'antd/lib/form';

/*
 * The naming rules of Helm and Kubernetes, checked as the operator types so a name is corrected before the deploy is
 * submitted. The server checks the same names on every deploy; these only warn early.
 */

/** Helm's rule for a release name: a DNS-1123 subdomain of at most 53 characters. */
export const RELEASE_NAME_PATTERN = '^(?=.{1,53}$)[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$';
export const RELEASE_NAME_HINT =
  "Use at most 53 lower-case letters, digits, '-' and '.', starting and ending with a letter or digit"
  + " (Helm's rule for release names).";

/** A DNS-1123 label of at most 63 characters: Kubernetes' rule for a namespace. */
export const NAMESPACE_PATTERN = '^(?=.{1,63}$)[a-z0-9]([-a-z0-9]*[a-z0-9])?$';
export const NAMESPACE_HINT =
  "Use at most 63 lower-case letters, digits and '-', starting and ending with a letter or digit.";

/**
 * The rule of a form field that declares a `pattern` (and the `validationHint` shown when the value does not match
 * it) in its service definition. None for a field without one, or with a pattern the browser cannot compile: a
 * broken definition must not stop the wizard.
 */
export const patternRules = (pattern?: string, hint?: string): Rule[] => {
  if (!pattern) return [];
  try {
    return [{ pattern: new RegExp(pattern), message: hint || `Does not match the expected format (${pattern}).` }];
  } catch {
    return [];
  }
};

/** The rules of the release name: required, then the rule the service declares for it, else Helm's. */
export const releaseNameRules = (definition?: { form?: any[] } | null): Rule[] => {
  const field = (definition?.form || []).find((f: any) => f?.name === 'releaseName');
  const declared = field?.pattern ? patternRules(field.pattern, field.validationHint) : [];
  return [
    { required: true, message: 'Enter a release name.' },
    ...(declared.length > 0 ? declared : patternRules(RELEASE_NAME_PATTERN, RELEASE_NAME_HINT)),
  ];
};

/** The rules of a namespace: required, a DNS-1123 label. */
export const namespaceRules = (): Rule[] => [
  { required: true, message: 'Enter a namespace.' },
  ...patternRules(NAMESPACE_PATTERN, NAMESPACE_HINT),
];
