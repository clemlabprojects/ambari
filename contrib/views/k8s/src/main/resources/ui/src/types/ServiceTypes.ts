// --- Interfaces for API responses ---
export interface FormFieldBase {
  name: string;
  label: string;
  help?: string;
}
export interface StandardFormField extends FormFieldBase {
  type: 'string' | 'number' | 'boolean' | 'select';
  required?: boolean;
  defaultValue?: any;
  disabled?: boolean; // <-- Ajout de la propriété optionnelle
  options?: Array<{ label: string; value: string; }>;
}
export interface ServiceSelectFormField extends FormFieldBase {
    type: 'service-select' | 'hadoop-discovery' | 'monitoring-discovery';
    serviceType?: string;
}


export interface K8sDiscoveryFormField extends FormFieldBase {
    type: 'k8s-discovery';
    lookupLabel: string;
}
export interface GroupFormField extends FormFieldBase {
    type: 'group';
    fields: FormField[];
}
export type FormField = StandardFormField | ServiceSelectFormField | K8sDiscoveryFormField | GroupFormField;

export interface ServiceDefinition {
  label: string;
  chart: string;
  defaultRepo: string; // URL du dépôt par défaut
  form: FormField[];
  // Optional backend-driven metadata for post-install actions (e.g., keytab/ranger replay).
  kerberos?: Array<Record<string, any>>;
  ranger?: Record<string, Record<string, any>>;
}
export interface AvailableServices {
  [key: string]: ServiceDefinition;
}

export interface ClusterService {
    label: string;
    value: string; // The connection URL, e.g., thrift://...
}
