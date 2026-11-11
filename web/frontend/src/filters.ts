import type { Encryption } from './types';

export interface Filters {
  q: string;
  enc: Encryption[];
  channel: number[];
  rssiMin: string;
  rssiMax: string;
  wps: '' | 'yes' | 'no';
  hidden: '' | 'only' | 'no';
  defaultName: boolean;
  localAdmin: boolean;
  hasPos: boolean;
  seenMin: string;
  since: string;
  until: string;
  city: string[];
  vendor: string[];
}

export const NO_FILTERS: Filters = {
  q: '', enc: [], channel: [], rssiMin: '', rssiMax: '', wps: '', hidden: '', defaultName: false, localAdmin: false,
  hasPos: false, seenMin: '', since: '', until: '', city: [], vendor: [],
};

export const ENCRYPTIONS: Encryption[] = ['OPEN', 'WEP', 'WPA', 'WPA2', 'WPA3', 'UNKNOWN'];
const ENC_SET = new Set<string>(ENCRYPTIONS);
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const INT_RE = /^-?\d{1,10}$/;

const list = (v: string | null): string[] => (v ? v.split('|').filter(Boolean) : []);

export function fromParams(p: URLSearchParams): Filters {
  const wps = p.get('wps');
  const hidden = p.get('hidden');
  const date = (k: string) => (DATE_RE.test(p.get(k) ?? '') ? (p.get(k) as string) : '');
  const int = (k: string) => (INT_RE.test(p.get(k) ?? '') ? (p.get(k) as string) : '');
  return {
    q: (p.get('q') ?? '').slice(0, 100),
    enc: (p.get('enc') ?? '').split(',').filter((e): e is Encryption => ENC_SET.has(e)),
    channel: (p.get('channel') ?? '').split(',').filter(Boolean).map(Number).filter((n) => Number.isInteger(n) && n >= 0 && n <= 196),
    rssiMin: int('rssi_min'),
    rssiMax: int('rssi_max'),
    wps: wps === 'true' ? 'yes' : wps === 'false' ? 'no' : '',
    hidden: hidden === 'only' || hidden === 'no' ? hidden : '',
    defaultName: p.get('default_name') === 'true',
    localAdmin: p.get('local_admin') === 'true',
    hasPos: p.get('has_pos') === 'true',
    seenMin: int('seen_min'),
    since: date('since'),
    until: date('until'),
    city: list(p.get('city')),
    vendor: list(p.get('vendor')),
  };
}

export function toParams(f: Filters): URLSearchParams {
  const p = new URLSearchParams();
  if (f.q.trim()) p.set('q', f.q.trim());
  if (f.enc.length) p.set('enc', f.enc.join(','));
  if (f.channel.length) p.set('channel', f.channel.join(','));
  if (f.rssiMin) p.set('rssi_min', f.rssiMin);
  if (f.rssiMax) p.set('rssi_max', f.rssiMax);
  if (f.wps) p.set('wps', f.wps === 'yes' ? 'true' : 'false');
  if (f.hidden) p.set('hidden', f.hidden);
  if (f.defaultName) p.set('default_name', 'true');
  if (f.localAdmin) p.set('local_admin', 'true');
  if (f.hasPos) p.set('has_pos', 'true');
  if (f.seenMin) p.set('seen_min', f.seenMin);
  if (f.since) p.set('since', f.since);
  if (f.until) p.set('until', f.until);
  if (f.city.length) p.set('city', f.city.join('|'));
  if (f.vendor.length) p.set('vendor', f.vendor.join('|'));
  return p;
}

export function toApiParams(f: Filters): URLSearchParams {
  const p = toParams(f);
  if (f.since) p.set('since', String(new Date(`${f.since}T00:00:00`).getTime()));
  if (f.until) p.set('until', String(new Date(`${f.until}T23:59:59.999`).getTime()));
  return p;
}

export function activeCount(f: Filters): number {
  return [...toParams(f).keys()].length;
}

export const same = (a: Filters, b: Filters): boolean => toParams(a).toString() === toParams(b).toString();
