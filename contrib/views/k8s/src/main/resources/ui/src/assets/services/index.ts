import trinoIcon from './trino.svg';
import supersetIcon from './superset.svg';
import gitlabIcon from './gitlab.svg';
import sqlAssistantIcon from './sql-assistant.svg';
import openmetadataIcon from './openmetadata.svg';
import genericIcon from './generic.svg';

export const SERVICE_ICONS: Record<string, string> = {
  TRINO: trinoIcon,
  SUPERSET: supersetIcon,
  GITLAB: gitlabIcon,
  'SQL-ASSISTANT': sqlAssistantIcon,
  OPENMETADATA: openmetadataIcon,
};

/** Generic logo for any service the catalog knows nothing about (e.g. a custom drop-in chart). */
export const DEFAULT_SERVICE_ICON: string = genericIcon;

/**
 * Icon for a service key. Falls back to a generic logo so a custom service (any
 * KDPS/services/<KEY>/service.json, incl. ones not in catalog.json) still renders a card/row icon.
 */
export function serviceIcon(key: string | undefined | null): string {
  return (key && SERVICE_ICONS[String(key).toUpperCase()]) || DEFAULT_SERVICE_ICON;
}
