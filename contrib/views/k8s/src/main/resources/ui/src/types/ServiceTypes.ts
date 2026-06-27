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
export interface SecretDiscoveryFormField extends FormFieldBase {
    // Lookup driven by Kubernetes Secret labels rather than Service labels.
    // Used today by the Company CA picker in KDPS/_shared/ingress.json which
    // surfaces Secrets stored in `ambari-pki` via the PKI registry.
    type: 'secret-discovery';
    lookupLabel: string;
}
export interface GroupFormField extends FormFieldBase {
    type: 'group';
    fields: FormField[];
}
/**
 * Placeholder field that renders the auth-mode dropdown + conditional Secret
 * picker for one entry in the service def's `externalServiceTargets` map. The
 * `target` attribute matches a top-level key in `externalServiceTargets`.
 *
 * The component reads the referenced entry from `ServiceDefinition.externalServiceTargets`
 * to know which auth modes are supported and which form-field paths to write to
 * (modeField, secretField). See docs/EXTERNAL_SERVICE_TARGETS.md.
 */
export interface ExternalAuthTargetFormField extends FormFieldBase {
    type: 'external-auth-target';
    target: string;
}
/**
 * Renders the value KDPS derives for {@link contextField} on the selected Platform Context
 * (e.g. "hive.hs2HostPort") as a read-only "blue" value, with an Override switch that reveals
 * an editable input bound to {@link name}. On a MANAGED context the value is computed live from
 * Ambari; on an EXTERNAL context with no resolved value the operator supplies {@link name}
 * directly and the engine computes the connection from it. Makes it visually explicit that
 * the endpoint/scheme/auth come from KDPS, not operator free-text.
 */
export interface ContextResolvedFormField extends FormFieldBase {
    type: 'context-resolved';
    contextField: string;       // "<capability>.<field>", e.g. "hive.hs2HostPort"
    placeholder?: string;
    required?: boolean;
    disabled?: boolean;
    // For an EXTERNAL context with no resolved value: render these raw-property sub-fields
    // (e.g. transport + TLS) instead of a free-text box; the KDPS engine derives this field's
    // value from them. This field's own `name` stays blank so the backend computes it.
    externalFields?: FormField[];
}
export type FormField =
    | StandardFormField
    | ServiceSelectFormField
    | K8sDiscoveryFormField
    | SecretDiscoveryFormField
    | GroupFormField
    | ExternalAuthTargetFormField
    | ContextResolvedFormField;

/**
 * One outbound connection the service can make (e.g. "hive", "ranger", "atlas").
 * See docs/EXTERNAL_SERVICE_TARGETS.md for the full contract.
 */
export interface ExternalAuthMode {
    label: string;
    secretField?: string;
    secretKeys?: string[];
    applyTo?: Record<string, string>;
}
export interface ExternalServiceTarget {
    label: string;
    discoveryServiceType?: string;
    urlOverrideField: string;
    modeField?: string;
    authModes: Record<string, ExternalAuthMode>;
}

export interface ServiceDefinition {
  label: string;
  chart: string;
  description?: string;
  version?: string;
  defaultRepo?: string;
  form: FormField[];
  // Optional backend-driven metadata for post-install actions (e.g., keytab/ranger replay).
  kerberos?: Array<Record<string, any>>;
  ranger?: Record<string, Record<string, any>>;
  oidc?: Array<Record<string, any>>;
  externalServiceTargets?: Record<string, ExternalServiceTarget>;
}
export interface AvailableServices {
  [key: string]: ServiceDefinition;
}

export interface ClusterService {
    label: string;
    value: string; // The connection URL, e.g., thrift://...
    port?: string; // Optional port for k8s-discovery auto-fill
}

export interface K8sDiscoveryFormFieldExtended extends K8sDiscoveryFormField {
    targetHost?: string; // form field name to auto-fill with discovered host
    targetPort?: string; // form field name to auto-fill with discovered port
}
