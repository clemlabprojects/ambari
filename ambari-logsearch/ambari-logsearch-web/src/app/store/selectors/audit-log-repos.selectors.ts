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

import { AppStore } from '@app/classes/models/store';
import { AuditLogRepo } from '../reducers/audit-log-repos.reducers';
import { createSelector } from 'reselect';

export const selectAuditLogReposState = (state: AppStore): AuditLogRepo[] => state.auditLogRepos;

export function createSelectAuditLogReposLabelByRepoName(repoName: string) {
  return createSelector(
    selectAuditLogReposState,
    (repos): string => {
      const repoWithGivenName: AuditLogRepo = repos.find((repo: AuditLogRepo) => repo.name === repoName);
      return repoWithGivenName ? repoWithGivenName.label : repoName;
    }
  );
}
