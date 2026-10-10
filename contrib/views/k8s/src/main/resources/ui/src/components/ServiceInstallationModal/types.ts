// Binding spec types (unchanged, just exported)
export type BindingSpecBase = { role?: string; field?: string; mountKey?: string; suffix?: string };

export type BindingTarget =
  | {
      path: string;                                         // dot path; [] means append to array
      kind?: 'volume' | 'mount';                            // for K8s shapes built from mount spec
      mountKey?: string;
      op?: 'set' | 'merge';
      value?: any;
      from?: { type: 'mountPath'; mountKey: string; suffix?: string }
          // format 'labels': the form holds "key=value, key2=value2"; the target receives { key: value, ... }
          | { type: 'form'; field: string; suffix?: string; format?: 'labels' };
    };

export type BindingSpec = BindingSpecBase & { targets?: BindingTarget[] };
