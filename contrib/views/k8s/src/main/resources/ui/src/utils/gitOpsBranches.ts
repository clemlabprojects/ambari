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

/*
 * Where a GitOps change lands in Git, as the deploy forms explain it and preview it. Flux reconciles the base branch:
 * a direct commit is pushed to it, and a pull request is opened into it from a branch of its own. The server applies
 * the same rules; a direct commit naming another branch is refused there.
 */

export const DEFAULT_BASE_BRANCH = 'main';

export const BASE_BRANCH_TOOLTIP =
  'The branch Flux reconciles. A direct commit is pushed to it; a pull request is opened into it. Left empty, the'
  + ' release keeps the base branch it was deployed with, or main for a new release.';

export const WORKING_BRANCH_TOOLTIP =
  'The branch the pull request is opened from, other than the base branch. Left empty, KDPS adds the change to the'
  + ' release\'s pull request while it is still open, and otherwise opens a new one from a new branch started from the'
  + ' base branch.';

export const COMMIT_MODE_LABELS: Record<'DIRECT_COMMIT' | 'PR_MODE', string> = {
  DIRECT_COMMIT: 'Direct commit to the base branch',
  PR_MODE: 'Open a pull request (access token required)',
};

export interface GitOptionsForm {
  baseBranch?: string;
  branch?: string;
  commitMode?: string;
}

/** Whether the options open a pull request rather than committing to the base branch. */
export function opensPullRequest(git?: GitOptionsForm): boolean {
  return git?.commitMode === 'PR_MODE';
}

/** The branch Flux reconciles for these options, as the server picks it for a new release. */
export function baseBranchOf(git?: GitOptionsForm): string {
  return git?.baseBranch?.trim() || DEFAULT_BASE_BRANCH;
}

/** Where the change is pushed, for the manifest preview: the base branch, or the branch of the pull request. */
export function landingBranchOf(git?: GitOptionsForm): string {
  const base = baseBranchOf(git);
  if (!opensPullRequest(git)) return base;
  const named = git?.branch?.trim();
  return named ? `${named}, in a pull request into ${base}` : `a new branch, in a pull request into ${base}`;
}

/**
 * Why the working branch cannot be used, or undefined: a pull request from the base branch itself would push the change
 * straight to the branch Flux reconciles, without review. The server refuses it too.
 */
export function workingBranchProblem(git?: GitOptionsForm): string | undefined {
  if (!opensPullRequest(git)) return undefined;
  const named = git?.branch?.trim();
  const base = baseBranchOf(git);
  return named && named === base
    ? `The working branch must differ from the base branch ${base}: the pull request is opened from it into ${base}.`
    : undefined;
}

/** The options as submitted: a direct commit names no working branch, which only a pull request has. */
export function gitOptionsToSubmit<T extends GitOptionsForm>(git: T | undefined): T | undefined {
  if (!git || opensPullRequest(git)) return git;
  const submitted = { ...git };
  delete submitted.branch;
  return submitted;
}
