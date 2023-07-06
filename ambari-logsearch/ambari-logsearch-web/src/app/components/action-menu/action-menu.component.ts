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
import { FormGroup } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { Observable } from 'rxjs/Observable';
import { BehaviorSubject } from 'rxjs/BehaviorSubject';
import 'rxjs/operator/do';

import { LogsContainerService } from '@app/services/logs-container.service';
import { ServerSettingsService } from '@app/services/server-settings.service';
import { ListItem } from '@app/classes/list-item';
import { ClustersService } from '@app/services/storage/clusters.service';
import { UtilsService } from '@app/services/utils.service';
import { Subject } from 'rxjs/Subject';

import { Store } from '@ngrx/store';
import { AppStore } from '@app/classes/models/store';
import { selectLogLevelFiltersFeatureState } from '@app/store/selectors/api-features.selectors';

import { AddNotificationAction, NotificationActions } from '@app/store/actions/notification.actions';
import { NotificationType } from '@modules/shared/services/notification.service';

@Component({
  selector: 'action-menu',
  templateUrl: './action-menu.component.html',
  styleUrls: ['./action-menu.component.less']
})
export class ActionMenuComponent  implements OnInit, OnDestroy {

  logLevelFiltersFeatureState$: Observable<any> = this.store.select(selectLogLevelFiltersFeatureState);
  logLevelFiltersFeatureTooltip$: Observable<string> = this.logLevelFiltersFeatureState$.map((state: boolean) => (
    state ? '' : 'apiFeatures.disabled'
  ));

  isLogIndexFilterDisplayed$: Observable<boolean> = Observable.combineLatest(
    this.route.queryParams
      .map((params) => {
        return params;
      })
      .map((params): boolean => /^(show|yes|true|1)$/.test(params.logIndexFilterSettings)),
    this.logLevelFiltersFeatureState$
  ).do(([show, enabled]) => {
    if (show && !enabled) {
      this.store.dispatch(new AddNotificationAction({
        type: NotificationType.ERROR,
        message: 'apiFeatures.disabled'
      }))
    }
  }).map(([show, enabled]) => show && enabled).distinctUntilChanged();

  settingsForm: FormGroup = this.settings.settingsFormGroup;

  clustersListItems$: Observable<ListItem[]> = this.clustersService.getAll()
    .map((clusterNames: string[]): ListItem[] => clusterNames.map(this.utilsService.getListItemFromString))
    .map((clusters: ListItem[]) => {
      if (clusters.length && !clusters.some((item: ListItem) => item.isChecked)) {
        clusters[0].isChecked = true;
      }
      return clusters;
    });

  selectedClusterName$: BehaviorSubject<string> = new BehaviorSubject('');
  isModalSubmitDisabled$: Observable<boolean> = this.selectedClusterName$.map(cluster => !cluster);

  destroyed$ = new Subject();

  constructor(
    private logsContainerService: LogsContainerService,
    private settings: ServerSettingsService,
    private route: ActivatedRoute,
    private router: Router,
    private clustersService: ClustersService,
    private utilsService: UtilsService,
    private store: Store<AppStore>
  ) {
  }

  ngOnInit() {
    this.clustersListItems$.filter((items: ListItem[]) => items.some((item: ListItem) => item.isChecked)).take(1)
      .map((items: ListItem[]) => items.find((item: ListItem) => item.isChecked))
      .subscribe((item) => this.selectedClusterName$.next(item.value));
  }

  ngOnDestroy() {
    this.destroyed$.next(true);
  }

  get captureSeconds(): number {
    return this.logsContainerService.captureSeconds;
  }

  refresh(): void {
    this.logsContainerService.loadLogs();
  }

  onSelectCluster(cluster: string) {
    this.selectedClusterName$.next(cluster);
  }

  openLogIndexFilter(): void {
    this.router.navigate(['.'], {
      queryParamsHandling: 'merge',
      queryParams: {logIndexFilterSettings: 'show'},
      relativeTo: this.route.root.firstChild
    });
  }

  closeLogIndexFilter(): void {
    this.route.queryParams.take(1).subscribe((queryParams) => {
      const {logIndexFilterSettings, ...params} = queryParams;
      this.router.navigate(['.'], {
        queryParams: params,
        relativeTo: this.route.root.firstChild
      });
    });
  }

  saveLogIndexFilter(): void {
    this.closeLogIndexFilter();
    this.settings.saveIndexFilterConfig();
  }

  startCapture(): void {
    this.logsContainerService.startCaptureTimer();
  }

  stopCapture(): void {
    this.logsContainerService.stopCaptureTimer();
  }

  cancelCapture(): void {
    this.logsContainerService.cancelCapture();
  }

}
