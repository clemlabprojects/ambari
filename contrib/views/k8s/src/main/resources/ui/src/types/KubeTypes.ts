export type KubeNamespace = {
  name: string;
  status?: string;
  createdAt?: string;
  labels?: Record<string, string>;
};

export type KubePodContainerStatus = {
  name: string;
  ready: boolean;
  restartCount: number;
  state?: string;
};

export type KubePod = {
  name: string;
  namespace: string;
  phase?: string;
  nodeName?: string;
  podIP?: string;
  startTime?: string;
  /** Kind of the controller that owns the pod (StatefulSet, ReplicaSet, ...); absent for a bare pod. */
  ownerKind?: string;
  /** Set once the pod is being deleted, that is while it is Terminating. */
  deletionTimestamp?: string;
  labels?: Record<string, string>;
  containers: KubePodContainerStatus[];
};

export type KubeService = {
  name: string;
  namespace: string;
  type?: string;
  clusterIP?: string;
  labels?: Record<string, string>;
  ports?: Record<string, number>;
};

export type KubeEvent = {
  reason?: string;
  message?: string;
  type?: string;
  lastTimestamp?: string;
  involvedKind?: string;
  involvedName?: string;
};
