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

import { UserSettingsState } from '@app/store/reducers/user-settings.reducers';

export enum UserSettingsActionTypes {
  LOAD = '[User Settings] Load from server',
  LOAD_SUCCESS = '[User Settings] Load from server is successful',
  LOAD_FAILED = '[User Settings] Load from server is failed',

  SAVE = '[User Settings] Save to server',
  SAVE_SUCCESS = '[User Settings] Save to server is successful',
  SAVE_FAILED = '[User Settings] Save to server is failed',

  SET = '[User Settings] Set to state'
}

export class LoadUserSettingsAction implements Action {
  readonly type = UserSettingsActionTypes.LOAD;
  constructor() {}
}

export class LoadUserSettingsSuccessAction implements Action {
  readonly type = UserSettingsActionTypes.LOAD_SUCCESS;
  constructor(public payload: UserSettingsState) {}
}

export class LoadUserSettingsFailedAction implements Action {
  readonly type = UserSettingsActionTypes.LOAD_FAILED;
  constructor(public payload: {[key: string]: any}) {}
}

export class SaveUserSettingsAction implements Action {
  readonly type = UserSettingsActionTypes.SAVE;
  constructor(public payload: UserSettingsState) {}
}

export class SaveUserSettingsSuccessAction implements Action {
  readonly type = UserSettingsActionTypes.SAVE_SUCCESS;
  constructor(public payload: UserSettingsState) {}
}

export class SaveUserSettingsFailedAction implements Action {
  readonly type = UserSettingsActionTypes.SAVE_FAILED;
  constructor(public payload: {[key: string]: any}) {}
}

export class SetUserSettingsAction implements Action {
  readonly type = UserSettingsActionTypes.SET;
  constructor(public payload: {[key: string]: any}) {}
}

export type UserSettingsActions = SetUserSettingsAction
  | LoadUserSettingsAction
  | LoadUserSettingsSuccessAction
  | LoadUserSettingsFailedAction
  | SaveUserSettingsAction
  | SaveUserSettingsSuccessAction
  | SaveUserSettingsFailedAction;
