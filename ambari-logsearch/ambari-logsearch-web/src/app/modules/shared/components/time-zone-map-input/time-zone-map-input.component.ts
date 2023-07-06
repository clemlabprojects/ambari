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
import {
  Component,
  Input,
  Output,
  EventEmitter,
  forwardRef,
  OnChanges,
  SimpleChanges,
  ViewChild
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

import * as moment from 'moment-timezone';
import Timezone from '@modules/shared/interfaces/timezone';

import timezoneList from './time-zone-map-input.data';
import { BehaviorSubject } from 'rxjs/BehaviorSubject';
import { ListItem } from '@app/classes/list-item';
import { DropdownButtonComponent } from '../dropdown-button/dropdown-button.component';

@Component({
  selector: 'time-zone-map-input',
  templateUrl: './time-zone-map-input.component.html',
  styleUrls: ['./time-zone-map-input.component.less'],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => TimeZoneMapInputComponent),
      multi: true
    }
  ]
})
export class TimeZoneMapInputComponent implements ControlValueAccessor, OnChanges {

  @Input()
  value = moment.tz.guess();
  
  @Input()
  mapElementId = 'timeZoneMap';

  @Input()
  timezonesData: Timezone[] = timezoneList;
  
  @Input()
  quickLinks = [{
    zonename: 'PST'
  }, {
    zonename: 'MST'
  }, {
    zonename: 'CST'
  }, {
    zonename: 'EST'
  }, {
    zonename: 'GMT'
  }, {
    label: 'London',
    timezone: 'Europe/London'
  }, {
    zonename: 'IST'
  }];

  @Output()
  onChange = new EventEmitter();

  selectedTimezone: BehaviorSubject<Timezone> = new BehaviorSubject(this.getTimezonesDataByTimezone(this.value));

  private _controlValueAccessorOnchange;

  @ViewChild(DropdownButtonComponent)
  private _dropdownRef: DropdownButtonComponent;

  dropdownItems$: BehaviorSubject<ListItem[]> = new BehaviorSubject(this.gestListItemsFromTimezonesData(this.timezonesData));

  _hoverTimezoneAbbr$: BehaviorSubject<string> = new BehaviorSubject((
    this.getTimezonesDataByTimezone(this.value) || {zonename: ''}
  ).zonename);
  
  constructor() { }
  
  ngOnChanges(change: SimpleChanges) {
    if (change.value) {
      this.selectedTimezone.next(this.getTimezonesDataByTimezone(this.value));
    }
    if (change.timezonesData) {
      this.dropdownItems$.next(this.gestListItemsFromTimezonesData(this.timezonesData));
    }
  }

  private _onChange(value: string) {
    this.selectedTimezone.next(this.getTimezonesDataByTimezone(value));
    this.onChange.emit(value);
    if (this._controlValueAccessorOnchange) {
      this._controlValueAccessorOnchange(value);
    }
    this._dropdownRef.selection = [{label: value, value}];
  }

  handleClickOnCountry(timezoneData, event) {
    if (event) {
      event.stopPropagation();
      event.preventDefault();
    }
    this.value = timezoneData.timezone;
    this._onChange(this.value);
  }

  writeValue(value: any): void {
    this.value = value;
  }

  registerOnChange(fn: any): void {
    this._controlValueAccessorOnchange = fn;
  }

  registerOnTouched(fn: any): void {

  }

  getTimezonesDataByTimezone(timezone: string): Timezone {
    return this.timezonesData.find((currentTimezone: Timezone) => currentTimezone.timezone === timezone);
  }

  gestListItemsFromTimezonesData(data: Timezone[]): ListItem[] {
    const d = data.map((timezoneData: Timezone) => ({
      value: timezoneData.timezone,
      label: timezoneData.timezone,
      isChecked: timezoneData.timezone === this.value
    }));
    return d;
  }

  handleDropDownSelection(event: any) {
    this.value = event.target.value;
    this._onChange(this.value);
  }

  handleQuickLinkClick(value, event) {
    if (event) {
      event.stopPropagation();
      event.preventDefault();
    }
    const selectedTimeZone = this.timezonesData.find(
      (timezoneData: Timezone) => (value.timezone && timezoneData.timezone === value.timezone) || timezoneData.zonename === value.zonename
    );
    const newValue = selectedTimeZone && selectedTimeZone.timezone;
    if (newValue && this.value !== newValue) {
      this.value = newValue;
      this._onChange(this.value);
    }
  }

  handleMouseEnterOnCountry(value, event) {
    if (event) {
      event.stopPropagation();
      event.preventDefault();
    }
    this._hoverTimezoneAbbr$.next(value.zonename);
  }
  
  handleMouseLeaveOnCountry(value, event) {
    if (event) {
      event.stopPropagation();
      event.preventDefault();
    }
    this._hoverTimezoneAbbr$.next('');
  }

}
