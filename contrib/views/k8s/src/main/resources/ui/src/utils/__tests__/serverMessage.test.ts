import { serverMessage } from '../serverMessage';

// A refusal the server explains (a 403 on the Truststores or Certificate Authorities page) is shown as its sentence,
// not as the raw response.
describe('serverMessage', () => {
  const refusal = 'KDPS cannot write Secrets in project ambari-pki; ask the platform team to grant the KDPS account edit '
    + 'in that project, or set the namespace in Cluster Settings.';

  it('reads the error of a JSON body', () => {
    expect(serverMessage(new Error(`HTTP 403 – ${JSON.stringify({ error: refusal })}`))).toBe(refusal);
    expect(serverMessage(new Error(JSON.stringify({ error: refusal })))).toBe(refusal);
  });

  it('keeps any other text without the status prefix', () => {
    expect(serverMessage(new Error('HTTP 500 – Internal Server Error'))).toBe('Internal Server Error');
    expect(serverMessage(new Error('HTTP 502 – {not json'))).toBe('{not json');
  });

  it('falls back when there is nothing to show', () => {
    expect(serverMessage(new Error(''), 'Could not load the truststores.')).toBe('Could not load the truststores.');
  });
});
