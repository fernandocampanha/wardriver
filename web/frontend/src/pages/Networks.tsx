import { useEffect, useRef, useState } from 'react';
import * as api from '../api';
import FilterPanel from '../components/FilterPanel';
import NetworkCard from '../components/NetworkCard';
import { activeCount, toParams } from '../filters';
import { navigate, type Route } from '../router';
import type { Network, Page } from '../types';
import { encLabel, fmtDate, fmtInt } from '../util';
import type { PageProps } from './Dashboard';

const PAGE_SIZE = 50;
const COLUMNS: { key: string; label: string; num?: boolean }[] = [
  { key: 'ssid', label: 'Nome (SSID)' },
  { key: 'bssid', label: 'BSSID' },
  { key: 'vendor', label: 'Fabricante' },
  { key: 'city', label: 'Cidade' },
  { key: 'channel', label: 'Canal', num: true },
  { key: 'rssi', label: 'Sinal', num: true },
  { key: 'enc', label: 'Criptografia' },
  { key: 'seen', label: 'Vista', num: true },
  { key: 'last', label: 'Última captura' },
];
const SORTABLE = new Set(['ssid', 'bssid', 'vendor', 'city', 'channel', 'rssi', 'seen', 'last']);
const ENC_KEY: Record<string, string> = { OPEN: 'open', WEP: 'wep', WPA: 'wpa', WPA2: 'wpa2', WPA3: 'wpa3', UNKNOWN: 'unknown' };

function parseSort(raw: string | null): { key: string; desc: boolean } {
  const [key, dir] = (raw ?? '').split(':');
  return SORTABLE.has(key) ? { key, desc: dir !== 'asc' } : { key: 'rssi', desc: true };
}

export default function Networks({ route, filters, setFilters, facets, onTermLost }: PageProps & { route: Route }) {
  const sort = parseSort(route.params.get('sort'));
  const pageIdx = Math.max(0, Math.min(200_000, Number(route.params.get('p')) || 0));
  const [data, setData] = useState<Page | null>(null);
  const [error, setError] = useState('');
  const [open, setOpen] = useState<Network | null>(null);
  const [panelOpen, setPanelOpen] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);
  const key = `${toParams(filters)}|${sort.key}${sort.desc}|${pageIdx}`;

  useEffect(() => {
    const ac = new AbortController();
    const t = setTimeout(() => {
      api.getNetworks(filters, sort, pageIdx * PAGE_SIZE, PAGE_SIZE, ac.signal).then(
        (d) => { setData(d); setError(''); },
        (e) => {
          if (ac.signal.aborted) return;
          if (e instanceof api.TermError) onTermLost();
          else setError((e as Error).message);
        },
      );
    }, 120);
    return () => { clearTimeout(t); ac.abort(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, onTermLost]);

  useEffect(() => {
    if (open && dialog.current && !dialog.current.open) dialog.current.showModal();
  }, [open]);

  const patch = (change: (p: URLSearchParams) => void) => {
    const p = new URLSearchParams(location.hash.split('?')[1] ?? '');
    change(p);
    navigate('/redes', p, true);
  };
  const sortBy = (k: string) => patch((p) => { p.set('sort', `${k}:${sort.key === k && sort.desc ? 'asc' : 'desc'}`); p.delete('p'); });
  const total = data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const n = activeCount(filters);
  const count = data ? `${fmtInt(total)} rede(s)` : 'Carregando…';

  return (
    <div className="dash-body">
      <button type="button" className="btn filters-toggle" aria-expanded={panelOpen} aria-controls="painel-filtros" onClick={() => setPanelOpen((v) => !v)}>
        {panelOpen ? 'Fechar filtros' : `Filtros${n ? ` (${n})` : ''}`}
      </button>
      <div id="painel-filtros" className={`side${panelOpen ? ' is-open' : ''}`}>
        <FilterPanel filters={filters} onChange={setFilters} cities={facets?.cities ?? []} vendors={facets?.vendors ?? []} count={count} />
      </div>

      <section className="stage table-stage" aria-label="Lista de redes">
        {error && <p className="error" role="alert">{error}</p>}
        <div className="table-scroll">
          <table className="data nets">
            <thead>
              <tr>
                {COLUMNS.map((c) => {
                  const sortable = SORTABLE.has(c.key);
                  const active = sort.key === c.key;
                  return (
                    <th key={c.key} className={c.num ? 'num' : ''} aria-sort={active ? (sort.desc ? 'descending' : 'ascending') : undefined}>
                      {sortable ? (
                        <button type="button" className="th-btn" onClick={() => sortBy(c.key)}>
                          {c.label}
                          <span aria-hidden="true">{active ? (sort.desc ? ' ↓' : ' ↑') : ''}</span>
                        </button>
                      ) : (
                        c.label
                      )}
                    </th>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              {data?.networks.map((r) => (
                <tr key={r.bssid} tabIndex={0} onClick={() => setOpen(r)} onKeyDown={(e) => { if (e.key === 'Enter') setOpen(r); }}>
                  <td className="nets-title">{r.hidden ? <em className="muted">(oculta)</em> : r.ssid}</td>
                  <td className="mono" data-label="BSSID">{r.bssid}</td>
                  <td data-label="Fabricante">{r.vendor || '—'}</td>
                  <td data-label="Cidade">{r.city || '—'}</td>
                  <td className="num" data-label="Canal">{r.channel || '—'}</td>
                  <td className="num" data-label="Sinal">{r.rssi}</td>
                  <td className="nets-enc"><span className={`enc-tag enc-${ENC_KEY[r.enc] ?? 'unknown'}`}>{encLabel(r.enc)}{r.wps ? ' · WPS' : ''}</span></td>
                  <td className="num nets-extra" data-label="Vista">{fmtInt(r.seen)}</td>
                  <td className="nets-extra" data-label="Última captura">{fmtDate(r.last)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {data && data.networks.length === 0 && <p className="empty">Nenhuma rede com estes filtros.</p>}
        </div>
        <nav className="pager" aria-label="Paginação">
          <button type="button" className="btn btn-small" disabled={pageIdx === 0} onClick={() => patch((p) => (pageIdx - 1 > 0 ? p.set('p', String(pageIdx - 1)) : p.delete('p')))}>
            Anterior
          </button>
          <span>Página {fmtInt(pageIdx + 1)} de {fmtInt(pages)}</span>
          <button type="button" className="btn btn-small" disabled={pageIdx + 1 >= pages} onClick={() => patch((p) => p.set('p', String(pageIdx + 1)))}>
            Próxima
          </button>
        </nav>
      </section>

      <dialog ref={dialog} className="sheet" aria-label="Detalhes da rede" onClose={() => setOpen(null)} onClick={(e) => { if (e.target === dialog.current) dialog.current?.close(); }}>
        {open && <NetworkCard net={open} onClose={() => dialog.current?.close()} />}
      </dialog>
    </div>
  );
}
