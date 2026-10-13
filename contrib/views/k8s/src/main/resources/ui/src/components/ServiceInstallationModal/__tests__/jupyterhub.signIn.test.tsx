/*
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
import * as fs from 'fs';
import * as path from 'path';
import React from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Form } from 'antd';
import type { FormInstance } from 'antd';
import DynamicFormField from '../DynamicFormField';
import { applyFormDefaults, buildChartValues, seedFieldsFromValues, snippetListEntries } from '../chartValues';
import { deepMerge } from '../bindings';
import { requiredUnlessAnyError, requiredUnlessAnyErrors } from '../requiredUnless';
import { seedFieldsFromRelease } from '../seedFromRelease';

jest.mock('../../../api/client', () => ({
  getClusterCapabilities: jest.fn(() => Promise.resolve({ platform: 'kubernetes' })),
}));

/**
 * Who may sign in to JupyterHub, run against the REAL JUPYTERHUB/service.json. OAuthenticator 17 refuses everyone but
 * the admins unless an allow rule is set, so the wizard always writes one: the members of the chosen groups (the
 * default) or every user of the realm, and refuses a group-restricted hub that names neither a group nor an admin.
 * An upgrade fills the form back from what the release runs, so an upgrade nobody edited changes nobody's access.
 */
const servicesDir = path.join(__dirname, '../../../../../KDPS/services');
const def = JSON.parse(fs.readFileSync(path.join(servicesDir, 'JUPYTERHUB/service.json'), 'utf8'));
const field = (name: string): any => def.form.find((f: any) => f.name === name);

const install = (extra: Record<string, any> = {}): any => {
  const values: any = {
    releaseName: 'jupyterhub', namespace: 'jupyterhub', jupyterHost: 'jupyter.example.com',
    kerberosHostFqdn: 'jupyterhub.jupyterhub.k8s',
  };
  applyFormDefaults(def.form, values);
  return { ...values, ...extra };
};

const build = (formValues: any) => buildChartValues({
  form: def.form, bindings: def.bindings, variables: def.variables, formValues, chartVersion: def.version,
});

describe('JupyterHub sign-in access', () => {
  it('asks who can sign in, members of the chosen groups by default', () => {
    const access = field('hubLoginAccess');
    expect(access.type).toBe('select');
    expect(access.required).toBe(true);
    expect(access.defaultValue).toBe('groups');
    expect(access.options.map((o: any) => o.value)).toEqual(['groups', 'all']);
    expect(field('allowedGroups').condition).toEqual({ field: 'hubLoginAccess', value: 'groups' });
    expect(field('adminGroups').condition).toEqual({ field: 'hubLoginAccess', value: 'groups' });
  });

  it('groups mode lets the allowed and admin groups in through the groups claim', () => {
    const hub = build(install({ allowedGroups: 'data-scientists, analysts', adminGroups: 'jupyterhub-admins' })).hub;
    expect(hub.config.GenericOAuthenticator).toMatchObject({
      allow_all: false,
      manage_groups: true,
      auth_state_groups_key: 'oauth_user.groups',
      allowed_groups: ['data-scientists', 'analysts'],
      admin_groups: ['jupyterhub-admins'],
    });
    // Deprecated in OAuthenticator 17: without manage_groups it stops the hub from starting.
    expect(JSON.stringify(hub)).not.toContain('claim_groups_key');
  });

  it('an empty group list is an empty list, not a blank string the hub cannot read', () => {
    const oauth = build(install({ allowedGroups: ' , ', hubAdminUsers: 'alice' })).hub.config.GenericOAuthenticator;
    expect(oauth.allowed_groups).toEqual([]);
    expect(oauth.admin_groups).toEqual([]);
  });

  it('all-users mode lets every realm user in and manages no groups', () => {
    const oauth = build(install({ hubLoginAccess: 'all', allowedGroups: 'left-over', adminGroups: 'left-over' }))
      .hub.config.GenericOAuthenticator;
    expect(oauth.allow_all).toBe(true);
    expect(oauth.manage_groups).toBe(false);
    expect(oauth.allowed_groups).toEqual([]);
    expect(oauth.admin_groups).toEqual([]);
  });

  it('a release from before the choice (no value) stays group-restricted', () => {
    const form = install({ allowedGroups: 'data-scientists' });
    delete form.hubLoginAccess;
    const oauth = build(form).hub.config.GenericOAuthenticator;
    expect(oauth.allow_all).toBe(false);
    expect(oauth.allowed_groups).toEqual(['data-scientists']);
  });

  it('quotes in the group and admin fields cannot break the hub configuration', () => {
    const hub = build(install({
      allowedGroups: "o'brien-team, \"quoted\"\\group",
      hubAdminUsers: "ali'ce,  bob ,,",
    })).hub;
    expect(hub.config.GenericOAuthenticator.allowed_groups).toEqual(["o'brien-team", '"quoted"\\group']);
    expect(hub.extraConfig['20-clemlab-admins']).toBe('c.Authenticator.admin_users = set(["ali\'ce","bob"])\n');
  });

  it('with no admin user named, the admin snippet names none', () => {
    expect(build(install({ allowedGroups: 'data-scientists' })).hub.extraConfig['20-clemlab-admins'])
      .toBe('c.Authenticator.admin_users = set([])\n');
    expect(build(install({ allowedGroups: 'x', hubAdminUsers: ' , ' })).hub.extraConfig['20-clemlab-admins'])
      .toBe('c.Authenticator.admin_users = set([])\n');
  });

  it('keeps the form-only fields out of the strict chart values', () => {
    const values = build(install({ allowedGroups: 'a', adminGroups: 'b', hubAdminUsers: 'c' }));
    for (const key of ['hubLoginAccess', 'allowedGroups', 'adminGroups', 'hubAdminUsers']) {
      expect(values).not.toHaveProperty(key);
    }
  });

  it('the admin users help no longer promises admins from Keycloak claims', () => {
    expect(field('hubAdminUsers').help).not.toMatch(/Keycloak claims/);
    expect(field('hubAdminUsers').help).toMatch(/admin groups/);
  });
});

describe('JupyterHub sign-in validation', () => {
  const rule = field('allowedGroups').requiredUnlessAny;

  it('refuses a group-restricted hub with no group and no admin', () => {
    expect(rule.fields).toEqual(['adminGroups', 'hubAdminUsers']);
    expect(requiredUnlessAnyError(rule, '', ['', ''])).toBe(rule.message);
    expect(requiredUnlessAnyError(rule, ' , ', [undefined, ',,'])).toBe(rule.message);
  });

  it('accepts allowed groups, admin groups or admin users', () => {
    expect(requiredUnlessAnyError(rule, 'data-scientists', ['', ''])).toBeNull();
    expect(requiredUnlessAnyError(rule, '', ['jupyterhub-admins', ''])).toBeNull();
    expect(requiredUnlessAnyError(rule, '', ['', 'alice'])).toBeNull();
  });

  // What the wizard's deploy button checks, on the wizard state as it stands: the fields need not have been touched.
  it('the deploy is refused for an untouched group-restricted form', () => {
    const errors = requiredUnlessAnyErrors(def.form, install());
    expect(errors).toEqual([`${field('allowedGroups').label}: ${rule.message}`]);
  });

  it('the deploy goes ahead once a group or an admin is named, or for all users', () => {
    expect(requiredUnlessAnyErrors(def.form, install({ allowedGroups: 'data-scientists' }))).toEqual([]);
    expect(requiredUnlessAnyErrors(def.form, install({ adminGroups: 'jupyterhub-admins' }))).toEqual([]);
    expect(requiredUnlessAnyErrors(def.form, install({ hubAdminUsers: 'alice' }))).toEqual([]);
    // The allowed groups are hidden in all-users mode, so they are not checked.
    expect(requiredUnlessAnyErrors(def.form, install({ hubLoginAccess: 'all' }))).toEqual([]);
  });

  it('a field hidden by its group is not checked either', () => {
    const form = [{
      name: 'access', label: 'Access', type: 'group', condition: { field: 'mode', value: 'groups' },
      fields: [{ name: 'allowed', label: 'Allowed', type: 'string', requiredUnlessAny: { fields: ['other'], message: 'm' } }],
    }] as any;
    expect(requiredUnlessAnyErrors(form, { mode: 'all' })).toEqual([]);
    expect(requiredUnlessAnyErrors(form, { mode: 'groups' })).toEqual(['Access - Allowed: m']);
  });

  const Harness: React.FC<{ formRef: React.MutableRefObject<FormInstance | null>; initial: any }> = ({ formRef, initial }) => {
    const [form] = Form.useForm();
    formRef.current = form;
    return (
      <Form form={form} initialValues={initial}>
        <DynamicFormField field={field('hubLoginAccess')} />
        <DynamicFormField field={field('allowedGroups')} />
        <DynamicFormField field={field('adminGroups')} />
        <DynamicFormField field={field('hubAdminUsers')} />
      </Form>
    );
  };

  it('the form shows the error on the field until an allow rule is named', async () => {
    const formRef: React.MutableRefObject<FormInstance | null> = { current: null };
    render(<Harness formRef={formRef} initial={{ hubLoginAccess: 'groups' }} />);
    await act(async () => {
      await expect(formRef.current!.validateFields()).rejects.toBeTruthy();
    });
    expect(await screen.findByText(rule.message)).toBeInTheDocument();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Admin users (comma-separated)'), 'alice');
    await waitFor(() => expect(screen.queryByText(rule.message)).not.toBeInTheDocument());
    await act(async () => {
      await expect(formRef.current!.validateFields()).resolves.toBeTruthy();
    });
  });

  it('all-users mode has nothing to name', async () => {
    const formRef: React.MutableRefObject<FormInstance | null> = { current: null };
    render(<Harness formRef={formRef} initial={{ hubLoginAccess: 'all' }} />);
    expect(screen.queryByLabelText('Allowed groups (comma-separated)')).not.toBeInTheDocument();
    await act(async () => {
      await expect(formRef.current!.validateFields()).resolves.toBeTruthy();
    });
  });
});

describe('JupyterHub sign-in upgrade', () => {
  /** The wizard's upgrade start: defaults, the release's values merged over them, then the form fields seeded. */
  const upgradeForm = (deployed: any): any => {
    const state: any = install();
    deepMerge(state, JSON.parse(JSON.stringify(deployed)));
    seedFieldsFromValues(def.form, state);
    seedFieldsFromRelease(def.form, state);
    return state;
  };
  const seed = (deployedAllowAll?: boolean): string => {
    const deployed = deployedAllowAll === undefined
      ? {} : { hub: { config: { GenericOAuthenticator: { allow_all: deployedAllowAll } } } };
    return upgradeForm(deployed).hubLoginAccess;
  };
  const deployedGroupsRelease = () => build(install({
    allowedGroups: 'data-scientists, analysts', adminGroups: 'jupyterhub-admins', hubAdminUsers: "alice, o'hara",
  }));

  it('an upgrade shows the mode the release runs with', () => {
    expect(seed(true)).toBe('all');
    expect(seed(false)).toBe('groups');
  });

  it('a release deployed before the choice existed starts group-restricted', () => {
    expect(seed()).toBe('groups');
  });

  it('an upgrade shows the groups and admins the release runs with', () => {
    const form = upgradeForm(deployedGroupsRelease());
    expect(form.hubLoginAccess).toBe('groups');
    expect(form.allowedGroups).toBe('data-scientists, analysts');
    expect(form.adminGroups).toBe('jupyterhub-admins');
    expect(form.hubAdminUsers).toBe("alice, o'hara");
    expect(requiredUnlessAnyErrors(def.form, form)).toEqual([]);
  });

  it('an upgrade nobody edited sends the same sign-in rules', () => {
    const deployed = deployedGroupsRelease();
    const rebuilt = build(upgradeForm(deployed));
    expect(rebuilt.hub.config.GenericOAuthenticator).toEqual(deployed.hub.config.GenericOAuthenticator);
    expect(rebuilt.hub.extraConfig['20-clemlab-admins']).toBe(deployed.hub.extraConfig['20-clemlab-admins']);
  });

  it('an upgrade to an unrelated setting keeps the groups', () => {
    const form = upgradeForm(deployedGroupsRelease());
    form.hub.image = { name: 'example/hub', tag: '2.0.0' };
    const oauth = build(form).hub.config.GenericOAuthenticator;
    expect(oauth.allowed_groups).toEqual(['data-scientists', 'analysts']);
    expect(oauth.admin_groups).toEqual(['jupyterhub-admins']);
  });

  it('switching a group-restricted release to all users drops its admin groups', () => {
    const form = upgradeForm(deployedGroupsRelease());
    form.hubLoginAccess = 'all';
    const oauth = build(form).hub.config.GenericOAuthenticator;
    expect(oauth.allow_all).toBe(true);
    expect(oauth.admin_groups).toEqual([]);
    expect(oauth.manage_groups).toBe(false);
  });

  it('emptying the admin users on an upgrade takes their rights away', () => {
    const form = upgradeForm(deployedGroupsRelease());
    form.hubAdminUsers = '';
    expect(build(form).hub.extraConfig['20-clemlab-admins']).toBe('c.Authenticator.admin_users = set([])\n');
  });

  it('the admins of a release deployed by an earlier version come back on an upgrade', () => {
    const legacy = {
      hub: { extraConfig: {
        '20-clemlab-admins': "c.Authenticator.admin_users = { u.strip() for u in 'alice, bob'.split(',') if u.strip() }\n",
      } },
    };
    const form = upgradeForm(legacy);
    expect(form.hubAdminUsers).toBe('alice, bob');
    expect(build(form).hub.extraConfig['20-clemlab-admins']).toBe('c.Authenticator.admin_users = set(["alice","bob"])\n');
  });

  it('reads the admin snippets the definition writes, and nothing else', () => {
    expect(snippetListEntries('c.Authenticator.admin_users = set(["a","b\\")"])\n')).toEqual(['a', 'b")']);
    expect(snippetListEntries('c.Authenticator.admin_users = set([])\n')).toEqual([]);
    expect(snippetListEntries("c.Authenticator.admin_users = { u.strip() for u in 'a,,b'.split(',') if u.strip() }\n"))
      .toEqual(['a', 'b']);
    expect(snippetListEntries('c.Authenticator.admin_users = load_admins()\n')).toBeNull();
    expect(snippetListEntries(undefined)).toBeNull();
  });
});
