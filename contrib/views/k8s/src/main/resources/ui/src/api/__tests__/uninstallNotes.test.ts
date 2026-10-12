import { uninstallHelm } from '../client';

/**
 * An uninstall answers with what it could not take back itself, such as OpenShift security context
 * constraints the platform team granted, so the Releases page can show the commands that do.
 */
describe('uninstallHelm', () => {
  const originalFetch = (globalThis as any).fetch;

  afterEach(() => {
    (globalThis as any).fetch = originalFetch;
  });

  const answer = (body: string) => {
    (globalThis as any).fetch = jest.fn(() => Promise.resolve({
      ok: true,
      status: 200,
      json: async () => JSON.parse(body),
      text: async () => body,
    }));
  };

  it('returns the SCC note left for the platform team', async () => {
    answer(JSON.stringify({ sccCleanup: 'oc adm policy remove-scc-from-user anyuid -z om-airflow-worker -n data' }));

    const notes = await uninstallHelm('om', 'data');

    expect(notes.sccCleanup).toBe('oc adm policy remove-scc-from-user anyuid -z om-airflow-worker -n data');
  });

  it('returns no notes when the answer is empty', async () => {
    answer('');

    await expect(uninstallHelm('om', 'data')).resolves.toEqual({});
  });
});
