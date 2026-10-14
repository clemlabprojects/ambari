import { saveViewSettings } from '../client';

/**
 * Saving the settings can succeed with a warning for the administrator: when the Kerberos injection mode leaves
 * Webhook while the keytab webhook is still installed, KDPS keeps the webhook's Ambari account and says why.
 */
describe('saveViewSettings', () => {
  const originalFetch = (globalThis as any).fetch;

  afterEach(() => {
    (globalThis as any).fetch = originalFetch;
  });

  const answer = (body: string, status: number) => {
    (globalThis as any).fetch = jest.fn(() => Promise.resolve({
      ok: status < 400,
      status,
      json: async () => JSON.parse(body),
      text: async () => body,
    }));
  };

  it('resolves without a warning when the settings are saved', async () => {
    answer('', 200);

    await expect(saveViewSettings({ kerberos: { injectionMode: 'PRE_PROVISIONED' } })).resolves.toEqual({});
  });

  it('passes on the warning of a save that kept the webhook account', async () => {
    const warning = "The Kerberos keytab webhook is still installed in the Kubernetes cluster, so its Ambari account "
      + "'webhook-user-kdps' was kept.";
    answer(JSON.stringify({ warning }), 200);

    await expect(saveViewSettings({ kerberos: { injectionMode: 'PRE_PROVISIONED' } })).resolves.toEqual({ warning });
  });

  it('shows why the settings were refused', async () => {
    answer('Settings too large', 400);

    await expect(saveViewSettings({})).rejects.toThrow('Settings too large');
  });
});
