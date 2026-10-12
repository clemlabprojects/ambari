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
import type { CommandStatus } from '../../api/client';

/**
 * A GitOps deploy whose change waits for a merge: the operation ends SUCCEEDED once the change is in Git, but Flux
 * applies nothing until it is merged, and the steps that need the running release were not run.
 */
export type WaitingForMerge = {
  /** What the change waits for, the steps not run and how to run them, as the backend words it. */
  message: string;
  /** The pull request to merge, when one was opened (an http(s) link only). */
  pullRequestUrl?: string;
};

/** The merge a finished operation waits for, or null when it waits for none. */
export const waitingForMerge = (status?: Pick<CommandStatus, 'result'> | null): WaitingForMerge | null => {
  const result = status?.result;
  if (!result || result.waitingForMerge !== true) return null;
  const message = typeof result.message === 'string' && result.message.trim()
    ? result.message
    : 'The change was committed to Git and waits for a merge: Flux applies it once it is merged.';
  // Only a web link is offered as one: the URL comes from the Git provider.
  const url = typeof result.pullRequestUrl === 'string' ? result.pullRequestUrl.trim() : '';
  return { message, pullRequestUrl: /^https?:\/\//i.test(url) ? url : undefined };
};

/**
 * What the operator must know once an operation succeeded, as the backend words it: where to read a password KDPS
 * generated for the release (never the password itself). Empty when there is nothing to tell.
 */
export const operationNotices = (status?: Pick<CommandStatus, 'result'> | null): string[] => {
  const notices = status?.result?.notices;
  if (!Array.isArray(notices)) return [];
  return notices.filter((n): n is string => typeof n === 'string' && n.trim() !== '');
};
