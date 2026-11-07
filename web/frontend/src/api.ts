import { toApiParams, type Filters } from './filters';
import type { Facets, MapResponse, Page, Stats, SyncHistory } from './types';

const CSRF = { 'X-Requested-With': 'wardriver-web' };


export class TermError extends Error {}

async function call(path: string, options: RequestInit = {}): Promise<Response> {
  const res = await fetch(path, {
    credentials: 'same-origin',
    ...options,
    headers: { ...CSRF, ...(options.headers as Record<string, string> | undefined) },
  });
  if (res.status === 403) throw new TermError('termo não aceito');
  if (res.status === 429) {
    const wait = Number(res.headers.get('Retry-After')) || 30;
    throw new Error(`Muitas consultas seguidas. Aguarde ${wait} s e tente de novo.`);
  }
  if (!res.ok) {
    let message = `erro ${res.status}`;
    try {
      message = (await res.json()).error || message;
    } catch {

    }
    throw new Error(message);
  }
  return res;
}

export async function me(): Promise<{ accepted: boolean; termVersion: string }> {
  const res = await fetch('/api/me', { credentials: 'same-origin' });
  const data = await res.json();
  return { accepted: data.accepted === true, termVersion: String(data.term_version ?? '') };
}

export async function login(version: string, password: string): Promise<void> {
  await call('/api/term/accept', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ version, password }),
  });
}

export const revokeTerm = (): Promise<Response> => call('/api/term/revoke', { method: 'POST' });

function query(f: Filters, extra: Record<string, string | number>): string {
  const p = toApiParams(f);
  for (const [k, v] of Object.entries(extra)) p.set(k, String(v));
  return p.toString();
}

export interface Sort {
  key: string;
  desc: boolean;
}

export async function getNetworks(f: Filters, sort: Sort, offset: number, limit: number, signal?: AbortSignal): Promise<Page> {
  const q = query(f, { sort: sort.key, order: sort.desc ? 'desc' : 'asc', offset, limit });
  return (await call(`/api/networks?${q}`, { signal })).json();
}

export async function getMap(f: Filters, bbox: string, zoom: number, mode: 'auto' | 'cells', signal?: AbortSignal): Promise<MapResponse> {
  const p = toApiParams(f);
  if (bbox) p.set('bbox', bbox);
  p.set('zoom', String(zoom));
  p.set('mode', mode);
  return (await call(`/api/map?${p}`, { signal })).json();
}

export async function getStats(f: Filters, signal?: AbortSignal): Promise<Stats> {
  return (await call(`/api/stats?${toApiParams(f)}`, { signal })).json();
}

export async function getFacets(): Promise<Facets> {
  return (await call('/api/facets')).json();
}

export async function getSyncHistory(offset: number, limit: number, signal?: AbortSignal): Promise<SyncHistory> {
  return (await call(`/api/sync-history?offset=${offset}&limit=${limit}`, { signal })).json();
}
