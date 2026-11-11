import type { Network } from './types';

const nf = new Intl.NumberFormat('pt-BR');
export const fmtInt = (n: number): string => nf.format(n);
export const pct = (n: number, total: number): number => (total > 0 ? Math.round((n * 100) / total) : 0);

export function fmtDate(ms: number | null | undefined): string {
  if (!ms || ms <= 0) return '—';
  return new Date(ms).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' });
}

export function fmtDay(ms: number | null | undefined): string {
  if (!ms || ms <= 0) return '—';
  return new Date(ms).toLocaleDateString('pt-BR', { dateStyle: 'medium' });
}

export const hasPos = (n: Network): n is Network & { lat: number; lon: number } => n.lat !== null && n.lon !== null;

export function fmtPos(n: Network): string {
  return hasPos(n) ? `${n.lat.toFixed(5)}, ${n.lon.toFixed(5)}` : 'sem posição';
}

export const encLabel = (enc: string): string => (enc === 'UNKNOWN' ? 'Desconhecida' : enc);
export const isWeak = (n: Network): boolean => n.enc === 'OPEN' || n.enc === 'WEP';

export function storageGet(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function storageSet(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {

  }
}

const NICE_STEPS = [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10];
export function niceMax(value: number): number {
  if (value <= 0) return 1;
  const exp = 10 ** Math.floor(Math.log10(value));
  return (NICE_STEPS.find((s) => s * exp >= value) ?? 10) * exp;
}
