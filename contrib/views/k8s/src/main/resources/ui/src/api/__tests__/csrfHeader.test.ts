import * as fs from 'fs';
import * as path from 'path';
import * as client from '../client';
import { apiFetch, withCsrfHeader } from '../client';

/**
 * Ambari refuses a POST, PUT or DELETE to its REST API that lacks X-Requested-By (400 "CSRF protection
 * is turned on"). Every call this UI makes to that API must carry the header: a call that misses it fails
 * as soon as the protection is on, and only the header stops another web site from making the operator's
 * browser submit the same request.
 */

const SRC = path.join(__dirname, '../..');

/** Reads a header from fetch() options whatever form they were given in, ignoring the name's case. */
function headerOf(init: RequestInit | undefined, name: string): string | undefined {
  const given: any = init?.headers;
  if (!given) return undefined;
  let entries: [string, string][];
  if (Array.isArray(given)) {
    entries = given;
  } else if (typeof given.forEach === 'function') {
    entries = [];
    given.forEach((value: string, key: string) => entries.push([key, value]));
  } else {
    entries = Object.entries(given);
  }
  return entries.find(([key]) => key.toLowerCase() === name.toLowerCase())?.[1];
}

describe('withCsrfHeader', () => {
  it('adds the header when the caller sets none', () => {
    expect(headerOf(withCsrfHeader(), 'X-Requested-By')).toBe('ambari');
    expect(headerOf(withCsrfHeader({ method: 'POST' }), 'X-Requested-By')).toBe('ambari');
  });

  it('keeps the headers and the other options of the caller', () => {
    const init = withCsrfHeader({ method: 'POST', body: '{}', credentials: 'include', headers: { 'Content-Type': 'application/json' } });
    expect(init.method).toBe('POST');
    expect(init.body).toBe('{}');
    expect(init.credentials).toBe('include');
    expect(headerOf(init, 'Content-Type')).toBe('application/json');
    expect(headerOf(init, 'X-Requested-By')).toBe('ambari');
  });

  it('keeps headers given as a list of pairs', () => {
    const init = withCsrfHeader({ headers: [['Accept', 'text/plain']] });
    expect(headerOf(init, 'Accept')).toBe('text/plain');
    expect(headerOf(init, 'X-Requested-By')).toBe('ambari');
  });

  it('keeps headers given as a Headers object', () => {
    // When the test environment lacks the fetch API, a minimal stand-in exercises the same branch.
    const original = (globalThis as any).Headers;
    class FakeHeaders {
      private readonly map = new Map<string, string>();
      constructor(entries: [string, string][]) { entries.forEach(([k, v]) => this.map.set(k.toLowerCase(), v)); }
      forEach(cb: (value: string, name: string) => void) { this.map.forEach((v, k) => cb(v, k)); }
    }
    (globalThis as any).Headers = original ?? FakeHeaders;
    try {
      const HeadersClass = (globalThis as any).Headers;
      const init = withCsrfHeader({ headers: new HeadersClass([['Content-Type', 'application/json']]) });
      expect(headerOf(init, 'Content-Type')).toBe('application/json');
      expect(headerOf(init, 'X-Requested-By')).toBe('ambari');
    } finally {
      (globalThis as any).Headers = original;
    }
  });

  it('never overrides the value the caller chose, whatever the case of the name', () => {
    expect(headerOf(withCsrfHeader({ headers: { 'X-Requested-By': 'kdps' } }), 'X-Requested-By')).toBe('kdps');
    const lower = withCsrfHeader({ headers: { 'x-requested-by': 'kdps' } });
    expect(Object.keys(lower.headers as Record<string, string>)).toEqual(['x-requested-by']);
  });

  it('does not change the options it was given', () => {
    const headers = { 'Content-Type': 'application/json' };
    withCsrfHeader({ headers });
    expect(headers).toEqual({ 'Content-Type': 'application/json' });
  });
});

describe('client calls to the Ambari REST API', () => {
  type Call = { fn: string; url: string; init?: RequestInit };
  let calls: Call[];
  let current = '';
  const originalFetch = (globalThis as any).fetch;

  const okResponse = (): any => {
    const res: any = {
      ok: true,
      status: 200,
      statusText: 'OK',
      headers: { get: () => 'application/json' },
      json: async () => ({}),
      text: async () => '{}',
      blob: async () => ({}),
      arrayBuffer: async () => new ArrayBuffer(0),
    };
    res.clone = () => res;
    return res;
  };

  beforeAll(async () => {
    calls = [];
    (globalThis as any).fetch = jest.fn((url: any, init?: RequestInit) => {
      calls.push({ fn: current, url: String(url), init });
      return Promise.resolve(okResponse());
    });
    // Call every exported function with a few argument shapes; each shape that reaches fetch() is
    // recorded. What a call does with the fake answer does not matter here, so errors are ignored.
    const argumentShapes: any[][] = [
      [],
      ['ns', 'release', '1', 'x'],
      [{}, new URLSearchParams()],
      [{ name: 'x', namespace: 'ns' }, 'ns', 'release'],
    ];
    for (const [name, value] of Object.entries(client)) {
      if (typeof value !== 'function') continue;
      for (const args of argumentShapes) {
        current = name;
        try {
          await (value as (...a: any[]) => unknown)(...args);
        } catch {
          /* the call has been recorded if it reached fetch() */
        }
      }
    }
    current = '';
  });

  afterAll(() => {
    (globalThis as any).fetch = originalFetch;
  });

  it('every call sends X-Requested-By', () => {
    expect(calls.length).toBeGreaterThan(50);
    const withoutHeader = calls
      .filter((c) => headerOf(c.init, 'X-Requested-By') !== 'ambari')
      .map((c) => `${c.fn} ${c.init?.method || 'GET'} ${c.url}`);
    expect(withoutHeader).toEqual([]);
  });

  it('reaches the API from every exported function that is not a local helper', () => {
    // Helpers that make no request at all. A new exported call that the argument shapes above do not
    // get to fetch() shows up here, so that it is not silently left out of the check.
    const localHelpers = ['withCsrfHeader', 'handleAuthExpiry', 'needsLogin'];
    const neverCalled = Object.entries(client)
      .filter(([name, value]) => typeof value === 'function' && !localHelpers.includes(name))
      .map(([name]) => name)
      .filter((name) => !calls.some((c) => c.fn === name));
    expect(neverCalled).toEqual([]);
  });

  it('covers the actions a forged form could trigger', () => {
    // State-changing calls that a page on another site could otherwise make the browser send.
    const stateChanging = [
      'restartPod', 'deletePod', 'cancelCommand', 'resumeCommand', 'refreshDependencies',
      'submitHelmDeploy', 'deployHelm', 'upgradeHelm', 'validateChart', 'uninstallHelm',
      'rollbackReleaseToRevision', 'upgradeReleaseChart', 'regenerateReleaseKeytabs', 'adoptRelease',
      'registerReleaseOidcClient', 'reapplyReleaseRangerRepository', 'triggerReplayableAction',
      'installMonitoring', 'resetMonitoringCache', 'loginHelmRepo', 'loginRepo', 'saveHelmRepo',
      'deleteHelmRepo', 'saveViewSettings', 'saveSecurityConfig', 'deleteSecurityProfile',
      'saveManagedConfig', 'deleteManagedConfig',
    ];
    for (const fn of stateChanging) {
      const sent = calls.filter((c) => c.fn === fn && (c.init?.method || 'GET') !== 'GET');
      expect({ fn, reachedApi: sent.length > 0 }).toEqual({ fn, reachedApi: true });
      expect({ fn, headers: sent.map((c) => headerOf(c.init, 'X-Requested-By')) })
        .toEqual({ fn, headers: sent.map(() => 'ambari') });
    }
  });

  it('JSON calls keep their content type next to the header', () => {
    const save = calls.find((c) => c.fn === 'saveHelmRepo');
    expect(headerOf(save?.init, 'Content-Type')).toBe('application/json');
    expect(headerOf(save?.init, 'X-Requested-By')).toBe('ambari');
  });

  it('apiFetch passes the URL through unchanged', async () => {
    await apiFetch(`${client.API_BASE_URL}/commands/1/cancel`, { method: 'POST' });
    const last = calls[calls.length - 1];
    expect(last.url).toBe(`${client.API_BASE_URL}/commands/1/cancel`);
    expect(last.init?.method).toBe('POST');
    expect(headerOf(last.init, 'X-Requested-By')).toBe('ambari');
  });
});

/**
 * A grep over the sources: a raw fetch() (or XMLHttpRequest) bypasses apiFetch and therefore the CSRF
 * header. Only the calls listed here may do so, each for the reason given.
 */
describe('no raw fetch() to the Ambari REST API', () => {
  const RAW_FETCH_ALLOWED: Record<string, { count: number; why: string }> = {
    'api/client.ts': { count: 1, why: 'apiFetch itself' },
    'components/ServiceInstallationModal/index.tsx': {
      count: 1,
      why: 'downloads a chart from an external URL the operator typed; Ambari is not involved',
    },
  };

  const sourceFiles = (dir: string): string[] =>
    fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) return entry.name === '__tests__' || entry.name === '__mocks__' ? [] : sourceFiles(full);
      return /\.(ts|tsx)$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [full] : [];
    });

  // Comments are dropped so that prose mentioning fetch() does not count.
  const code = (file: string) =>
    fs.readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');

  const files = sourceFiles(SRC).map((f) => ({ rel: path.relative(SRC, f).split(path.sep).join('/'), text: code(f) }));

  it('scans the UI sources', () => {
    expect(files.map((f) => f.rel)).toEqual(expect.arrayContaining(['api/client.ts', 'pages/HelmReleasesPage.tsx']));
  });

  it('calls the API only through apiFetch', () => {
    const offenders: string[] = [];
    for (const { rel, text } of files) {
      const raw = (text.match(/(^|[^\w.$])fetch\s*\(/g) || []).length
        + (text.match(/\b(window|globalThis|self)\.fetch\s*\(/g) || []).length;
      const allowed = RAW_FETCH_ALLOWED[rel]?.count ?? 0;
      if (raw !== allowed) {
        const why = RAW_FETCH_ALLOWED[rel] ? ` (${RAW_FETCH_ALLOWED[rel].why})` : '';
        offenders.push(`${rel}: ${raw} raw fetch() call(s), ${allowed} allowed${why}; use apiFetch`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it('sets the header on every XMLHttpRequest', () => {
    const offenders = files
      .filter(({ text }) => /new XMLHttpRequest\s*\(/.test(text))
      // XMLHttpRequest.open() takes the method first; window.open() takes a URL and does not count.
      .filter(({ text }) => (text.match(/\.open\(\s*['"][A-Z]+['"]/g) || []).length
        > (text.match(/setRequestHeader\(\s*(['"]X-Requested-By['"]|CSRF_HEADER)/g) || []).length)
      .map(({ rel }) => rel);
    expect(offenders).toEqual([]);
  });
});
