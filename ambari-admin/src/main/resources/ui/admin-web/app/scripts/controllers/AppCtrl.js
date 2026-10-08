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
'use strict';

angular.module('ambariAdminConsole')
.controller('AppCtrl',['$scope','$rootScope', '$route', '$window','Auth', 'Alert', '$modal', 'Cluster', '$translate', '$http', 'Settings', 'Utility', '$q', function($scope, $rootScope, $route, $window, Auth, Alert, $modal, Cluster, $translate, $http, Settings, Utility, $q) {
  var $t = $translate.instant;

  /**
   * KDPS console button in the top navigation.
   *
   * This Admin console is a separate app from ambari-web, so the button ambari-web puts in its own
   * navbar is absent here — which is exactly the "Welcome to Apache Ambari" screen an operator lands
   * on when no cluster is installed yet, i.e. the moment KDPS is most likely the thing they came for.
   *
   * The URL comes from the instance's context_path rather than being built from name/version, so it
   * follows whatever view version is deployed with nothing hardcoded. Left null (button hidden) when
   * the view is absent or not visible, so a stock Ambari is unaffected.
   */
  $scope.kdpsViewUrl = null;

  /** Compares dotted view versions ("1.0.0.10" > "1.0.0.9"); same rule as ambari-web's compareViewVersions. */
  function compareViewVersions(a, b) {
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

  $http.get(Settings.baseUrl + '/views/K8S-VIEW?fields=versions/instances/ViewInstanceInfo')
    .then(function (resp) {
      // Several versions can be deployed at once (an older jar left after an upgrade): open the newest, as the
      // Ambari dashboard's KDPS button does, so a version bump never sends users to a stale instance.
      var versions = ((resp.data && resp.data.versions) || []).slice().sort(function (a, b) {
        return compareViewVersions((b.ViewVersionInfo || {}).version, (a.ViewVersionInfo || {}).version);
      });
      for (var i = 0; i < versions.length; i++) {
        var instances = versions[i].instances || [];
        for (var j = 0; j < instances.length; j++) {
          var info = instances[j].ViewInstanceInfo || {};
          if (info.visible === false || !info.context_path) {
            continue;
          }
          // Trailing slash required: the view's page loads its scripts as ./assets/..., which without it
          // resolve one directory too high (404, blank page). ambari-web's view links add it the same way.
          $scope.kdpsViewUrl = Settings.siteRoot.replace(/\/$/, '') + info.context_path.replace(/\/?$/, '/');
          return;
        }
      }
    })
    .catch(function () {
      $scope.kdpsViewUrl = null;   // view not deployed on this Ambari: just don't offer the button
    });

  $scope.signOut = function () {
    Auth.signout().finally(function () {
      $window.location.pathname = Settings.siteRoot;
    });
  };

  //todo replace with breadcrumb service
  $scope.$watch(function () {
    return $route.current;
  }, function (value) {
    var breadcrumbs = [$t('common.admin')];
    if (value && value.$$route && value.$$route.label) {
      breadcrumbs.push(value.$$route.label);
    }
    $scope.breadcrumbs = breadcrumbs;
  });


  $scope.ambariVersion = null;
  $rootScope.supports = {};
  $rootScope.authDataLoad = $q.defer();

  Utility.getUserPref('user-pref-' + Auth.getCurrentUser() + '-supports').then(function (data) {
    $rootScope.supports = data.data ? data.data : {};
  });

  $http.get(Settings.baseUrl + '/users/' + Auth.getCurrentUser() + '/authorizations?fields=*')
    .then(function (data) {
      var auth = !!data.data && !!data.data.items ? data.data.items.map(function (a) {
          return a.AuthorizationInfo.authorization_id;
        }) : [],
        canPersistData = auth.indexOf('CLUSTER.MANAGE_USER_PERSISTED_DATA') > -1;
      $rootScope.authDataLoad.resolve(canPersistData);
      if (auth.indexOf('AMBARI.RENAME_CLUSTER') == -1) {
        $window.location = $rootScope.fromSiteRoot("/#/main/dashboard");
      }
    });

  $scope.about = function () {
    var ambariVersion = $scope.ambariVersion;
    var modalInstance = $modal.open({
      templateUrl: 'views/modals/AboutModal.html',
      controller: ['$scope', function ($scope) {
        $scope.ok = function () {
          modalInstance.close();
        };
        $scope.ambariVersion = ambariVersion;
      }]
    });
  };

  $scope.currentUser = Auth.getCurrentUser();

  $scope.cluster = null;
  $scope.isLoaded = null;

  function loadAmbariVersion() {
    Cluster.getAmbariVersion().then(function (version) {
      $scope.ambariVersion = version;
    });
  }

  function loadClusterData() {
    Cluster.getStatus().then(function (cluster) {
      $rootScope.cluster = cluster;
      $scope.cluster = cluster;
      $scope.isLoaded = true;
      if (cluster && cluster.Clusters.provisioning_state === 'INIT') {
        setTimeout(loadClusterData, 1000);
      }
    }).catch(function (data) {
      Alert.error($t('common.alerts.cannotLoadClusterStatus'), data.statusText);
    });
  }

  loadClusterData();
  loadAmbariVersion();

  $scope.startInactiveTimeoutMonitoring = function (timeout) {
    var TIME_OUT = timeout;
    var active = true;
    var lastActiveTime = Date.now();

    var keepActive = function () {
      if (active) {
        lastActiveTime = Date.now();
      }
    };

    $(window).bind('mousemove', keepActive);
    $(window).bind('keypress', keepActive);
    $(window).bind('click', keepActive);

    var checkActiveness = function () {
      var remainTime = TIME_OUT - (Date.now() - lastActiveTime);
      if (remainTime < 0) {
        active = false;
        $(window).unbind('mousemove', keepActive);
        $(window).unbind('keypress', keepActive);
        $(window).unbind('click', keepActive);
        clearInterval($rootScope.userActivityTimeoutInterval);
        $scope.signOut();
      } else if (remainTime < 60000 && !$rootScope.timeoutModal) {
        $rootScope.timeoutModal = $modal.open({
          templateUrl: 'views/modals/TimeoutWarning.html',
          backdrop: false,
          controller: ['$scope', 'Auth', function ($scope, Auth) {
            $scope.remainTime = 60;
            $scope.title = $t('main.autoLogOut');
            $scope.primaryText = $t('main.controls.remainLoggedIn');
            $scope.secondaryText = $t('main.controls.logOut');
            $scope.remain = function () {
              $rootScope.timeoutModal.close();
              delete $rootScope.timeoutModal;
            };
            $scope.logout = function () {
              $rootScope.timeoutModal.close();
              delete $rootScope.timeoutModal;
              Auth.signout().finally(function () {
                $window.location.pathname = Settings.siteRoot;
              });
            };
            $scope.countDown = function () {
              $scope.remainTime--;
              $scope.$apply();
              if ($scope.remainTime == 0) {
                Auth.signout().finally(function () {
                  $window.location.pathname = Settings.siteRoot;
                });
              }
            };
            setInterval($scope.countDown, 1000);
          }]
        });
      }
    };
    $rootScope.userActivityTimeoutInterval = window.setInterval(checkActiveness, 1000);
  };

  // Send noop requests every 10 seconds just to keep backend session alive
  $scope.startNoopPolling = function () {
    $rootScope.noopPollingInterval = setInterval(Cluster.getAmbariTimeout, 10000);
  };

  if (!$rootScope.userActivityTimeoutInterval) {
    Cluster.getAmbariTimeout().then(function (timeout) {
      $rootScope.userTimeout = Number(timeout) * 1000;
      if ($rootScope.userTimeout > 0)
        $scope.startInactiveTimeoutMonitoring($rootScope.userTimeout);
    });
  }
  if (!$rootScope.noopPollingInterval) {
    $scope.startNoopPolling();
  }
}]);
