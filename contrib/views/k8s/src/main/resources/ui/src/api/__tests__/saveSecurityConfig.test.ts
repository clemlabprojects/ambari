import { saveSecurityConfig } from '../client';

/**
 * Saving the security profiles can be refused for one profile (too large to be stored, or still used by a
 * release): the Global Security page shows the server's reason as it is, not the raw JSON of the answer.
 */
describe('saveSecurityConfig', () => {
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

  it('resolves when the profiles are saved', async () => {
    answer('', 200);

    await expect(saveSecurityConfig({ defaultProfile: 'corp-ldap', profiles: {} })).resolves.toBeUndefined();
  });

  it('shows why a profile was refused', async () => {
    const reason = "The security profile 'big' is too large to be saved (4210 characters once stored, at most 3000).";
    answer(JSON.stringify({ error: reason }), 400);

    await expect(saveSecurityConfig({ profiles: {} })).rejects.toThrow(reason);
  });

  it('shows which releases still use a profile left out', async () => {
    const reason = "Security profile 'keycloak' is still used by analytics/trino.";
    answer(JSON.stringify({ error: reason, releases: ['analytics/trino'] }), 409);

    await expect(saveSecurityConfig({ profiles: {} })).rejects.toThrow(new Error(reason));
  });

  it('keeps an answer that is not JSON', async () => {
    answer('Bad Gateway', 502);

    await expect(saveSecurityConfig({ profiles: {} })).rejects.toThrow('Bad Gateway');
  });
});
