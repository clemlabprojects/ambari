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
import { Component, Input, OnInit, OnDestroy } from '@angular/core';
import { FormGroup, FormControl } from '@angular/forms';

import { Subject } from 'rxjs/Subject';
import { Observable } from 'rxjs/Observable';
import { Router, ActivatedRoute } from '@angular/router';

import { Store } from '@ngrx/store';
import { AppStore } from '@app/classes/models/store';
import { selectDisplayShortHostNames, selectTimeZone } from '@app/store/selectors/user-settings.selectors';
import { selectHostNames } from '@app/store/selectors/hosts.selectors';
import { SetUserSettingsAction } from '@app/store/actions/user-settings.actions';

@Component({
  selector: 'user-settings',
  templateUrl: './user-settings.component.html',
  styleUrls: ['./user-settings.component.less']
})
export class UserSettingsComponent implements OnInit, OnDestroy {

  @Input()
  visibilityQueryParamName = 'showUserSettings';

  demoHostName = 'subdomain01.company-domain.exp';
  
  visible$: Observable<boolean> = this.route.queryParams
    .map(params => params)
    .map((params): boolean => /^(show|yes|true|1)$/.test(params[this.visibilityQueryParamName]))
    .distinctUntilChanged();

  displayShortHostNames$: Observable<boolean> = this.store.select(selectDisplayShortHostNames);
  timeZone$: Observable<string> = this.store.select(selectTimeZone);

  hostNameExample$: Observable<string> = this.store.select(selectHostNames)
    .map((hostNames: string[]) => hostNames && hostNames.length ? hostNames[0] : this.demoHostName)
    .startWith(this.demoHostName);

  hostNameExampleShort$: Observable<string> = this.hostNameExample$.map((hostName: string) => hostName.split('.')[0])

  userSettingsFormGroup = new FormGroup({
    displayShortHostName: new FormControl()
  });

  form: FormGroup = new FormGroup({
    timeZone: new FormControl(),
    displayShortHostNames: new FormControl()
  });
  
  destroyed$: Subject<boolean> = new Subject();

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private store: Store<AppStore>
  ) { }

  ngOnInit() {
    this.displayShortHostNames$.distinctUntilChanged().takeUntil(this.destroyed$).subscribe(this.setDisplayShortHostNamesValue);
    this.timeZone$.distinctUntilChanged().takeUntil(this.destroyed$).subscribe(this.setTimeZoneValue);
    this.form.valueChanges.takeUntil(this.destroyed$).subscribe(this.onFormValueChanges);
  }

  ngOnDestroy() {
    this.destroyed$.next(true);
    this.destroyed$.complete();
  }

  handleCloseRequest = (clickEvent) => {
    clickEvent.preventDefault();
    clickEvent.stopPropagation();
    this.close();
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

  setFormValue(field, value, emitEvent = false) {
    if (this.form.controls[field]) {
      this.form.controls[field].setValue(value, {
        onlySelf: emitEvent,
        emitEvent
      });
    }
  }

  setDisplayShortHostNamesValue = (value) => {
    this.setFormValue('displayShortHostNames', value);
  }
  
  setTimeZoneValue = (value) => {
    this.setFormValue('timeZone', value, true);
  }

  onFormValueChanges = (form) => {
    this.store.dispatch(new SetUserSettingsAction( this.form.getRawValue() ));
  }

}
