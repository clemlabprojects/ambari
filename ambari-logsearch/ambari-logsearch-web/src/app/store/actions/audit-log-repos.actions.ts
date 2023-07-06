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

import { Action } from '@ngrx/store';
import { AuditLogRepo } from '../reducers/audit-log-repos.reducers';

export enum AuditLogReposActionTypes {
  LOAD = '[AuditLogRepos] Load',
  LOADED = '[AuditLogRepos] Loaded',
  SET = '[AuditLogRepos] Set',
  CLEAR = '[AuditLogRepos] Clear'
}

export class LoadAuditLogsReposAction implements Action {
  readonly type = AuditLogReposActionTypes.LOAD;
  constructor() {}
}

export class LoadedAuditLogReposAction implements Action {
  readonly type = AuditLogReposActionTypes.LOADED;
  constructor(public payload: AuditLogRepo[]) {}
}

export class SetAuditLogReposAction implements Action {
  readonly type = AuditLogReposActionTypes.SET;
  constructor(public payload: AuditLogRepo[]) {}
}

export class ClearAuditLogReposAction implements Action {
  readonly type = AuditLogReposActionTypes.CLEAR;
  constructor() {}
}

export type AuditLogReposActions = LoadAuditLogsReposAction
  | LoadedAuditLogReposAction
  | SetAuditLogReposAction
  | ClearAuditLogReposAction;
