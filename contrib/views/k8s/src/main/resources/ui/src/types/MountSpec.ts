export type MountSpec = {
  key: string;
  defaultMountPath: string;
  supportedTypes: string[];
  defaults: { type: string; size?: string; storageClass?: string; accessModes?: string[] };
};

export type BindingSpec = { role: 'coordinator' | 'worker'; field: string; mountKey: string };