import type { HelmRepo } from '../../types/ServiceTypes';

export const getHelmRepos = jest.fn<Promise<HelmRepo[]>, []>(() => Promise.resolve([]));
export const createHelmRepo = jest.fn<Promise<HelmRepo>, [any]>((repo) => Promise.resolve(repo));
export const loginHelmRepo  = jest.fn<Promise<void>, [string]>((id) => Promise.resolve());
export const deleteHelmRepo = jest.fn<Promise<void>, [string]>((id) => Promise.resolve());

// Si d’autres fonctions existent dans le module, mocke-les au besoin

export const getTrinoCatalogs = jest.fn<Promise<any[]>, []>(() => Promise.resolve([]));
export const saveTrinoCatalog = jest.fn<Promise<any>, [any]>((c) => Promise.resolve({ id: 'cat-1', ...c }));
export const deleteTrinoCatalog = jest.fn<Promise<void>, [string]>(() => Promise.resolve());
export const listReleaseCatalogs = jest.fn<Promise<any[]>, [string, string]>(() => Promise.resolve([]));
export const createReleaseCatalog = jest.fn<Promise<any>, [string, string, any]>((_n, _r, b) => Promise.resolve({ name: b.name, live: true, persisted: b.persist }));
export const dropReleaseCatalog = jest.fn<Promise<any>, [string, string, string, boolean?]>((_n, _r, name) => Promise.resolve({ name }));
export const testReleaseCatalog = jest.fn<Promise<any>, [string, string, string]>(() => Promise.resolve({ schemas: [] }));
export const adoptReleaseCatalog = jest.fn<Promise<any>, [string, string, string]>((_n, _r, name) => Promise.resolve({ name, properties: '' }));
