// The time zones offered on the settings screen. The browser supplies the list (IANA region ids); the server is
// the validator and answers 400 "Unknown time zone" for an id its own zone database does not have.

/** Longest zone id the server accepts. */
export const TIME_ZONE_MAX_LENGTH = 50;

/** True when this browser can format times in the zone, which is what makes it safe to offer and to display. */
export function isKnownTimeZone(id: string | null | undefined): id is string {
  const zone = (id ?? '').trim();
  if (!zone || zone.length > TIME_ZONE_MAX_LENGTH) return false;
  // Offsets ("+02:00", "GMT+2") are refused by the server even where a browser accepts them.
  if (/^(GMT|UTC)?[+-]\d/i.test(zone)) return false;
  try {
    new Intl.DateTimeFormat('en-GB', { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

// Used only where the browser cannot list its zones (Intl.supportedValuesOf is missing): the zones of the region
// the clinic serves first, then the large zones of each continent. Each entry is still checked by the browser.
const FALLBACK_ZONES = [
  'Africa/Abidjan', 'Africa/Accra', 'Africa/Addis_Ababa', 'Africa/Algiers', 'Africa/Blantyre', 'Africa/Cairo',
  'Africa/Casablanca', 'Africa/Dar_es_Salaam', 'Africa/Gaborone', 'Africa/Harare', 'Africa/Johannesburg',
  'Africa/Kampala', 'Africa/Kigali', 'Africa/Kinshasa', 'Africa/Lagos', 'Africa/Luanda', 'Africa/Lusaka',
  'Africa/Maputo', 'Africa/Nairobi', 'Africa/Windhoek',
  'America/Bogota', 'America/Chicago', 'America/Denver', 'America/Halifax', 'America/Lima', 'America/Los_Angeles',
  'America/Mexico_City', 'America/New_York', 'America/Phoenix', 'America/Santiago', 'America/Sao_Paulo',
  'America/Toronto', 'America/Vancouver',
  'Asia/Bangkok', 'Asia/Dhaka', 'Asia/Dubai', 'Asia/Hong_Kong', 'Asia/Jakarta', 'Asia/Jerusalem', 'Asia/Karachi',
  'Asia/Kolkata', 'Asia/Manila', 'Asia/Riyadh', 'Asia/Seoul', 'Asia/Shanghai', 'Asia/Singapore', 'Asia/Tokyo',
  'Atlantic/Reykjavik',
  'Australia/Adelaide', 'Australia/Brisbane', 'Australia/Melbourne', 'Australia/Perth', 'Australia/Sydney',
  'Europe/Amsterdam', 'Europe/Athens', 'Europe/Berlin', 'Europe/Dublin', 'Europe/Istanbul', 'Europe/Lisbon',
  'Europe/London', 'Europe/Madrid', 'Europe/Moscow', 'Europe/Paris', 'Europe/Rome', 'Europe/Stockholm',
  'Europe/Zurich',
  'Pacific/Auckland', 'Pacific/Honolulu',
];

let zoneIds: string[] | null = null;

/** Zone ids this browser knows, in alphabetical order, with UTC included. Built once per page load. */
export function listTimeZones(): string[] {
  if (zoneIds) return zoneIds;
  const supportedValuesOf = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf;
  let ids: string[] = [];
  try {
    ids = typeof supportedValuesOf === 'function' ? supportedValuesOf.call(Intl, 'timeZone') : [];
  } catch {
    ids = [];
  }
  if (ids.length === 0) ids = FALLBACK_ZONES;
  const set = new Set(ids.filter(id => isKnownTimeZone(id)));
  // Browsers leave UTC out of the region list; the server accepts it.
  if (isKnownTimeZone('UTC')) set.add('UTC');
  zoneIds = Array.from(set).sort((a, b) => a.localeCompare(b, 'en'));
  return zoneIds;
}

/** The zone's current offset from UTC as the browser words it ("GMT+3", "GMT+5:30", "GMT"); '' when unavailable. */
export function timeZoneOffset(id: string, at: Date = new Date()): string {
  try {
    const options = { timeZone: id, hour: '2-digit', timeZoneName: 'shortOffset' } as Intl.DateTimeFormatOptions;
    const parts = new Intl.DateTimeFormat('en-GB', options).formatToParts(at);
    return parts.find(p => p.type === 'timeZoneName')?.value ?? '';
  } catch {
    return '';
  }
}

/** "Africa/Dar es Salaam": the zone id with its underscores read as spaces. */
export function timeZoneName(id: string): string {
  return id.replace(/_/g, ' ');
}

/** "Africa/Nairobi (GMT+3)", or the bare name when the browser cannot tell the offset. */
export function timeZoneLabel(id: string, at: Date = new Date()): string {
  const offset = timeZoneOffset(id, at);
  return offset ? `${timeZoneName(id)} (${offset})` : timeZoneName(id);
}

export interface TimeZoneOption {
  value: string;
  label: string;
}

let zoneOptions: TimeZoneOption[] | null = null;

/**
 * The zones as select options, labelled with their current offset. `current` (the stored zone) is added when the
 * browser's list does not have it, so a zone saved from another device stays selected instead of looking unset.
 */
export function timeZoneOptions(current?: string | null): TimeZoneOption[] {
  if (!zoneOptions) {
    const now = new Date();
    zoneOptions = listTimeZones().map(id => ({ value: id, label: timeZoneLabel(id, now) }));
  }
  const stored = (current ?? '').trim();
  if (!stored || zoneOptions.some(o => o.value === stored)) return zoneOptions;
  return [{ value: stored, label: isKnownTimeZone(stored) ? timeZoneLabel(stored) : timeZoneName(stored) }, ...zoneOptions];
}
