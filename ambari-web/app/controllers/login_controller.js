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

App.LoginController = Em.Object.extend({

  name: 'loginController',

  loginName: '',
  password: '',

  errorMessage: '',

  isSubmitDisabled: false,

  /**
   * Bound to App.router.oidcSignInUrl so any change made by router.onAuthenticationError
   * propagates here without us having to wire a manual observer.  Ember 1.x reactive
   * computed properties do NOT walk global property paths (e.g. 'App.router.X') the way
   * a Binding does — we need this explicit Binding for the chooser button to flip on
   * after the 403 round-trip populates the URL.
   */
  oidcSignInUrlBinding: 'App.router.oidcSignInUrl',

  /**
   * True when the login page should offer a "Sign in with SSO" button alongside
   * the local username/password form.  Sourced from oidcSignInUrl above, which
   * AmbariErrorHandler's 403 response populates whenever jwtProviderType === "oidc"
   * (i.e. only when Keycloak/OIDC is enabled — Knox clusters get the legacy
   * hard-redirect and never reach this template, so the button stays hidden).
   */
  showOidcButton: Em.computed.bool('oidcSignInUrl'),

  /**
   * Navigates the browser to Ambari's /oidc/begin endpoint with the current
   * page URL appended as the returnUrl query parameter, mirroring how the
   * legacy auto-redirect path constructs the URL.
   */
  signInWithOidc: function () {
    var base = this.get('oidcSignInUrl');
    if (!base) {
      return;
    }
    window.location.href = base + encodeURIComponent(App.router.getCurrentLocationUrl());
  },

  submit: function (e) {
    this.set('errorMessage', '');
    this.set('isSubmitDisabled', true);
    //Hack to set username and password when user has not focussed on the element and is using previous values
    let username="";
    let password="";
    if(document&&document.getElementsByClassName("login-username")[0]){
      username=document.getElementsByClassName("login-username")[0].value;
    }
    if(document&&document.getElementsByClassName("login-password")[0]){
      password=document.getElementsByClassName("login-password")[0].value;
    }

    if(username){
      this.set('loginName',username);
    }
    if(password){
      this.set('password',password);
    }
    App.get('router').login();
  },

  postLogin: function (isConnected, isAuthenticated, responseText) {
    var errorMessage = "";
    if (!isConnected) {
      this.set('errorMessage', responseText || Em.I18n.t('login.error.bad.connection'));
    } else if (!isAuthenticated) {
      if (responseText === "User is disabled") {
        errorMessage = Em.I18n.t('login.error.disabled');
      } else if (responseText === "Authentication required" || Em.isNone(responseText)) {
        errorMessage = Em.I18n.t('login.error.bad.credentials');
      } else {
        errorMessage = responseText;
      }
      this.set('errorMessage', errorMessage);
    }
    this.set('isSubmitDisabled', false);
  }

});
