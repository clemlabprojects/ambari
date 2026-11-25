import trinoIcon from './trino.svg';
import supersetIcon from './superset.svg';
import sqlAssistantIcon from './sql-assistant.svg';
import openmetadataIcon from './openmetadata.svg';
import genericIcon from './generic.svg';
import { API_BASE_URL } from '../../api/client';

export const SERVICE_ICONS: Record<string, string> = {
  TRINO: trinoIcon,
  SUPERSET: supersetIcon,
  'SQL-ASSISTANT': sqlAssistantIcon,
  OPENMETADATA: openmetadataIcon,
};

/** Generic logo for any service the catalog knows nothing about (e.g. a custom drop-in chart). */
export const DEFAULT_SERVICE_ICON: string = genericIcon;

/**
 * Icon for a service key. A built-in service has its own logo; an add-on installed on the Ambari server
 * (definition.addon.icon) is served by the backend; anything else (a custom KDPS/services/<KEY>/service.json,
 * an add-on without an icon) gets the generic logo.
 */
export function serviceIcon(key: string | undefined | null, definition?: { addon?: { icon?: boolean } | null } | null): string {
  const builtIn = key ? SERVICE_ICONS[String(key).toUpperCase()] : undefined;
  if (builtIn) return builtIn;
  if (key && definition?.addon?.icon) {
    return `${API_BASE_URL}/services/${encodeURIComponent(String(key).toUpperCase())}/icon`;
  }
  return DEFAULT_SERVICE_ICON;
}
