import { handleAuthExpiry } from '../client';

/**
 * An expired Ambari session sends the operator to the login page. A refusal that comes from the cluster must
 * never do that, even when its text names a token or authentication.
 */
describe('handleAuthExpiry', () => {
  it('treats a 401 as an expired session', () => {
    // jsdom does not implement navigation and only reports it; the redirect itself is not what is asserted here.
    const report = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    try {
      expect(handleAuthExpiry(401, 'Unauthorized')).toBe(true);
    } finally {
      report.mockRestore();
    }
  });

  it('does not treat a cluster refusal for lack of rights as an expired session', () => {
    const body = 'K8S_FORBIDDEN: serviceaccounts "keda" is forbidden: User "kdps" cannot create resource '
      + '"serviceaccounts/token" in API group "" (authentication.k8s.io)';
    expect(handleAuthExpiry(403, body)).toBe(false);
  });

  it('does not treat a rejected cluster token as an expired session', () => {
    expect(handleAuthExpiry(401, 'K8S_UNAUTHORIZED: Unauthorized')).toBe(false);
  });
});
