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

/**
 * Creates the Bootstrap tooltips and popovers declared with data-bs-toggle. An element keeps the
 * tooltip or popover it already has (App.tooltip, App.popover): Bootstrap allows one per element, and
 * a second one would replace it while its tip is shown, which then never hides.
 * @param {number} delay
 */
function initBootstrapPopups(delay) {
  setTimeout(function () {
    var bootstrap = window.bootstrap;
    if (!bootstrap) {
      return;
    }
    var hasPopup = function (element) {
      return bootstrap.Tooltip.getInstance(element) || bootstrap.Popover.getInstance(element);
    };
    document.querySelectorAll('[data-bs-toggle="tooltip"]').forEach(function (element) {
      if (!hasPopup(element)) {
        new bootstrap.Tooltip(element);
      }
    });
    document.querySelectorAll('[data-bs-toggle="popover"]').forEach(function (element) {
      if (!hasPopup(element)) {
        new bootstrap.Popover(element);
      }
    });
  }, delay);
}

initBootstrapPopups(3000);

module.exports = initBootstrapPopups;
