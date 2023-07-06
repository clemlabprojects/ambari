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
import { FilterCondition, TimeUnitListItem } from '@app/classes/filtering';
import { ListItem } from '@app/classes/list-item';
import { HomogeneousObject } from '@app/classes/object';
import { LogsContainerService } from '@app/services/logs-container.service';
import { Router, ActivatedRoute } from '@angular/router';

import { Store } from '@ngrx/store';
import { Subject } from 'rxjs/Subject';
import { Observable } from 'rxjs/Observable';

import { AppStore } from '@app/classes/models/store';
import { LogOutAction } from '@app/store/actions/auth.actions';

import { selectMetadataPatternsFeatureState } from '@app/store/selectors/api-features.selectors';

@Component({
  selector: 'top-menu',
  templateUrl: './top-menu.component.html',
  styleUrls: ['./top-menu.component.less']
})
export class TopMenuComponent implements OnInit, OnDestroy {

  private items;
  
  metadataPatternsFeatureState$ = this.store.select(selectMetadataPatternsFeatureState).startWith(true);
  
  destroyed$: Subject<boolean> = new Subject();

  constructor(
    private logsContainer: LogsContainerService,
    private router: Router,
    private route: ActivatedRoute,
    private store: Store<AppStore>
  ) {}

  ngOnInit() {
    this.metadataPatternsFeatureState$.takeUntil(this.destroyed$).subscribe(this.onMetadataFeatureStateChange);
  }

  ngOnDestroy() {
    this.destroyed$.next(true);
    this.destroyed$.complete();
  }

  get filtersForm(): FormGroup {
    return this.logsContainer.filtersForm;
  };

  get filters(): HomogeneousObject<FilterCondition> {
    return this.logsContainer.filters;
  };

  openUserSettingsModal = (): void => {
    this.router.navigate(['.'], {
      queryParamsHandling: 'merge',
      queryParams: {showUserSettings: 'show'},
      relativeTo: this.route.root.firstChild
    });
  }

  onMetadataFeatureStateChange = (state: boolean) => {
    this.items = [{
      iconClass: 'fa fa-user grey',
      hideCaret: true,
      isRightAlign: true,
      subItems: [
        {
          label: 'common.settings',
          onSelect: this.openUserSettingsModal,
          iconClass: 'fa fa-cog'
        },

        {
          label: 'topMenu.shipperConfiguration',
          secondaryLabel: state ? '' : 'apiFeatures.disabled',
          onSelect: this.navigateToShipperConfig,
          iconClass: 'fa fa-file-code-o',
          disabled: !state
        },
        {
          isDivider: true
        },
        {
          label: 'authorization.logout',
          onSelect: this.logout,
          iconClass: 'fa fa-sign-out'
        }
      ]
    }];
  }

  openSettings = (): void => {};

  /**
   * Dispatch the LogOutAction.
   */
  logout = (): void => {
    this.store.dispatch(new LogOutAction());
  }

  navigateToShipperConfig = (): void => {
    this.router.navigate(['/shipper']);
  }

  get clusters(): (ListItem | TimeUnitListItem[])[] {
    return this.filters.clusters.options;
  }

  get isClustersFilterDisplayed(): boolean {
    return this.logsContainer.isFilterConditionDisplayed('clusters') && this.clusters.length > 1;
  }

}
