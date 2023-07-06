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

import { Input, OnInit } from '@angular/core';
import { FormGroup } from '@angular/forms';
import { ListItem } from '@app/classes/list-item';
import { ServiceLog } from '@app/classes/models/service-log';
import { AuditLog } from '@app/classes/models/audit-log';

import * as moment from 'moment-timezone';

export class LogsTableComponent implements OnInit {
  @Input()
  logs: ServiceLog[] | AuditLog[] = [];

  @Input()
  columns: ListItem[] = [];

  @Input()
  filtersForm: FormGroup;

  @Input()
  totalCount = 0;

  @Input()
  timeZone: string = moment.tz.guess();

  get displayedColumns(): ListItem[] {
    return this.columns ? this.columns.filter((column: ListItem): boolean => column.isChecked) : [];
  };

  columnLabels: {[key: string]: string} = {};

  ngOnInit() {
    this.mapColumnLabelsToLocalCopy(this.columns);
  }

  mapColumnLabelsToLocalCopy(columns) {
    this.columnLabels = (columns || []).reduce( (labels, column): {[key: string]: string}  => {
      return {
        ...labels,
        [column.value]: column.label || column.value
      };
    }, {});
  }

  isColumnDisplayed(key: string): boolean {
    return this.displayedColumns.some((column: ListItem): boolean => column.value === key);
  }

  getColumnLabel(key: string): string {
    return this.columnLabels[key] || key;
  }

}
