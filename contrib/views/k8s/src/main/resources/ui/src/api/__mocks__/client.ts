import type { HelmRepo } from '../../types/ServiceTypes';

export const getHelmRepos = jest.fn<Promise<HelmRepo[]>, []>(() => Promise.resolve([]));
export const createHelmRepo = jest.fn<Promise<HelmRepo>, [any]>((repo) => Promise.resolve(repo));
export const loginHelmRepo  = jest.fn<Promise<void>, [string]>((id) => Promise.resolve());
export const deleteHelmRepo = jest.fn<Promise<void>, [string]>((id) => Promise.resolve());

// Si d’autres fonctions existent dans le module, mocke-les au besoin

export const getTrinoCatalogs = jest.fn<Promise<any[]>, []>(() => Promise.resolve([]));
export const saveTrinoCatalog = jest.fn<Promise<any>, [any]>((c) => Promise.resolve({ id: 'cat-1', ...c }));
export const deleteTrinoCatalog = jest.fn<Promise<void>, [string]>(() => Promise.resolve());
