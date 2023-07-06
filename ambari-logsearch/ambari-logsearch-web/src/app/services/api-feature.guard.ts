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
import { CanActivate } from '@angular/router';

import { Observable } from 'rxjs/Observable';
import 'rxjs/operator/do';
import { Store } from '@ngrx/store';

import { AppStore } from '@app/classes/models/store';
import { AddNotificationAction, NotificationActions } from '@app/store/actions/notification.actions';
import { NotificationType } from '@modules/shared/services/notification.service';

@Injectable()
export class ApiFeatureGuard implements CanActivate {
  
  protected selector;

  constructor(private store: Store<AppStore>){}

  canActivate(): Observable<boolean> {
    const canActivate$: Observable<boolean> = (this.selector ? this.store.select(this.selector) : Observable.of(true));
    return canActivate$.do((state) => {
      if (!state) {
        this.store.dispatch(new AddNotificationAction({
          type: NotificationType.ERROR,
          message: 'apiFeatures.disabled'
        }));
      }
    });
  }
}