import type { HelmRepo } from '../../types/ServiceTypes';

export const getHelmRepos = jest.fn<Promise<HelmRepo[]>, []>(() => Promise.resolve([]));
export const createHelmRepo = jest.fn<Promise<HelmRepo>, [any]>((repo) => Promise.resolve(repo));
export const loginHelmRepo  = jest.fn<Promise<void>, [string]>((id) => Promise.resolve());
export const deleteHelmRepo = jest.fn<Promise<void>, [string]>((id) => Promise.resolve());

// Si d’autres fonctions existent dans le module, mocke-les au besoin

