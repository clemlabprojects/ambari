import { masterKeyRefusal, saveOpenShiftLogin, getMasterKeyStatus, MASTER_KEY_UNAVAILABLE } from '../client';

/**
 * Without the Ambari master key the server refuses to store a credential with a 412 that says what to do. The view
 * shows that, and does not take it for the 412 of a view that has no cluster connection yet.
 */
describe('master key refusal', () => {
  const originalFetch = (globalThis as any).fetch;
  const message = "KDPS needs the Ambari master key to encrypt the credentials it keeps: run 'ambari-server "
    + "setup-security' (encrypt passwords, persist the master key) or set AMBARI_SECURITY_MASTER_KEY, then restart Ambari.";
  const refusal = JSON.stringify({ error: message, code: MASTER_KEY_UNAVAILABLE });

  afterEach(() => {
    (globalThis as any).fetch = originalFetch;
  });

  const answer = (body: string, status: number) => {
    (globalThis as any).fetch = jest.fn(() => Promise.resolve({
      ok: status < 400,
      status,
      statusText: '',
      json: async () => JSON.parse(body),
      text: async () => body,
    }));
  };

  it('reads what to do from the answer', () => {
    expect(masterKeyRefusal(412, refusal)).toBe(message);
  });

  it('leaves the 412 of an unconfigured view alone', () => {
    expect(masterKeyRefusal(412, 'UNCONFIGURED')).toBeNull();
    expect(masterKeyRefusal(500, refusal)).toBeNull();
  });

  it('shows what to do when a save is refused', async () => {
    answer(refusal, 412);

    await expect(saveOpenShiftLogin({ apiUrl: 'https://api:6443', username: 'u', password: 'p' } as any))
      .rejects.toThrow(new Error(message));
  });

  it('asks the server whether the key is there', async () => {
    answer(JSON.stringify({ available: false, message, code: MASTER_KEY_UNAVAILABLE }), 200);

    await expect(getMasterKeyStatus()).resolves.toEqual({ available: false, message, code: MASTER_KEY_UNAVAILABLE });
  });
});
