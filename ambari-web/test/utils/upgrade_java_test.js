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
var selector = require('utils/upgrade_java');

describe('Upgrade JDK selector', function () {
  var popup, version, proceed, pending;
  beforeEach(function () {
    pending = [];
    sinon.stub(App.ModalPopup, 'show', function (definition) {
      popup = Em.Object.create(definition);
      popup.hide = sinon.spy();
      return popup;
    });
    App.ajax.send.restore();
    sinon.stub(App.ajax, 'send', function (options) {
      var deferred = $.Deferred();
      pending.push({options: options, deferred: deferred});
      return deferred.promise();
    });
    version = Em.Object.create({id: 123});
    proceed = sinon.spy();
    selector.show(version, {min_version: '1.3.2.0', primary_java_major: 17, secondary_java_major: 21,
      primary_java_home: '/jdk17', secondary_java_home: '/jdk21'}, proceed);
    pending.shift().deferred.reject({status: 404});
  });
  afterEach(function () {
    popup.incrementProperty('generation');
    App.ModalPopup.show.restore();
    popup.destroy();
  });
  function result(status, hosts, endTime) {
    return {Requests: {request_status: status}, tasks: hosts.map(function (host) {
      return {Tasks: {host_name: host, status: status, end_time: endTime || Date.now(), structured_out: {upgrade_java: {
        primary: {home: '/jdk17', major: 17, valid: true, message: 'Java 17'},
        secondary: {home: '/jdk21', major: 21, valid: true, message: 'Java 21'}
      }}}};
    })};
  }
  function complete(status, hosts, leaveSavePending) {
    popup.check();
    pending[0].deferred.resolve({items: [{Hosts: {host_name: 'host1'}}, {Hosts: {host_name: 'host2'}}]});
    pending[1].deferred.resolve({Requests: {id: 42}});
    pending[2].deferred.resolve(result(status, hosts));
    if (pending[3] && !leaveSavePending) pending[3].deferred.resolve({});
  }
  function administration() {
    popup.destroy();
    selector.show(null, {min_version: '1.3.2.0', primary_java_major: 17, secondary_java_major: 21,
      java_home: '/jdk17', primary_java_home: '/jdk17', secondary_java_home: '/jdk21'}, null, true);
  }
  it('administration checks both homes without writing an upgrade draft or active configuration', function () {
    administration();
    expect(pending.length).to.equal(0);
    complete('COMPLETED', ['host1', 'host2']);
    expect(pending.length).to.equal(3);
    expect(popup.get('disablePrimary')).to.equal(false);
    expect(popup.get('validatedMessage')).to.equal(Em.I18n.t('admin.java.validated'));
  });
  it('administration Save submits trusted proof and original paths only after successful checks', function () {
    administration();
    popup.onPrimary();
    expect(pending.length).to.equal(0);
    complete('COMPLETED', ['host1', 'host2']);
    popup.onPrimary();
    expect(pending[3].options.name).to.equal('admin.java.save');
    expect(pending[3].options.data.runtime.validation_request_id).to.equal(42);
    expect(pending[3].options.data.runtime.expected_primary_java_home).to.equal('/jdk17');
    expect(popup.get('inputsDisabled')).to.equal(true);
    popup.onPrimary();
    popup.onSecondary();
    expect(pending.length).to.equal(4);
    expect(popup.hide.called).to.equal(false);
    pending[3].deferred.reject({status: 500});
    expect(popup.get('saving')).to.equal(false);
    expect(popup.get('disablePrimary')).to.equal(true);
    expect(popup.get('error')).to.equal(Em.I18n.t('admin.java.saveFailed'));
  });
  it('administration does not save failed or incomplete checks', function () {
    administration();
    complete('COMPLETED', ['host1']);
    popup.onPrimary();
    expect(pending.length).to.equal(3);
    expect(popup.get('disablePrimary')).to.equal(true);
  });
  it('administration only reports success after the Save response', function () {
    administration();
    complete('COMPLETED', ['host1', 'host2']);
    sinon.stub(App, 'showAlertPopup');
    try {
      popup.onPrimary();
      expect(App.showAlertPopup.called).to.equal(false);
      pending[3].deferred.resolve('');
      expect(App.showAlertPopup.calledOnce).to.equal(true);
      expect(popup.hide.calledOnce).to.equal(true);
    } finally {
      App.showAlertPopup.restore();
    }
  });
  it('blocks Proceed until both JDKs pass on every host', function () {
    expect(popup.get('disablePrimary')).to.equal(true);
    popup.onPrimary();
    expect(proceed.called).to.equal(false);
    complete('COMPLETED', ['host1', 'host2']);
    expect(popup.get('validated')).to.equal(true);
    expect(popup.get('primarySuccessCount')).to.equal(2);
    expect(popup.get('secondarySuccessCount')).to.equal(2);
    expect(popup.get('hostCount')).to.equal(2);
    popup.onPrimary();
    expect(version.get('javaValidationRequestId')).to.equal(42);
    expect(version.get('primaryJavaHome')).to.equal('/jdk17');
    expect(version.get('secondaryJavaHome')).to.equal('/jdk21');
    expect(proceed.calledOnce).to.equal(true);
  });
  it('invalidates successful validation when either path changes', function () {
    complete('COMPLETED', ['host1', 'host2']);
    popup.set('secondaryHome', '/another21');
    expect(popup.get('validated')).to.equal(false);
    expect(popup.get('requestId')).to.equal(null);
    expect(popup.get('disablePrimary')).to.equal(true);
    expect(popup.get('hostCount')).to.equal(0);
    expect(popup.get('primarySuccessCount')).to.equal(0);
    expect(popup.get('secondarySuccessCount')).to.equal(0);
  });
  it('rejects a missing host', function () {
    complete('COMPLETED', ['host1']);
    expect(popup.get('validated')).to.equal(false);
    expect(popup.get('hostCount')).to.equal(2);
    expect(popup.get('primarySuccessCount')).to.equal(1);
    expect(popup.get('rows')[1].progressClass).to.equal('progress-bar-danger');
    expect(popup.get('rows')[1].primaryFailed).to.equal(true);
  });
  it('rejects failed tasks', function () {
    complete('FAILED', ['host1', 'host2']);
    expect(popup.get('validated')).to.equal(false);
  });
  it('ignores responses for edited paths', function () {
    popup.check();
    popup.set('primaryHome', '/another17');
    pending[0].deferred.resolve({items: [{Hosts: {host_name: 'host1'}}]});
    expect(pending.length).to.equal(1);
    expect(popup.get('validated')).to.equal(false);
  });
  it('ignores responses after cancellation', function () {
    popup.check();
    popup.onSecondary();
    pending[0].deferred.resolve({items: [{Hosts: {host_name: 'host1'}}]});
    expect(pending.length).to.equal(1);
    expect(proceed.called).to.equal(false);
  });
  it('shows request failure and permits retry', function () {
    popup.check();
    pending[0].deferred.reject();
    expect(popup.get('checking')).to.equal(false);
    expect(popup.get('error')).not.to.equal('');
    expect(popup.get('disablePrimary')).to.equal(true);
  });
  it('does not permit Proceed until the draft is saved in the database', function () {
    complete('COMPLETED', ['host1', 'host2'], true);
    expect(popup.get('validated')).to.equal(false);
    expect(pending[3].options.name).to.equal('admin.upgrade.java.draft.create');
    expect(JSON.parse(pending[3].options.data.settings.content).requestId).to.equal(42);
    pending[3].deferred.reject({status: 500});
    popup.onPrimary();
    expect(proceed.called).to.equal(false);
    expect(popup.get('error')).not.to.equal('');
  });
  it('enables Proceed after an existing draft is updated with an empty HTTP 200 response', function () {
    popup.set('draftExists', true);
    startCheck();
    App.ajax.send.restore();
    sinon.spy(App.ajax, 'send');
    $.ajax.restore();
    sinon.spy($, 'ajax');
    var server = sinon.fakeServer.create();
    try {
      server.respondWith('PUT', 'http://' + $.hostName + App.apiPrefix + '/settings/' + popup.get('draftName'),
        [200, {'Content-Type': 'application/json'}, '']);
      pending[2].deferred.resolve(result('COMPLETED', ['host1', 'host2']));
      expect(popup.get('disablePrimary')).to.equal(true);
      server.respond();
      expect(popup.get('validated')).to.equal(true);
      expect(popup.get('disablePrimary')).to.equal(false);
      expect(popup.get('error')).to.equal('');
      expect(proceed.called).to.equal(false);
    } finally {
      server.restore();
    }
  });
  function restoreDraft(endTime, hosts) {
    popup.setProperties({primaryHome: '/old8', secondaryHome: ''});
    popup.loadDraft();
    pending[0].deferred.resolve({Settings: {content: JSON.stringify({
      primary: '/jdk17', secondary: '/jdk21', primaryMajor: 17, secondaryMajor: 21, requestId: 42
    })}});
    pending[1].deferred.resolve({items: (hosts || ['host1', 'host2']).map(function (host) {
      return {Hosts: {host_name: host}};
    })});
    pending[2].deferred.resolve(result('COMPLETED', ['host1', 'host2'], endTime));
  }
  it('recovers saved paths and recent proof without starting another host check', function () {
    restoreDraft(Date.now());
    expect(popup.get('primaryHome')).to.equal('/jdk17');
    expect(pending[2].options.name).to.equal('admin.upgrade.java.results');
    expect(pending[3].options.name).to.equal('admin.upgrade.java.draft.update');
    pending[3].deferred.resolve({});
    expect(popup.get('validated')).to.equal(true);
    popup.onPrimary();
    expect(proceed.calledOnce).to.equal(true);
  });
  it('retains restored paths but requires another check if the proof expired', function () {
    restoreDraft(1);
    expect(popup.get('primaryHome')).to.equal('/jdk17');
    expect(popup.get('validated')).to.equal(false);
    expect(pending.length).to.equal(3);
  });
  it('requires another check after hosts were added', function () {
    restoreDraft(Date.now(), ['host1', 'host2', 'host3']);
    expect(popup.get('validated')).to.equal(false);
  });
  it('does not reuse a draft for different Java requirements', function () {
    popup.loadDraft();
    pending[0].deferred.resolve({Settings: {content: JSON.stringify({
      primary: '/jdk21', secondary: '/jdk25', primaryMajor: 21, secondaryMajor: 25, requestId: 42
    })}});
    expect(pending.length).to.equal(1);
    expect(popup.get('primaryHome')).to.equal('/jdk17');
    expect(popup.get('validated')).to.equal(false);
  });
  it('ignores late draft responses after cancellation', function () {
    popup.loadDraft();
    popup.onSecondary();
    pending[0].deferred.resolve({Settings: {content: '{}'}});
    expect(pending.length).to.equal(1);
    expect(proceed.called).to.equal(false);
  });

  it('uses the stack threshold in the title and a wider dialog', function () {
    expect(popup.get('header')).to.contain('1.3.2.0');
    expect(popup.get('primaryMinimum')).to.equal('Minimum JDK 17');
    expect(popup.get('secondaryMinimum')).to.equal('Minimum JDK 21');
    expect(popup.get('modalDialogClasses')).to.deep.equal(['upgrade-java-dialog']);
  });

  function startCheck() {
    popup.check();
    pending[0].deferred.resolve({items: [{Hosts: {host_name: 'host1'}}, {Hosts: {host_name: 'host2'}}]});
    pending[1].deferred.resolve({Requests: {id: 42}});
  }

  function partialFailure() {
    var response = result('COMPLETED', ['host1', 'host2']);
    response.Requests.request_status = 'FAILED';
    response.tasks[1].Tasks.status = 'FAILED';
    response.tasks[1].Tasks.structured_out.upgrade_java.secondary = {
      home: '/jdk21', valid: false, message: 'bin/java does not exist'
    };
    return response;
  }

  it('shows a pending row for every host before results arrive', function () {
    startCheck();
    expect(popup.get('hostCount')).to.equal(2);
    expect(popup.get('primarySuccessCount')).to.equal(0);
    expect(popup.get('secondarySuccessCount')).to.equal(0);
    popup.get('rows').forEach(function (row) {
      expect(row.running).to.equal(true);
      expect(row.primaryFailed).to.equal(false);
      expect(row.progressClass).to.equal('progress-bar-info');
    });
  });

  it('counts each JDK independently when one path fails on a host', function () {
    startCheck();
    pending[2].deferred.resolve(partialFailure());
    expect(popup.get('primarySuccessCount')).to.equal(2);
    expect(popup.get('secondarySuccessCount')).to.equal(1);
    expect(popup.get('rows')[1].primaryValid).to.equal(true);
    expect(popup.get('rows')[1].secondaryFailed).to.equal(true);
    expect(popup.get('rows')[1].running).to.equal(false);
    expect(popup.get('error')).to.equal('Validation failed. Correct the paths or host availability, then check again.');
    expect(popup.get('disablePrimary')).to.equal(true);
  });

  it('stops pending progress bars after a request error', function () {
    startCheck();
    pending[2].deferred.reject();
    expect(popup.get('checking')).to.equal(false);
    popup.get('rows').forEach(function (row) {
      expect(row.running).to.equal(false);
      expect(row.primaryFailed).to.equal(true);
      expect(row.secondaryFailed).to.equal(true);
      expect(row.progressClass).to.equal('progress-bar-danger');
    });
  });

  it('does not count stale proof as a successful path check', function () {
    restoreDraft(1);
    expect(popup.get('primarySuccessCount')).to.equal(0);
    expect(popup.get('secondarySuccessCount')).to.equal(0);
    expect(popup.get('rows')[0].primary).to.equal('Validation expired. Check again.');
  });

  it('does not count duplicate task results as distinct hosts', function () {
    complete('COMPLETED', ['host1', 'host1']);
    expect(popup.get('hostCount')).to.equal(2);
    expect(popup.get('primarySuccessCount')).to.equal(0);
    expect(popup.get('validated')).to.equal(false);
  });

  it('marks a timed out host as failed instead of leaving its progress active', function () {
    complete('TIMEDOUT', ['host1', 'host2']);
    expect(popup.get('rows')[0].statusLabel).to.equal('Timed out');
    expect(popup.get('rows')[0].running).to.equal(false);
    expect(popup.get('primarySuccessCount')).to.equal(0);
    expect(popup.get('disablePrimary')).to.equal(true);
  });

  function renderedBody(assertions) {
    var container = Em.ContainerView.create(popup.getProperties('rows', 'hostCount',
      'primaryHome', 'secondaryHome', 'primaryMajor', 'secondaryMajor', 'introduction',
      'primaryMinimum', 'secondaryMinimum', 'primarySuccessCount', 'secondarySuccessCount',
      'activationMessage', 'validatedMessage', 'inputsDisabled', 'saving',
      'error', 'validated', 'checking', 'loadingDraft', 'disableCheck'), {
      childViews: [popup.get('bodyClass')]
    });
    try {
      Em.run(function () { container.appendTo('body'); });
      assertions(container.$());
    } finally {
      Em.run(function () { container.destroy(); });
    }
  }

  it('renders separate path icons, counts and the bold activation notice', function () {
    startCheck();
    pending[2].deferred.resolve(partialFailure());
    renderedBody(function (body) {
      expect(body.find('tbody tr').length).to.equal(2);
      expect(body.find('.primary-count').text()).to.contain('2/2');
      expect(body.find('.secondary-count').text()).to.contain('1/2');
      expect(body.find('.java-primary-result .glyphicon-ok').length).to.equal(2);
      expect(body.find('.java-secondary-result .glyphicon-remove').length).to.equal(1);
      expect(body.find('.progress-bar-danger').length).to.equal(1);
      expect(body.find('.upgrade-java-activation strong').text()).to.contain('ambari.properties');
    });
  });

  it('renders registration-style animated progress while checks are pending', function () {
    startCheck();
    renderedBody(function (body) {
      expect(body.find('.progress-bar-striped.active').length).to.equal(2);
      expect(body.find('.glyphicon-time').length).to.equal(4);
      expect(body.find('.primary-count').text()).to.contain('0/2');
    });
  });

  it('renders the success notice with its spacing class', function () {
    complete('COMPLETED', ['host1', 'host2']);
    renderedBody(function (body) {
      expect(body.find('.alert-success.upgrade-java-feedback').length).to.equal(1);
      expect(body.find('.progress-bar-success').length).to.equal(2);
      expect(body.find('.progress-bar-striped').length).to.equal(0);
    });
  });
});
