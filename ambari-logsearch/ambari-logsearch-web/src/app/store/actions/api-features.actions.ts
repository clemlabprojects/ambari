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

import { ApiFeatureSet } from '../reducers/api-features.reducers';

export enum ApiFeaturesActionTypes {
  SET = '[Api Features] Set',
  LOAD = '[Api Features] Load',
  LOAD_SUCCESS = '[Api Features] Load success',
  LOAD_FAILED = '[Api Features] Load failed'
}

export class LoadApiFeaturesAction implements Action {
  readonly type = ApiFeaturesActionTypes.LOAD;
  constructor() {}
}

export class LoadSuccessApiFeaturesAction implements Action {
  readonly type = ApiFeaturesActionTypes.LOAD_SUCCESS;
  constructor(public payload: ApiFeatureSet) {}
}

export class LoadFailedApiFeaturesAction implements Action {
  readonly type = ApiFeaturesActionTypes.LOAD_FAILED;
  constructor(public payload: any) {}
}

export class SetApiFeaturesAction implements Action {
  readonly type = ApiFeaturesActionTypes.SET;
  constructor(public payload: ApiFeatureSet) {}
}

export type ApiFeaturesActions = LoadApiFeaturesAction
  | LoadSuccessApiFeaturesAction
  | LoadFailedApiFeaturesAction
  | SetApiFeaturesAction;