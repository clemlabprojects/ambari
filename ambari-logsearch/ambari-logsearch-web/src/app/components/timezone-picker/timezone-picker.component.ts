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

import { Component, Input, OnDestroy } from '@angular/core';
import { Router, ActivatedRoute } from '@angular/router';

import * as moment from 'moment-timezone';
import { Observable } from 'rxjs/Observable';
import { Subject } from 'rxjs/Subject';

import { Store } from '@ngrx/store';
import { AppStore } from '@app/classes/models/store';
import { selectTimeZone } from '@app/store/selectors/user-settings.selectors';
import { SetUserSettingsAction } from '@app/store/actions/user-settings.actions';

@Component({
  selector: 'timezone-picker',
  templateUrl: './timezone-picker.component.html',
  styleUrls: ['./timezone-picker.component.less']
})
export class TimeZonePickerComponent implements OnDestroy {

  @Input()
  visibilityQueryParamName = 'timeZoneSettings';
  
  timeZone$: Observable<string> = this.store.select(selectTimeZone).startWith(moment.tz.guess());

  selectedTimeZone: string;

  visible$: Observable<boolean> = this.route.queryParams
    .map(params => params)
    .map((params): boolean => /^(show|yes|true|1)$/.test(params[this.visibilityQueryParamName]))
    .distinctUntilChanged();

  destroyed$: Subject<boolean> = new Subject();

  constructor(
    private store: Store<AppStore>,
    private route: ActivatedRoute,
    private router: Router
  ) {
  }

  ngOnDestroy() {
    this.destroyed$.next(true);
    this.destroyed$.complete();
  }

  onCloseRequest = (clickEvent) => {
    clickEvent.preventDefault();
    clickEvent.stopPropagation();
    this.close();
  }

  onSaveRequest = (clickEvent) => {
    this.timeZone$.take(1).subscribe((originalTimeZone) => {
      if (this.selectedTimeZone && originalTimeZone !== this.selectedTimeZone) {
        this.store.dispatch(new SetUserSettingsAction({
          timeZone: this.selectedTimeZone
        }));
      }
      this.onCloseRequest(clickEvent);
    });
  } 

  onTimeZoneSelect(timeZone) {
    this.selectedTimeZone = timeZone;
  }

  close() {
    this.route.queryParams.take(1).subscribe((queryParams) => {
      const params = { ...queryParams };
      delete params[this.visibilityQueryParamName];
      this.router.navigate(['.'], {
        queryParams: params,
        relativeTo: this.route.root.firstChild
      });
    });
  }

}
