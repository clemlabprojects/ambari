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

import { deploySubmittedMessage, describeGitOps, gitOpsResult, operationNotices, waitingForMerge } from '../operationOutcome';

describe('waitingForMerge', () => {
  it('reads what a GitOps deploy waiting for a merge ended with', () => {
    expect(waitingForMerge({
      result: {
        waitingForMerge: true,
        message: 'Waiting for branch flux-1 of https://git.example.org/gitops.git to be merged into branch main.',
        pullRequestUrl: 'https://git.example.org/gitops/pull/3',
      },
    })).toEqual({
      message: 'Waiting for branch flux-1 of https://git.example.org/gitops.git to be merged into branch main.',
      pullRequestUrl: 'https://git.example.org/gitops/pull/3',
    });
  });

  it('is null for an operation that waits for nothing', () => {
    expect(waitingForMerge(null)).toBeNull();
    expect(waitingForMerge({ result: undefined })).toBeNull();
    expect(waitingForMerge({ result: { waitingForMerge: false, message: 'x' } })).toBeNull();
  });

  it('offers only a web link to the pull request', () => {
    const pending = waitingForMerge({ result: { waitingForMerge: true, pullRequestUrl: 'javascript:alert(1)' } });
    expect(pending?.pullRequestUrl).toBeUndefined();
    expect(pending?.message).toMatch(/waits for a merge/);
  });
});

describe('operationNotices', () => {
  it('reads what the operator must know once the deploy succeeded', () => {
    expect(operationNotices({
      result: { notices: ['Superset administrator \'admin\': its password is in the Secret bi/bi-superset-admin.'] },
    })).toEqual(['Superset administrator \'admin\': its password is in the Secret bi/bi-superset-admin.']);
  });

  it('keeps the notices of a GitOps deploy waiting for a merge', () => {
    expect(operationNotices({ result: { waitingForMerge: true, message: 'x', notices: ['n1', 'n2'] } }))
      .toEqual(['n1', 'n2']);
  });

  it('is empty without notices, and ignores anything but text', () => {
    expect(operationNotices(null)).toEqual([]);
    expect(operationNotices({ result: undefined })).toEqual([]);
    expect(operationNotices({ result: { notices: 'not a list' } })).toEqual([]);
    expect(operationNotices({ result: { notices: [42, '', '  ', 'kept'] } })).toEqual(['kept']);
  });
});

describe('gitOpsResult', () => {
  it('reads what a GitOps deploy did in Git', () => {
    expect(gitOpsResult({
      result: {
        gitOps: {
          mode: 'FLUX_GITOPS', commit: '3f0c9a52e7d14b6c8a9e', branch: 'flux-1', noChanges: false,
          prUrl: 'https://git.example.org/platform/gitops/pulls/7', prState: 'open',
        },
      },
    })).toEqual({
      mode: 'FLUX_GITOPS', commit: '3f0c9a52e7d14b6c8a9e', branch: 'flux-1', noChanges: false,
      prUrl: 'https://git.example.org/platform/gitops/pulls/7', prState: 'open',
    });
  });

  it('is null for an operation that did nothing in Git', () => {
    expect(gitOpsResult(null)).toBeNull();
    expect(gitOpsResult({ result: { waitingForMerge: true } })).toBeNull();
    expect(gitOpsResult({ result: { gitOps: { mode: 'DIRECT_HELM' } } })).toBeNull();
  });

  it('offers only a web link to the pull request', () => {
    expect(gitOpsResult({ result: { gitOps: { mode: 'FLUX_GITOPS', prUrl: 'javascript:alert(1)' } } })?.prUrl)
      .toBeUndefined();
  });
});

describe('describeGitOps', () => {
  it('names the commit, the branch and the pull request', () => {
    expect(describeGitOps({
      mode: 'FLUX_GITOPS', commit: '3f0c9a52e7d14b6c8a9e', branch: 'main', prState: 'open', noChanges: false,
    })).toEqual({ title: 'Committed to Git', lines: ['Commit 3f0c9a52e7d1', 'Branch main', 'Pull request (open)'] });
  });

  it('says when Git already held the change', () => {
    expect(describeGitOps({ mode: 'FLUX_GITOPS', commit: 'abc', branch: 'main', noChanges: true }).title)
      .toBe('Git already holds this change: nothing committed');
  });
});

describe('deploySubmittedMessage', () => {
  it('says a GitOps change goes to Git, and to which branch', () => {
    expect(deploySubmittedMessage('Resync', { id: 'op', mode: 'FLUX_GITOPS', gitOps: { mode: 'FLUX_GITOPS', branch: 'main' } }))
      .toBe('Resync started: the change is committed to Git on branch main once the release is prepared, then Flux'
        + ' applies it. Follow it in Background operations.');
    expect(deploySubmittedMessage('Redeploy', { id: 'op', mode: 'FLUX_GITOPS', gitOps: { mode: 'FLUX_GITOPS' } }))
      .toMatch(/^Redeploy started: the change is committed to Git once the release is prepared/);
  });

  it('says only that a deploy with Helm started', () => {
    expect(deploySubmittedMessage('Deployment', { id: 'op', mode: 'DIRECT_HELM' })).toBe('Deployment started');
    expect(deploySubmittedMessage('Deployment', undefined)).toBe('Deployment started');
  });
});
