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

import { FilterUrlParamChange } from '@app/classes/models/filter-url-param-change.interface';

import { FilterHistoryActions, FilterHistoryActionTypes } from '../actions/filter-history.actions';

export interface LogTypeFilterHistory {
  changes: FilterUrlParamChange[];
  currentChangeIndex: number;
};

export interface FilterHistoryState {
  [key: string]: LogTypeFilterHistory;
}

export const initialState: FilterHistoryState = {};

export function reducer(state = initialState, action: FilterHistoryActions): FilterHistoryState {
  switch (action.type) {
      case FilterHistoryActionTypes.ADD_FILTER_HISTORY: {
        const payload = action.payload;
        const history: LogTypeFilterHistory = state[payload.logType] ? {...state[payload.logType]} : {
          changes: [],
          currentChangeIndex: -1
        };
        // we throw away the 'rest' of the history changes when the user create a new filter
        // while he/she did undo/redo so when the current index is pointing other than the last element
        if (history.currentChangeIndex > -1 && history.currentChangeIndex < history.changes.length - 1) {
          history.changes = [...history.changes.slice(0, history.currentChangeIndex + 1)];
        }
        history.changes = [...history.changes, payload.change];
        history.currentChangeIndex = history.changes.length - 1;
        return {
          ...state,
          [payload.logType]: history
        };
      }
      case FilterHistoryActionTypes.SET_CURRENT_FILTER_HISTORY_BY_INDEX: {
        const payload = action.payload;
        const history: LogTypeFilterHistory = state[payload.logType];
        const subState = {};
        if (history && history.changes.length > payload.index) {
          subState[payload.logType] = {
            ...history,
            currentChangeIndex: payload.index
          };
          return {
            ...state,
            ...subState
          };
        }
        return state;
      }
      case FilterHistoryActionTypes.SET_CURRENT_FILTER_HISTORY_BY_URL_PARAM: {
        const payload = action.payload;
        const {logType, url} = payload;
        const history: LogTypeFilterHistory = state[logType];
        const subState = {};
        if (history && history.changes.length) {
          const index = history.changes.findIndex((change) => change.currentPath === url);
          subState[logType] = {
            ...history,
            currentChangeIndex: index > -1 ? index : history.currentChangeIndex
          };
          return {
            ...state,
            ...subState
          };
        }
        return state;
      }
      default: {
        return state;
      }
  };
}

export const createFilterHistoryGetterById = (id: string): (state: FilterHistoryState) => LogTypeFilterHistory => {
  return (state: FilterHistoryState): LogTypeFilterHistory => state[id];
};
export const getServiceLogsFilterHistory = createFilterHistoryGetterById('serviceLogs');
export const getAuditLogsFilterHistory = createFilterHistoryGetterById('auditLogs');

export const getFilterHistoryList = (filterHistory: LogTypeFilterHistory): FilterUrlParamChange[] => filterHistory.changes;
export const getFilterHistoryCurrentChangeIndex = (filterHistory: LogTypeFilterHistory): number => filterHistory.currentChangeIndex;

export const getUndoHistoryList = (filterHistory: LogTypeFilterHistory): FilterUrlParamChange[] => (
  getFilterHistoryList(filterHistory).slice(0, getFilterHistoryCurrentChangeIndex(filterHistory))
);
export const getRedoHistoryList = (filterHistory: LogTypeFilterHistory): FilterUrlParamChange[] => (
  getFilterHistoryList(filterHistory).slice(getFilterHistoryCurrentChangeIndex(filterHistory) + 1)
);
export const getCurrentFilterUrlParamChange = (filterHistory: LogTypeFilterHistory): FilterUrlParamChange => (
  getFilterHistoryList(filterHistory)[getFilterHistoryCurrentChangeIndex(filterHistory)]
);
