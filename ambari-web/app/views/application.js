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


var App = require('app');

App.ApplicationView = Em.View.extend({
  templateName: require('templates/application'),

  views: function () {
    return App.router.get('loggedIn') ? App.router.get('mainViewsController.visibleAmbariViews') : [];
  }.property('App.router.mainViewsController.visibleAmbariViews.[]', 'App.router.loggedIn'),

  /**
   * The KDPS (Kubernetes Data Platform Services) view instance, surfaced as a dedicated top-nav
   * button so the console is one click away regardless of whether an ODP cluster is installed —
   * rather than being buried in the generic views (grid) dropdown. Resolves the deployed instance
   * from the loaded views list, so the URL follows the actual view version with no hardcoding, and
   * is {@code null} (button hidden) when the view is not deployed.
   * @type {App.ViewInstance|null}
   */
  kdpsView: function () {
    if (!App.router.get('loggedIn')) {
      return null;
    }
    var views = App.router.get('mainViewsController.visibleAmbariViews') || [];
    var candidates = views.filterProperty('viewName', 'K8S-VIEW');
    if (!candidates.length) {
      return null;
    }
    // Several versions of the view can be deployed at once (an older jar left behind, or a
    // just-upgraded server whose previous version is still registered). Always point the button at
    // the newest one, so a version bump never sends users to a stale instance.
    return candidates.sort(function (a, b) {
      return App.ApplicationView.compareViewVersions(b.get('version'), a.get('version'));
    })[0];
  }.property('App.router.mainViewsController.visibleAmbariViews.[]', 'App.router.loggedIn'),

  didInsertElement: function () {
    // on 'Enter' pressed, trigger modal window primary button if primary button is enabled(green)
    // on 'Esc' pressed, close the modal
    $(document).keydown(function (event) {
      if (event.which === 13 || event.keyCode === 13) {
        $('.modal:last').trigger('enter-key-pressed');
      }
      return true;
    });
    $(document).keyup(function (event) {
      if (event.which === 27 || event.keyCode === 27) {
        $('.modal:last').trigger('escape-key-pressed');
      }
      return true;
    });
  },

  /**
   * Navigation Bar should be initialized after cluster data is loaded
   */
  initNavigationBar: function () {
    if (App.get('router.mainController.isClusterDataLoaded')) {
      const observer = new MutationObserver(mutations => {
        var targetNode
        if (mutations.some((mutation) => mutation.type === 'childList' && (targetNode = $('.navigation-bar')).length)) {
          observer.disconnect();
          //initTooltips();
          targetNode.navigationBar({
            fitHeight: true,
            collapseNavBarClass: 'icon-double-angle-left',
            expandNavBarClass: 'icon-double-angle-rightt'
          });
        }
      });
 
      setTimeout(() => {
        // remove observer if selected element is not found in 10secs.
        observer.disconnect();
      }, 10000)
 
      observer.observe(document.body, {
        childList: true,
        subtree: true
      });
    }
  }.observes('App.router.mainController.isClusterDataLoaded')

});

App.ApplicationView.reopenClass({
  /**
   * Numeric, segment-wise comparison of dotted view versions ("1.0.0.10" > "1.0.0.8"); non-numeric
   * segments fall back to string order, missing segments count as 0.
   * @param {string} a
   * @param {string} b
   * @returns {number} negative when a < b, positive when a > b, 0 when equal
   */
  compareViewVersions: function (a, b) {
    var pa = String(a || '').split('.'), pb = String(b || '').split('.');
    var n = Math.max(pa.length, pb.length);
    for (var i = 0; i < n; i++) {
      var sa = pa[i] || '0', sb = pb[i] || '0';
      var na = parseInt(sa, 10), nb = parseInt(sb, 10);
      var bothNumeric = String(na) === sa && String(nb) === sb;
      if (bothNumeric) {
        if (na !== nb) {
          return na - nb;
        }
      } else if (sa !== sb) {
        return sa < sb ? -1 : 1;
      }
    }
    return 0;
  }
});
