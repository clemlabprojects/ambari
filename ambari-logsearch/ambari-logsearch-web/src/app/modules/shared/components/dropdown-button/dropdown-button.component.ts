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

import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core';
import { ListItem } from '@app/classes/list-item';
import { UtilsService } from '@app/services/utils.service';

@Component({
  selector: 'dropdown-button',
  templateUrl: './dropdown-button.component.html',
  styleUrls: ['./dropdown-button.component.less']
})
export class DropdownButtonComponent implements OnChanges {
  @Input()
  label?: string;

  @Input()
  buttonClass = 'btn-link';

  @Input()
  iconClass?: string;

  @Input()
  hideCaret = false;

  @Input()
  showSelectedValue = true;

  @Input()
  isRightAlign = false;

  @Input()
  isDropup = false;

  @Input()
  showCommonLabelWithSelection = false;

  @Output()
  selectItem: EventEmitter<any> = new EventEmitter();

  // PROXY PROPERTIES TO DROPDOWN LIST COMPONENT
  @Input()
  options: ListItem[] = [];

  @Input()
  listItemArguments: any[] = [];

  @Input()
  isMultipleChoice = false;

  @Input()
  useClearToDefaultSelection = false;

  @Input()
  showTotalSelection = false;

  @Input()
  useDropDownLocalFilter = false;

  @Input()
  closeOnSelection = true;

  @Input()
  disabled = false;

  protected selectedItems: ListItem[] = [];

  get selection(): ListItem[] {
    return this.selectedItems;
  }

  set selection(items: ListItem[]) {
    this.selectedItems = <ListItem[]>(Array.isArray(items) ? items : items || []);
    if (this.selectedItems.length > 1 && !this.isMultipleChoice) {
      this.selectedItems = this.selectedItems.slice(0, 1);
    }
    if (this.isMultipleChoice && this.options) {
      this.options.forEach(
        (option: ListItem): void => {
          const selectionItem = this.selectedItems.find(
            (item: ListItem): boolean => this.utils.isEqual(item.value, option.value)
          );
          option.isChecked = !!selectionItem;
        }
      );
    }
  }

  get hasSelection(): boolean {
    return this.selectedItems.length > 0;
  }

  get totalSelection(): number {
    return this.selectedItems.length;
  }

  // TODO handle case of selections with multiple items
  /**
   * Indicates whether selection can be displayed at the moment, i.e. it's not empty, not multiple
   * and set to be displayed by showSelectedValue flag
   * @returns {boolean}
   */
  get isSelectionDisplayable(): boolean {
    return this.showSelectedValue && !this.isMultipleChoice && this.hasSelection;
  }

  get value(): any {
    const values = this.selectedItems && this.selectedItems.length && this.selectedItems.map(item => item.value);
    return this.isMultipleChoice ? values : values[0];
  }

  constructor(protected utils: UtilsService) {}

  ngOnChanges(changes: SimpleChanges) {
    if (changes.options) {
      this.hanldeOptionsChange();
    }
  }

  hanldeOptionsChange() {
    this.filterAndSetSelection();
  }

  clearSelection(silent: boolean = false) {
    let hasChange = false;
    this.options.forEach((item: ListItem) => {
      hasChange = hasChange || item.isChecked;
      item.isChecked = false;
    });
    if (!silent && hasChange) {
      this.selectItem.emit(this.isMultipleChoice ? [] : undefined);
    }
  }

  updateSelection(updates: ListItem | ListItem[]): boolean {
    let hasChange = false;
    if (updates && (!Array.isArray(updates) || updates.length)) {
      const items: ListItem[] = Array.isArray(updates) ? updates : [updates];
      if (this.isMultipleChoice) {
        items.forEach((item: ListItem) => {
          if (this.options && this.options.length) {
            const itemToUpdate: ListItem = this.options.find((option: ListItem) =>
              this.utils.isEqual(option.value, item.value)
            );
            if (itemToUpdate) {
              hasChange = hasChange || itemToUpdate.isChecked !== item.isChecked;
              itemToUpdate.isChecked = item.isChecked;
            }
          }
        });
      } else {
        const selectedItem: ListItem = Array.isArray(updates) ? updates[0] : updates;
        this.options.forEach((item: ListItem) => {
          const checkedStateBefore = item.isChecked;
          item.isChecked = this.utils.isEqual(item.value, selectedItem.value);
          hasChange = hasChange || checkedStateBefore !== item.isChecked;
        });
      }
    } else {
      this.options.forEach((item: ListItem) => (item.isChecked = false));
    }
    this.filterAndSetSelection();
    if (hasChange) {
      const selectedValues = this.selection.map((option: ListItem): any => option.value);
      this.selectItem.emit(this.isMultipleChoice ? selectedValues : selectedValues.shift());
    }
    return hasChange;
  }

  protected filterAndSetSelection() {
    const checkedItems = this.options.filter((option: ListItem): boolean => option.isChecked);
    this.selection = checkedItems;
  }
}
