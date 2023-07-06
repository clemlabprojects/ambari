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
import { Injectable } from '@angular/core';
import { CanActivate, ActivatedRouteSnapshot, RouterStateSnapshot, Router } from '@angular/router';
import { Observable } from 'rxjs/Observable';

import { Store } from '@ngrx/store';
import { AppStore } from '@app/classes/models/store';

import * as dataAvailabilitySelectors from '@app/store/selectors/data-availability.selectors';

import * as fromFilterHistoryReducers from '@app/store/reducers/filter-history.reducers';
import * as filterHistorySelectors from '@app/store/selectors/filter-history.selectors';
import { AddFilterHistoryAction, SetCurrentFilterHistoryByIndexAction } from '@app/store/actions/filter-history.actions';

@Injectable()
export class FilterHistoryIndexGuard implements CanActivate {

  private currentUrl: string;

  currentFilterHistory$: Observable<fromFilterHistoryReducers.FilterHistoryState> = this.store.select(
    filterHistorySelectors.selectFilterHistoryState
  );

  filterHistoryIndexUrlParamName = '_fhi';
  logsTypeUrlParamName = 'activeTab';

  constructor (
    private router: Router,
    private store: Store<AppStore>
  ) {}

  removeFilterHistoryIndexFromUrl(url) {
    const regexp = new RegExp(`;${this.filterHistoryIndexUrlParamName}=\\d{1,}`, 'g');
    return url.replace(regexp, '');
  }

  addFilterHistoryIndexToUrl(url, index) {
    const [mainUrl, queryParams] = url.split('?');
    return `${mainUrl};${this.filterHistoryIndexUrlParamName}=${index}${queryParams ? '?' : ''}${queryParams || ''}`;
  }

  canActivate(next: ActivatedRouteSnapshot, state: RouterStateSnapshot): Observable<boolean> | Promise<boolean> | boolean {
    let filterHistoryIndex = next.params[this.filterHistoryIndexUrlParamName];
    if (typeof filterHistoryIndex === 'string') {
      filterHistoryIndex = parseInt(filterHistoryIndex, 0);
    }
    const logsType = next.params[this.logsTypeUrlParamName];
    return  Observable.combineLatest(
      this.currentFilterHistory$,
      this.store.select(dataAvailabilitySelectors.isBaseDataAvailable)
    ).first().map(([historyState, isBaseDataAvailable]: [fromFilterHistoryReducers.FilterHistoryState, boolean]): boolean => {
      const history = historyState[logsType];
      let canActivate = true;
      const requestedUrlWithoutFilterHistoryIndex = this.removeFilterHistoryIndexFromUrl(state.url);
      const lastChangeUrlWithoutFilterHistoryIndex = history && history.changes.length && (
        this.removeFilterHistoryIndexFromUrl(history.changes[ history.changes.length - 1].currentPath)
      );
      const isUrlChanged = lastChangeUrlWithoutFilterHistoryIndex !== requestedUrlWithoutFilterHistoryIndex;
      // check if the filter history index is correct
      if (isUrlChanged && filterHistoryIndex !== undefined) {
        const currentUrlAtIndex = (
          history
          && history.changes[filterHistoryIndex]
          && this.removeFilterHistoryIndexFromUrl(history.changes[filterHistoryIndex].currentPath)
        );
        if (requestedUrlWithoutFilterHistoryIndex !== currentUrlAtIndex) {
          filterHistoryIndex = undefined;
          // correct the filter history index if we already have history and the URL exists in the list
          if (history && history.changes.length) {
            const existingIndex = history.changes.findIndex(
              (change) => this.removeFilterHistoryIndexFromUrl(change.currentPath) ===  requestedUrlWithoutFilterHistoryIndex
            );
            if (existingIndex > -1) {
              filterHistoryIndex = existingIndex;
            }
          }
        }
      }
      if (!history || filterHistoryIndex === undefined) { // new History URL
        const nextFilterHistoryIndex: number = history ? history.currentChangeIndex + 1 : 0;
        const indexedUrl = this.addFilterHistoryIndexToUrl(requestedUrlWithoutFilterHistoryIndex, nextFilterHistoryIndex);
        if (isUrlChanged) {
          this.store.dispatch( new AddFilterHistoryAction({
            logType: logsType,
            change: {
              previousPath: this.currentUrl,
              currentPath: indexedUrl,
              time: new Date()
            }
          }));
          this.currentUrl = indexedUrl;
        }
        this.router.navigateByUrl(indexedUrl);
        canActivate = false;
      } else if (history.currentChangeIndex !== filterHistoryIndex) {
        // set the current index in the store
        this.store.dispatch(
          new SetCurrentFilterHistoryByIndexAction({
            logType: logsType,
            index: filterHistoryIndex
          })
        );
      }
      // if we found the requested URL in the history but the recorded index is not the same as it is in the URL
      // we add it and navigate to the indexed URL
      if (filterHistoryIndex !== undefined && next.params[this.filterHistoryIndexUrlParamName] !== filterHistoryIndex.toString()) {
        this.router.navigateByUrl( this.addFilterHistoryIndexToUrl(state.url, filterHistoryIndex) );
        canActivate = false;
      }
      return canActivate;
    });
  }
}
