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

import { COMMIT_MODE_LABELS, gitOptionsToSubmit, landingBranchOf, opensPullRequest, workingBranchProblem } from '../gitOpsBranches';

// The deploy forms say, and preview, what the server does: a direct commit lands on the base branch Flux reconciles,
// a pull request on a branch of its own, opened into the base branch.
describe('gitOpsBranches', () => {
  it('previews a direct commit on the base branch, main when none is given', () => {
    expect(landingBranchOf(undefined)).toBe('main');
    expect(landingBranchOf({ commitMode: 'DIRECT_COMMIT' })).toBe('main');
    expect(landingBranchOf({ commitMode: 'DIRECT_COMMIT', baseBranch: 'production', branch: 'feature/x' })).toBe('production');
  });

  it('previews a pull request on its own branch, into the base branch', () => {
    expect(landingBranchOf({ commitMode: 'PR_MODE', baseBranch: 'production' }))
      .toBe('a new branch, in a pull request into production');
    expect(landingBranchOf({ commitMode: 'PR_MODE', branch: ' feature/x ' }))
      .toBe('feature/x, in a pull request into main');
  });

  it('never submits a working branch with a direct commit, which the server refuses', () => {
    expect(gitOptionsToSubmit({ commitMode: 'DIRECT_COMMIT', baseBranch: 'main', branch: 'feature/x' }))
      .toEqual({ commitMode: 'DIRECT_COMMIT', baseBranch: 'main' });
    expect(gitOptionsToSubmit({ baseBranch: 'main', branch: 'feature/x' })).toEqual({ baseBranch: 'main' });
    expect(gitOptionsToSubmit({ commitMode: 'PR_MODE', baseBranch: 'main', branch: 'feature/x' }))
      .toEqual({ commitMode: 'PR_MODE', baseBranch: 'main', branch: 'feature/x' });
    expect(gitOptionsToSubmit(undefined)).toBeUndefined();
  });

  it('refuses a pull request from the base branch itself, which would skip the review', () => {
    expect(workingBranchProblem({ commitMode: 'PR_MODE', branch: ' main ' }))
      .toBe('The working branch must differ from the base branch main: the pull request is opened from it into main.');
    expect(workingBranchProblem({ commitMode: 'PR_MODE', baseBranch: 'production', branch: 'production' }))
      .toContain('base branch production');
    expect(workingBranchProblem({ commitMode: 'PR_MODE', baseBranch: 'production', branch: 'main' })).toBeUndefined();
    expect(workingBranchProblem({ commitMode: 'PR_MODE' })).toBeUndefined();
    expect(workingBranchProblem({ commitMode: 'DIRECT_COMMIT', branch: 'main' })).toBeUndefined();
  });

  it('labels pull request mode without calling it a stub', () => {
    expect(opensPullRequest({ commitMode: 'PR_MODE' })).toBe(true);
    expect(opensPullRequest({ commitMode: 'DIRECT_COMMIT' })).toBe(false);
    expect(Object.values(COMMIT_MODE_LABELS).join(' ')).not.toMatch(/stub/i);
  });
});
