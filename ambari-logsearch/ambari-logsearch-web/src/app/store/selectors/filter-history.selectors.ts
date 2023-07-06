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

import { createSelector, Selector } from 'reselect';

import { AppStore } from '@app/classes/models/store';
import * as fromFilterHistoryReducers from '@app/store/reducers/filter-history.reducers';
import { FilterUrlParamChange } from '@app/classes/models/filter-url-param-change.interface';
import * as fromAppStateSelector from '@app/store/selectors/app-state.selectors';


export const selectFilterHistoryState = (state: AppStore): fromFilterHistoryReducers.FilterHistoryState => state.filterHistory;

export const selectActiveFilterHistory = createSelector(
  selectFilterHistoryState,
  fromAppStateSelector.selectActiveLogsType,
  (filterHistoryState, activeLogsType): fromFilterHistoryReducers.LogTypeFilterHistory => (
    filterHistoryState && filterHistoryState[activeLogsType]
  )
);

export const selectActiveFilterHistoryChangeIndex = createSelector(
  selectActiveFilterHistory,
  (logTypeFilterHistory: fromFilterHistoryReducers.LogTypeFilterHistory): number => (
    logTypeFilterHistory && logTypeFilterHistory.currentChangeIndex
  )
);

export const selectActiveFilterHistoryChanges = createSelector(
  selectActiveFilterHistory,
  (logTypeFilterHistory: fromFilterHistoryReducers.LogTypeFilterHistory): FilterUrlParamChange[] => (
    logTypeFilterHistory && logTypeFilterHistory.changes
  )
);

export const selectActiveFilterHistoryChangesUndoItems = createSelector(
  selectActiveFilterHistoryChanges,
  selectActiveFilterHistoryChangeIndex,
  (items: FilterUrlParamChange[], changeIndex: number): FilterUrlParamChange[] => items && items.slice(0, changeIndex)
);

export const selectActiveFilterHistoryChangesRedoItems = createSelector(
  selectActiveFilterHistoryChanges,
  selectActiveFilterHistoryChangeIndex,
  (items: FilterUrlParamChange[], changeIndex: number): FilterUrlParamChange[] => items && items.slice(changeIndex + 1)
);

export const createFilterHistorySelectorById = (id: string): Selector<AppStore, fromFilterHistoryReducers.LogTypeFilterHistory> => (
  createSelector(
    selectFilterHistoryState,
    (filterHistoryState): fromFilterHistoryReducers.LogTypeFilterHistory => filterHistoryState[id]
  )
);

export const createFilterHistoryChangeIndexSelectorById = (id: string): Selector<AppStore, number> => createSelector(
  createFilterHistorySelectorById(id),
  (logTypeFilterHistory: fromFilterHistoryReducers.LogTypeFilterHistory): number => (
    logTypeFilterHistory && logTypeFilterHistory.currentChangeIndex
  )
);

export const createFilterHistoryChangesSelectorById = (id: string): Selector<AppStore, FilterUrlParamChange[]> => createSelector(
  createFilterHistorySelectorById(id),
  (logTypeFilterHistory: fromFilterHistoryReducers.LogTypeFilterHistory): FilterUrlParamChange[] => (
    logTypeFilterHistory && logTypeFilterHistory.changes
  )
);

export const createFilterHistoryChangesUndoItemsSelectorById = (id: string): Selector<AppStore, FilterUrlParamChange[]> => createSelector(
  createFilterHistoryChangesSelectorById(id),
  createFilterHistoryChangeIndexSelectorById(id),
  (items: FilterUrlParamChange[], changeIndex: number): FilterUrlParamChange[] => items && items.slice(0, changeIndex)
);

export const createFilterHistoryChangesRedoItemsSelectorById = (id: string): Selector<AppStore, FilterUrlParamChange[]> => createSelector(
  createFilterHistoryChangesSelectorById(id),
  createFilterHistoryChangeIndexSelectorById(id),
  (items: FilterUrlParamChange[], changeIndex: number): FilterUrlParamChange[] => items && items.slice(changeIndex + 1)
);
