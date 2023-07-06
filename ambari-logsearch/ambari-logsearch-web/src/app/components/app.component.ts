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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { Observable } from 'rxjs/Observable';
import { Subject } from 'rxjs/Subject';

import { Options } from 'angular2-notifications/src/options.type';

import { AppStateService } from '@app/services/storage/app-state.service';
import { DataAvailabilityValues } from '@app/classes/string';
import { notificationIcons } from '@modules/shared/services/notification.service';

import { Store } from '@ngrx/store';
import { AppStore } from '@app/classes/models/store';
import { AuthorizationStatuses } from '@app/store/reducers/auth.reducers';
import { isAuthorizedSelector, selectAuthStatus, isCheckingAuthStatusInProgressSelector } from '@app/store/selectors/auth.selectors';

import { HttpClientService } from '@app/services/http-client.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.less', '../modules/shared/notifications.less'],
  host: {
    '[class]': 'hostCssClasses'
  }
})
export class AppComponent implements OnInit, OnDestroy {

  authorizationStatuses = AuthorizationStatuses;

  isAuthorized$: Observable<boolean> = this.store.select(isAuthorizedSelector);
  authorizationStatus$: Observable<AuthorizationStatuses> = this.store.select(selectAuthStatus);
  isCheckingAuthStatusInProgress$: Observable<boolean> = this.store.select(isCheckingAuthStatusInProgressSelector);
  authorizationCode$: Observable<number> = this.appState.getParameter('authorizationCode');
  isBaseDataAvailable$: Observable<boolean> = this.appState.getParameter('baseDataSetState')
    .map((dataSetState: DataAvailabilityValues) => dataSetState === DataAvailabilityValues.AVAILABLE);

  destroyed$ = new Subject();

  notificationServiceOptions: Options = {
    timeOut: 2000,
    showProgressBar: true,
    pauseOnHover: true,
    preventLastDuplicates: 'visible',
    theClass: 'app-notification',
    icons: notificationIcons,
    position: ['top', 'left']
  };

  hostCssClasses = '';

  constructor(
    private appState: AppStateService,
    public httpClient: HttpClientService,
    private store: Store<AppStore>
  ) {}

  ngOnInit() {
    this.authorizationStatus$.distinctUntilChanged().takeUntil(this.destroyed$).subscribe(this.onAuthStatusChange);
  }

  ngOnDestroy() {
    this.destroyed$.next(true);
  }

  onAuthStatusChange = (status: AuthorizationStatuses): void => {
    this.setHostCssClasses(status ? status.replace(/\s/, '-').toLocaleLowerCase() : '');
  }

  setHostCssClasses(cls: string) {
    this.hostCssClasses = cls;
  }

}
