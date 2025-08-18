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
    type: 'service-select';
    serviceType: string;
}
export interface GroupFormField extends FormFieldBase {
    type: 'group';
    fields: FormField[];
}
export type FormField = StandardFormField | ServiceSelectFormField | GroupFormField;

export interface ServiceDefinition {
  label: string;
  chart: string;
  defaultRepo: string; // URL du dépôt par défaut
  form: FormField[];
}
export interface AvailableServices {
  [key: string]: ServiceDefinition;
}

export interface ClusterService {
    label: string;
    value: string; // The connection URL, e.g., thrift://...
}