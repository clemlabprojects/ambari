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

  const answer = (body: string, status = 200) => {
    (globalThis as any).fetch = jest.fn(() => Promise.resolve({
      ok: status < 400,
      status,
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

  it('tells why an uninstall was refused, and what to do', async () => {
    const reason = 'KDPS cannot remove data/om from Git with the Git credential recorded for it, so nothing was '
      + 'removed. Deploy the release again once, giving its Git access token or SSH key, then uninstall it.';
    answer(JSON.stringify({ error: reason }), 409);

    await expect(uninstallHelm('om', 'data')).rejects.toThrow(`Uninstall failed: 409: ${reason}`);
  });

  it('gives the status when a refusal has no reason', async () => {
    answer('', 500);

    await expect(uninstallHelm('om', 'data')).rejects.toThrow(/^Uninstall failed: 500$/);
  });
});
