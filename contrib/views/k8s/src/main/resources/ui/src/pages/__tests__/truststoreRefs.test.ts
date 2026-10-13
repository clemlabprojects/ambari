import { normalizeTruststoreRefs, truststoreRef } from '../truststoreRefs';
import type { TruststoreSummary } from '../../api/client';

// The deploy wizard names each truststore by its namespace, the truststore project; a release recorded with bare names
// still shows them selected, and never picks up a Secret of the same name in another project.
const listed = (namespace: string, name: string, managed = true): TruststoreSummary => ({
  namespace,
  name,
  releaseName: name.replace(/-truststore$/, ''),
  caCount: 1,
  jvmReady: true,
  pemReady: true,
  certificates: [],
  managed,
});

describe('truststoreRef', () => {
  it('is the namespace and the Secret name', () => {
    expect(truststoreRef(listed('kdps-pki', 'corp-ca-truststore'))).toBe('kdps-pki/corp-ca-truststore');
  });
});

describe('normalizeTruststoreRefs', () => {
  // Only the truststore project's Secrets are listed as managed; the others are Secrets a tenant may have labelled.
  const truststores = [
    listed('kdps-pki', 'corp-ca-truststore'),
    listed('team-a', 'corp-ca-truststore', false),
    listed('team-b', 'partner-ca-truststore', false),
  ];

  it('matches a bare name recorded by an earlier version to the managed truststore of that name', () => {
    expect(normalizeTruststoreRefs(['corp-ca', 'corp-ca-truststore'], truststores))
      .toEqual(['kdps-pki/corp-ca-truststore', 'kdps-pki/corp-ca-truststore']);
  });

  it('never matches a Secret of that name outside the truststore project, nor one it does not find', () => {
    expect(normalizeTruststoreRefs(['partner-ca', 'gone-ca'], truststores)).toEqual(['partner-ca', 'gone-ca']);
  });

  it('keeps a reference that already names its namespace', () => {
    expect(normalizeTruststoreRefs(['team-b/partner-ca-truststore'], truststores)).toEqual(['team-b/partner-ca-truststore']);
    expect(normalizeTruststoreRefs(undefined, truststores)).toEqual([]);
  });
});
