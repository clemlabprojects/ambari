import { getAtStr, interpolate, setAtStr } from './bindings';

/**
 * Upgrade seeding for form fields kept out of the chart values (`excludeFromValues`) whose effect is
 * a chart value, declared in service.json with `seedFromValue: "<values path>"`.
 *
 * An upgrade starts from the deployed values, but such a field is not one of them: it would show its
 * default, and the bindings would write that default over what runs. OpenMetadata's "Enable Ranger
 * TagSync" switch, for instance, showed off on every upgrade of a release that had it on. This fills
 * each field from the deployed value at its path, in `state` (the wizard's initial form state, with
 * the deployed values already merged in).
 *
 * `seedUnlessEquals` (a `${path}` template rendered against the same state) names the value the chart
 * gets when the field is left blank: a deployed value equal to it is not copied into the field, so the
 * derived value keeps following its inputs (e.g. the TagSync endpoint follows the ingress host).
 *
 * @returns the names of every field declaring `seedFromValue`, filled or not, which the auto-enable
 *   defaults must leave alone. A release without a value at the path runs the chart default (a first
 *   install with the switch off sends none, and neither did any release deployed before the switch was
 *   wired), so switching such a field on would change what runs on an upgrade nobody edited.
 */
export const seedFieldsFromRelease = (fields: any[] | undefined, state: any): string[] => {
  const seeded: string[] = [];
  const walk = (list: any[]) => {
    for (const f of list || []) {
      if (!f || typeof f !== 'object') continue;
      if (Array.isArray(f.fields)) walk(f.fields);
      if (!f.name || !f.seedFromValue) continue;
      seeded.push(f.name);
      const deployed = getAtStr(state, f.seedFromValue);
      if (deployed == null || String(deployed).trim() === '') continue;
      if (f.seedUnlessEquals && String(deployed) === interpolate(f.seedUnlessEquals, state)) continue;
      setAtStr(state, f.name, deployed);
    }
  };
  walk(fields || []);
  return seeded;
};
