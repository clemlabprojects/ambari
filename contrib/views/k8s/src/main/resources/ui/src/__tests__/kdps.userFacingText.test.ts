import * as fs from 'fs';
import * as path from 'path';

/**
 * Regression guard for the text an operator reads: every KDPS service definition and global form, the view
 * descriptor and the UI sources must stay English and must not show internal lab names as examples. It loads
 * the real files, so a new French label or a lab host name in a placeholder fails the build.
 */
const resources = path.join(__dirname, '../../../');
const kdps = path.join(resources, 'KDPS');
const uiSrc = path.join(__dirname, '..');

/** JSON keys whose string value is shown to the operator (underscore-prefixed keys are author notes, never rendered). */
const USER_FACING_KEYS = new Set([
  'label', 'displayName', 'description', 'help', 'placeholder', 'title', 'tooltip', 'extra', 'message', 'text',
]);

const FRENCH_ACCENT = /[éèêëàâçùûôîïœÉÈÊÀÇ]/;
// Words that are also English (replicas, integration, demand) are left to the accent check, which catches their French spelling.
const FRENCH_WORDS = new RegExp(
  '\\b(' + [
    'stockage', 'taille', 'ressources', 'r[ée]pertoire', 'activer', 'd[ée]sactiver', 'limite', 'interne',
    'optionnel(le)?', 'seuil', 'chemin', 'nombre', 'fichier', 'utilisateur', 'mot de passe', 'recherche',
    'impossible', 'lecture', 'pour', 'avec', 'dans', 'sans', 'les', 'des', 'une', 'vue', 'du',
  ].join('|') + ')\\b',
  'i'
);

/** Vendor lab host names, domains and networks; the product identifiers registry.clemlab.com and ambari.clemlab.com/ stay allowed. */
const LAB_NAMES = /clemlabtest|hadoop\.clemlab\.com|\bdev\d{2}\b|\bmaster\d{2}\b|crc\.testing|192\.168\./i;

const isFrench = (t: string): boolean => FRENCH_ACCENT.test(t) || FRENCH_WORDS.test(t);
const isLabName = (t: string): boolean => LAB_NAMES.test(t);

const collectTexts = (node: unknown, out: string[]): void => {
  if (Array.isArray(node)) {
    node.forEach(n => collectTexts(n, out));
  } else if (node && typeof node === 'object') {
    Object.entries(node as Record<string, unknown>).forEach(([k, v]) => {
      if (typeof v === 'string') {
        if (USER_FACING_KEYS.has(k)) out.push(v);
      } else {
        collectTexts(v, out);
      }
    });
  }
};

const walkFiles = (dir: string, accept: (file: string) => boolean, skipDir: (name: string) => boolean): string[] =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) return skipDir(e.name) ? [] : walkFiles(full, accept, skipDir);
    return accept(full) ? [full] : [];
  });

const jsonFiles = walkFiles(kdps, f => f.endsWith('.json'), () => false);

/** The text of view.xml that Ambari Admin shows: descriptions and labels (comments are checked separately). */
const viewXml = fs.readFileSync(path.join(resources, 'view.xml'), 'utf8');
const viewXmlTexts = Array.from(viewXml.matchAll(/<(description|label)>([\s\S]*?)<\/\1>/g)).map(m => m[2].trim());
const viewXmlComments = Array.from(viewXml.matchAll(/<!--([\s\S]*?)-->/g)).map(m => m[1]);

describe('the guard detectors', () => {
  it('catch French words and accents, including words without an accent', () => {
    ['Stockage', 'PostgreSQL — Taille PVC', 'Ressources du serveur', 'Activer HTTPS interne', 'Réplicas', 'Répertoire de Travail']
      .forEach(t => expect(isFrench(t)).toBe(true));
  });

  it('let English text through', () => {
    ['Storage', 'Keystore Secret', 'Server Resources', 'CPU Limit', 'Enable internal HTTPS', 'Replicas']
      .forEach(t => expect(isFrench(t)).toBe(false));
  });

  it('catch lab names but not product identifiers', () => {
    ['dev21.hadoop.clemlab.com', 'hive-clemlabtest', 'clemlabtest_tag', 'daemon on master03', 'api.crc.testing', '192.168.50.1']
      .forEach(t => expect(isLabName(t)).toBe(true));
    ['registry.clemlab.com/clemlabprojects', 'ambari.clemlab.com/managed', 'apps.example.com', 'RFC1918 (10.0.0.0/8, 192.168/16)']
      .forEach(t => expect(isLabName(t)).toBe(false));
  });
});

describe('KDPS service definitions and globals', () => {
  it('finds the definitions it is meant to guard', () => {
    const rel = jsonFiles.map(f => path.relative(kdps, f));
    expect(rel).toEqual(expect.arrayContaining([
      'services/OPENMETADATA/service.json', 'services/TRINO/service.json', 'globals/security.json',
    ]));
  });

  it.each(jsonFiles.map(f => [path.relative(kdps, f), f]))('%s has English text only', (_rel, file) => {
    const texts: string[] = [];
    collectTexts(JSON.parse(fs.readFileSync(file, 'utf8')), texts);
    expect(texts.filter(isFrench)).toEqual([]);
  });

  it.each(jsonFiles.map(f => [path.relative(kdps, f), f]))('%s shows no internal lab names', (_rel, file) => {
    const texts: string[] = [];
    collectTexts(JSON.parse(fs.readFileSync(file, 'utf8')), texts);
    expect(texts.filter(isLabName)).toEqual([]);
  });
});

describe('view.xml', () => {
  it('reads the descriptions and labels it is meant to guard', () => {
    expect(viewXmlTexts.length).toBeGreaterThan(5);
  });

  it('shows English descriptions and labels', () => {
    expect(viewXmlTexts.filter(isFrench)).toEqual([]);
  });

  it('keeps its comments in English too', () => {
    expect(viewXmlComments.filter(c => FRENCH_ACCENT.test(c))).toEqual([]);
  });

  it('shows no internal lab names', () => {
    expect(viewXmlTexts.filter(isLabName)).toEqual([]);
  });
});

describe('UI sources', () => {
  const sources = walkFiles(
    uiSrc,
    f => /\.(tsx?|css)$/.test(f),
    name => name === '__tests__' || name === 'node_modules'
  );

  it('contain no internal lab names (vite.config.ts and the tests are outside this scan)', () => {
    const offenders = sources.filter(f => isLabName(fs.readFileSync(f, 'utf8'))).map(f => path.relative(uiSrc, f));
    expect(offenders).toEqual([]);
  });
});
