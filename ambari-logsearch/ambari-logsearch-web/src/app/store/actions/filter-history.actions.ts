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
import { FilterUrlParamChange } from '@app/classes/models/filter-url-param-change.interface';
import { LogsType } from '@app/classes/string';

export enum FilterHistoryActionTypes {
  ADD_FILTER_HISTORY = '[Filter History] Add',
  SET_CURRENT_FILTER_HISTORY_BY_INDEX = '[Filter History] Set Current By Index',
  SET_CURRENT_FILTER_HISTORY_BY_URL_PARAM = '[Filter History] Set Current By Url Param'
}

export class AddFilterHistoryAction implements Action {
  readonly type = FilterHistoryActionTypes.ADD_FILTER_HISTORY;
  constructor(public payload: {
    logType: LogsType,
    change: FilterUrlParamChange
  }) {}
}

export class SetCurrentFilterHistoryByIndexAction implements Action {
  readonly type = FilterHistoryActionTypes.SET_CURRENT_FILTER_HISTORY_BY_INDEX;
  constructor(public payload: {
    logType: LogsType,
    index: number
  }) {}
}

export class SetCurrentFilterHistoryByUrlParamAction implements Action {
  readonly type = FilterHistoryActionTypes.SET_CURRENT_FILTER_HISTORY_BY_URL_PARAM;
  constructor(public payload: {
    logType: LogsType,
    url: string
  }) {}
}

export type FilterHistoryActions =
  | AddFilterHistoryAction
  | SetCurrentFilterHistoryByIndexAction
  | SetCurrentFilterHistoryByUrlParamAction;
