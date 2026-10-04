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

// Render the expected host list, including hosts that have not returned a task yet.
function hostRows(selection, tasks, finished) {
  return selection.hosts.map(function (host) {
    var matching = tasks.filter(function (item) { return item.Tasks.host_name === host; });
    var task = matching.length === 1 ? matching[0].Tasks : {};
    var output = task.structured_out || {};
    if (typeof output === 'string') {
      try { output = JSON.parse(output); } catch (ignore) { output = {}; }
    }
    var checked = (output && output.upgrade_java) || {};
    var primary = checked.primary || {}, secondary = checked.secondary || {};
    var done = finished || ['COMPLETED', 'FAILED', 'ABORTED', 'TIMEDOUT'].indexOf(task.status) !== -1;
    var fresh = task.end_time > 0 && Date.now() - task.end_time < 30 * 60 * 1000;
    // A failed task may still contain a successful result for one of the two JDKs.
    var hasResults = fresh && ['COMPLETED', 'FAILED'].indexOf(task.status) !== -1;
    var primaryValid = !!(hasResults && primary.valid && primary.home === selection.primary && primary.major === selection.primaryMajor);
    var secondaryValid = !!(hasResults && secondary.valid && secondary.home === selection.secondary && secondary.major === selection.secondaryMajor);
    var valid = task.status === 'COMPLETED' && primaryValid && secondaryValid;
    var statusKey = valid ? 'passed' : done ? (task.status === 'TIMEDOUT' ? 'timedOut' : 'failed') :
      (task.status === 'IN_PROGRESS' ? 'running' : 'pending');
    var fallback = done ? Em.I18n.t('admin.upgrade.java.noResult') : '';
    var expired = task.end_time > 0 && !fresh ? Em.I18n.t('admin.upgrade.java.expired') : '';
    return {
      host: host, valid: valid, running: !done,
      statusLabel: Em.I18n.t('admin.upgrade.java.' + statusKey),
      progressClass: valid ? 'progress-bar-success' : done ? 'progress-bar-danger' : 'progress-bar-info',
      primary: expired || primary.message || fallback, secondary: expired || secondary.message || fallback,
      primaryValid: primaryValid, secondaryValid: secondaryValid,
      primaryFailed: done && !primaryValid, secondaryFailed: done && !secondaryValid
    };
  });
}

module.exports = {
  showAdministration: function () {
    var self = this;
    return App.ajax.send({name: 'admin.java.get', sender: this}).done(function (data) {
      self.show(null, data.JavaRuntime, null, true);
    });
  },

  show: function (version, requirements, proceed, administration) {
    var popup = App.ModalPopup.show({
      header: administration ? Em.I18n.t('admin.java.title') : Em.I18n.t('admin.upgrade.java.title').format(requirements.min_version),
      modalDialogClasses: ['upgrade-java-dialog'],
      introduction: Em.I18n.t('admin.upgrade.java.introduction').format(requirements.min_version),
      primaryMinimum: Em.I18n.t('admin.upgrade.java.minimum').format(requirements.primary_java_major),
      secondaryMinimum: Em.I18n.t('admin.upgrade.java.minimum').format(requirements.secondary_java_major),
      activationMessage: Em.I18n.t(administration ? 'admin.java.activation' : 'admin.upgrade.java.activation'),
      validatedMessage: Em.I18n.t(administration ? 'admin.java.validated' : 'admin.upgrade.java.validated'),
      primary: Em.I18n.t(administration ? 'common.save' : 'common.proceed'),
      secondary: Em.I18n.t('common.cancel'),
      primaryHome: requirements.primary_java_home || '',
      secondaryHome: requirements.secondary_java_home || '',
      primaryMajor: requirements.primary_java_major,
      secondaryMajor: requirements.secondary_java_major,
      checking: false,
      saving: false,
      validated: false,
      requestId: null,
      rows: [],
      hostCount: Em.computed.alias('rows.length'),
      primarySuccessCount: function () {
        return this.get('rows').filterProperty('primaryValid', true).length;
      }.property('rows.@each.primaryValid'),
      secondarySuccessCount: function () {
        return this.get('rows').filterProperty('secondaryValid', true).length;
      }.property('rows.@each.secondaryValid'),
      error: '',
      generation: 0,
      draftName: administration ? null : 'upgrade-java-' + App.get('clusterName') + '-' + version.get('id'),
      draftExists: false,
      loadingDraft: !administration,
      inputsDisabled: Em.computed.or('loadingDraft', 'saving'),
      disablePrimary: function () { return !this.get('validated') || this.get('saving'); }.property('validated', 'saving'),
      disableCheck: function () {
        return this.get('inputsDisabled') || this.get('checking') || !this.get('primaryHome') || !this.get('secondaryHome');
      }.property('inputsDisabled', 'checking', 'primaryHome', 'secondaryHome'),
      // Settings is database-backed. It stores preparation, never active Java homes.
      loadDraft: function () {
        var self = this, generation = this.get('generation');
        App.ajax.send({name: 'admin.upgrade.java.draft.get', sender: this, error: 'draftRequestError',
          data: {draftName: encodeURIComponent(this.get('draftName'))}}).done(function (data) {
          if (generation !== self.get('generation')) return;
          self.setProperties({loadingDraft: false, draftExists: true});
          try {
            var draft = JSON.parse(data.Settings.content);
            if (draft.primaryMajor !== self.get('primaryMajor') || draft.secondaryMajor !== self.get('secondaryMajor')) return;
            self.setProperties({primaryHome: draft.primary, secondaryHome: draft.secondary});
            if (draft.requestId) self.check(draft.requestId);
          } catch (ignore) {
            self.set('error', Em.I18n.t('admin.upgrade.java.draftInvalid'));
          }
        }).fail(function (request) {
          if (generation !== self.get('generation')) return;
          self.set('loadingDraft', false);
          if (request.status !== 404) self.set('error', Em.I18n.t('admin.upgrade.java.draftFailed'));
        });
      },
      draftRequestError: function () {}, // Handle expected missing drafts locally.
      failCheck: function (message) {
        this.get('rows').filterProperty('running', true).forEach(function (row) {
          Em.setProperties(row, {running: false, progressClass: 'progress-bar-danger',
            statusLabel: Em.I18n.t('admin.upgrade.java.failed'),
            primaryFailed: !row.primaryValid, secondaryFailed: !row.secondaryValid});
        });
        this.setProperties({checking: false, validated: false, error: message});
      },
      saveDraft: function (generation, selection) {
        if (administration) {
          this.setProperties({checking: false, validated: true});
          return;
        }
        var self = this;
        var draft = {primary: selection.primary, secondary: selection.secondary,
          primaryMajor: selection.primaryMajor, secondaryMajor: selection.secondaryMajor,
          requestId: this.get('requestId')};
        App.ajax.send({name: this.get('draftExists') ? 'admin.upgrade.java.draft.update' : 'admin.upgrade.java.draft.create',
          sender: this, error: 'draftRequestError', data: {draftName: encodeURIComponent(this.get('draftName')),
            settings: {name: this.get('draftName'), setting_type: 'UPGRADE_JAVA', content: JSON.stringify(draft)}}
        }).done(function () {
          if (generation === self.get('generation')) self.setProperties({draftExists: true, checking: false, validated: true});
        }).fail(function () {
          if (generation === self.get('generation')) self.setProperties({checking: false, validated: false,
            error: Em.I18n.t('admin.upgrade.java.draftFailed')});
        });
      },
      pathsChanged: function () {
        this.incrementProperty('generation');
        this.setProperties({validated: false, checking: false, requestId: null, rows: [], error: ''});
      }.observes('primaryHome', 'secondaryHome'),
      bodyClass: Em.View.extend({
        templateName: require('templates/main/admin/stack_upgrade/upgrade_java'),
        check: function () { this.get('parentView').check(); }
      }),
      check: function (savedRequestId) {
        var self = this;
        var generation = this.incrementProperty('generation');
        var selection = {
          primary: this.get('primaryHome'), secondary: this.get('secondaryHome'),
          primaryMajor: this.get('primaryMajor'), secondaryMajor: this.get('secondaryMajor')
        };
        this.setProperties({checking: true, validated: false, rows: [], error: ''});
        var failed = function () {
          if (generation === self.get('generation')) {
            self.failCheck(Em.I18n.t('admin.upgrade.java.requestFailed'));
          }
        };
        App.ajax.send({name: 'admin.upgrade.java.hosts', sender: this}).done(function (data) {
          if (generation !== self.get('generation')) return;
          selection.hosts = data.items.map(function (host) { return host.Hosts.host_name; });
          if (!selection.hosts.length) return failed();
          self.set('rows', hostRows(selection, [], false));
          if (savedRequestId) {
            self.set('requestId', savedRequestId);
            self.poll(generation, selection, Date.now());
            return;
          }
          App.ajax.send({name: 'admin.upgrade.java.check', sender: self, data: selection}).done(function (request) {
            if (generation !== self.get('generation')) return;
            self.set('requestId', request.Requests.id);
            self.poll(generation, selection, Date.now());
          }).fail(failed);
        }).fail(failed);
      },
      poll: function (generation, selection, started) {
        var self = this;
        if (generation !== this.get('generation')) return;
        App.ajax.send({name: 'admin.upgrade.java.results', sender: this, data: {requestId: this.get('requestId')}}).done(function (data) {
          if (generation !== self.get('generation')) return;
          var tasks = data.tasks || [];
          var terminal = ['COMPLETED', 'FAILED', 'ABORTED', 'TIMEDOUT'].indexOf(data.Requests.request_status) !== -1;
          var finished = terminal || Date.now() - started >= 120000;
          var rows = hostRows(selection, tasks, finished);
          self.set('rows', rows);
          if (!finished) {
            Em.run.later(function () { self.poll(generation, selection, started); }, 2000);
            return;
          }
          var valid = terminal && tasks.length === selection.hosts.length && rows.every(function (row) { return row.valid; });
          if (valid) {
            self.saveDraft(generation, selection);
          } else {
            self.setProperties({checking: false, validated: false, error: Em.I18n.t('admin.upgrade.java.checkFailed')});
          }
        }).fail(function () {
          if (generation === self.get('generation')) {
            self.failCheck(Em.I18n.t('admin.upgrade.java.requestFailed'));
          }
        });
      },
      onPrimary: function () {
        if (this.get('disablePrimary')) return;
        if (administration) {
          var self = this;
          this.setProperties({saving: true, error: ''});
          App.ajax.send({name: 'admin.java.save', sender: this, error: 'draftRequestError', data: {runtime: {
            primary_java_home: this.get('primaryHome'), secondary_java_home: this.get('secondaryHome'),
            validation_request_id: this.get('requestId'), expected_java_home: requirements.java_home,
            expected_primary_java_home: requirements.primary_java_home,
            expected_secondary_java_home: requirements.secondary_java_home
          }}}).done(function () {
            self.set('saving', false);
            self.onSecondary();
            App.showAlertPopup(Em.I18n.t('admin.java.title'), Em.I18n.t('admin.java.saved'));
          }).fail(function () {
            // A failed publication may follow a successful disk write. Reload actual state.
            self.setProperties({saving: false, validated: false, error: Em.I18n.t('admin.java.saveFailed')});
          });
          return;
        }
        version.setProperties({primaryJavaHome: this.get('primaryHome'), secondaryJavaHome: this.get('secondaryHome'),
          javaValidationRequestId: this.get('requestId')});
        this.incrementProperty('generation');
        this.hide();
        proceed();
      },
      onSecondary: function () { if (!this.get('saving')) { this.incrementProperty('generation'); this.hide(); } },
      onClose: function () { this.onSecondary(); }
    });
    if (!administration) popup.loadDraft();
    return popup;
  }
};
