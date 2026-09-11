import { createContext } from 'react';

/**
 * Re-sync the wizard's `installValues` from the antd form store.
 *
 * antd `form.setFieldValue` / `setFieldsValue` update the store but do NOT fire the Form's
 * `onValuesChange`, which is what the install wizard uses to mirror the form into `installValues`
 * (the source of truth for `formValues` and the binding engine). So any code that programmatically
 * populates a DIFFERENT field than the one the user touched — e.g. a `k8s-discovery` /
 * `hadoop-discovery` field filling its `targetHost` / `targetPort` — must call this AFTER the set,
 * or the value is stranded in the form store and never reaches `installValues` (so the deploy /
 * bindings never see it).
 *
 * Provided by the wizard step (InstallStep) as its `onValuesChange`. Undefined outside the wizard,
 * where the optional-chained call is a harmless no-op.
 */
export const FieldSyncContext = createContext<(() => void) | undefined>(undefined);
