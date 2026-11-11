import { useEffect, useMemo, useState } from 'react';
import * as api from '../api';
import BigNumbers from '../components/BigNumbers';
import FilterPanel from '../components/FilterPanel';
import MapView from '../components/MapView';
import NetworkCard from '../components/NetworkCard';
import { activeCount, toParams, type Filters } from '../filters';
import type { Look } from '../mapController';
import { navigate, type Route } from '../router';
import type { Facets, Network, Stats } from '../types';
import { fmtInt } from '../util';

export interface PageProps {
  filters: Filters;
  setFilters: (f: Filters) => void;
  facets: Facets | null;
  onTermLost: () => void;
}

export function useStats(filters: Filters, onTermLost: () => void): { stats: Stats | null; error: string } {
  const [stats, setStats] = useState<Stats | null>(null);
  const [error, setError] = useState('');
  const key = toParams(filters).toString();
  useEffect(() => {
    const ac = new AbortController();
    const t = setTimeout(() => {
      api.getStats(filters, ac.signal).then(
        (s) => { setStats(s); setError(''); },
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
  return { stats, error };
}

export default function Dashboard({ route, filters, setFilters, facets, onTermLost }: PageProps & { route: Route }) {
  const { stats, error } = useStats(filters, onTermLost);
  const [panelOpen, setPanelOpen] = useState(false);
  const [selected, setSelected] = useState<Network | null>(null);
  const look: Look = route.params.get('mode') === 'heat' ? 'heat' : 'points';
  const initialView = useMemo(() => route.params.get('v'), []); // eslint-disable-line react-hooks/exhaustive-deps
  const sel = route.params.get('sel');
  const n = activeCount(filters);

  const patch = (change: (p: URLSearchParams) => void) => {
    const p = new URLSearchParams(location.hash.split('?')[1] ?? '');
    change(p);
    navigate('/', p, true);
  };

  const count = stats ? `${fmtInt(stats.total)} rede(s) · ${fmtInt(stats.with_position)} com posição` : 'Carregando…';
  const topCities = (facets?.cities ?? []).slice(0, 6);

  return (
    <div className="dash">
      <BigNumbers stats={stats} filters={filters} onChange={setFilters} />
      {error && <p className="error" role="alert">{error}</p>}

      <div className="dash-body">
        <button type="button" className="btn filters-toggle" aria-expanded={panelOpen} aria-controls="painel-filtros" onClick={() => setPanelOpen((v) => !v)}>
          {panelOpen ? 'Fechar filtros' : `Filtros${n ? ` (${n})` : ''}`}
        </button>
        <div id="painel-filtros" className={`side${panelOpen ? ' is-open' : ''}`}>
          <FilterPanel filters={filters} onChange={setFilters} cities={facets?.cities ?? []} vendors={facets?.vendors ?? []} count={count} />
        </div>

        <div className="stage">
          {topCities.length > 0 && (
            <div className="shortcuts" role="group" aria-label="Atalhos por cidade">
              <span className="muted small">Ir para</span>
              {topCities.map((c) => (
                <button key={c.name} type="button" className="chip" aria-pressed={filters.city.length === 1 && filters.city[0] === c.name}
                  onClick={() => setFilters({ ...filters, city: filters.city.length === 1 && filters.city[0] === c.name ? [] : [c.name] })}>
                  {c.name} <small className="num">{fmtInt(c.count)}</small>
                </button>
              ))}
            </div>
          )}
          <MapView
            filters={filters}
            initialView={initialView}
            selected={selected?.bssid ?? sel}
            look={look}
            onLook={(l) => patch((p) => (l === 'heat' ? p.set('mode', 'heat') : p.delete('mode')))}
            onView={(v) => patch((p) => p.set('v', v))}
            onSelect={(net) => { setSelected(net); patch((p) => (net ? p.set('sel', net.bssid) : p.delete('sel'))); }}
            onTermLost={onTermLost}
          />
          {selected && (
            <div className="float-card">
              <NetworkCard net={selected} onClose={() => { setSelected(null); patch((p) => p.delete('sel')); }} mapLink={false} />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
