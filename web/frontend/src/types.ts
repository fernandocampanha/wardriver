export type Encryption = 'OPEN' | 'WEP' | 'WPA' | 'WPA2' | 'WPA3' | 'UNKNOWN';


export interface Network {
  bssid: string;
  ssid: string;
  hidden: boolean;
  channel: number;
  rssi: number;
  enc: Encryption;
  seen: number;
  wps: boolean;
  lat: number | null;
  lon: number | null;
  last: number;
  city: string;
  vendor: string;
}

export interface Facet {
  name: string;
  count: number;
}

export interface Facets {
  cities: Facet[];
  vendors: Facet[];
  term_version: string;
}

export interface Page {
  total: number;
  limit: number;
  offset: number;
  networks: Network[];
}


export type Bounds = [number, number, number, number];

export interface Cell {
  lat: number;
  lon: number;
  n: number;
  weak: number;
}

export type MapResponse =
  | { mode: 'points'; total: number; truncated: boolean; bounds: Bounds | null; points: Network[] }
  | { mode: 'cells'; total: number; bounds: Bounds | null; cells: Cell[] };

export interface ImportRow {
  id: number;
  at_ms: number;
  source: string;
  layout: string;
  rows_read: number;
  rows_skipped: number;
  networks: number;
  added: number;
  updated: number;
  names_filled: number;
  with_position: number;
}

export interface SyncHistory {
  total: number;
  limit: number;
  offset: number;
  events: ImportRow[];
}

export interface Stats {
  total: number;
  with_position: number;
  hidden: number;
  wps: number;
  weak: number;
  default_names: number;
  hidden_origin: { unnamed: number; with_visible_sibling: number; locally_administered: number };
  encryption: { name: string; count: number }[];
  channels: { channel: number; count: number }[];
  channels_other: number;
  signal: { name: string; count: number }[];
  top_ssids: { ssid: string; count: number }[];
  cities: Facet[];
  vendors: Facet[];
  first_seen_ms: number | null;
  last_seen_ms: number | null;
  last_import: ImportRow | null;
}
