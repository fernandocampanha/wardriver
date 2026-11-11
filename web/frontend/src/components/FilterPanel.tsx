import { useEffect, useMemo, useState } from 'react';
import { ENCRYPTIONS, NO_FILTERS, activeCount, type Filters } from '../filters';
import type { Encryption, Facet } from '../types';
import { encLabel, fmtInt } from '../util';
import { SearchIcon } from './Icons';

const CHANNELS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13];
const SIGNAL_PRESETS: [string, string][] = [
  ['', 'Qualquer'],
  ['-50', 'Forte (≥ −50)'],
  ['-60', 'Bom (≥ −60)'],
  ['-70', 'Médio (≥ −70)'],
  ['-80', 'Fraco (≥ −80)'],
];
const ENC_KEY: Record<Encryption, string> = { OPEN: 'open', WEP: 'wep', WPA: 'wpa', WPA2: 'wpa2', WPA3: 'wpa3', UNKNOWN: 'unknown' };

interface Props {
  filters: Filters;
  onChange: (f: Filters) => void;
  cities: Facet[];
  vendors: Facet[];

  count: string;
}

const toggle = <T,>(arr: T[], v: T): T[] => (arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v]);

export default function FilterPanel({ filters, onChange, cities, vendors, count }: Props) {
  const [text, setText] = useState(filters.q);
  useEffect(() => setText(filters.q), [filters.q]);

  useEffect(() => {
    if (text.trim() === filters.q.trim()) return;
    const t = setTimeout(() => onChange({ ...filters, q: text }), 250);
    return () => clearTimeout(t);
  }, [text, filters, onChange]);

  const set = (patch: Partial<Filters>) => onChange({ ...filters, ...patch });
  const n = activeCount(filters);

  return (
    <form className="filters" aria-label="Filtros" onSubmit={(e) => e.preventDefault()}>
      <div className="filters-head">
        <p className="count" aria-live="polite">{count}</p>
        <button type="button" className="link-btn" disabled={n === 0} onClick={() => onChange(NO_FILTERS)}>
          Limpar filtros{n > 0 ? ` (${n})` : ''}
        </button>
      </div>

      <label className="search">
        <SearchIcon />
        <input
          type="search"
          placeholder="Nome, BSSID ou “oculta”"
          aria-label="Buscar por nome ou BSSID"
          autoComplete="off"
          maxLength={100}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') set({ q: text });
          }}
        />
      </label>

      <div className="presets" role="group" aria-label="Atalhos">
        <button type="button" className="chip" aria-pressed={filters.enc.join() === 'OPEN,WEP'} onClick={() => set({ enc: filters.enc.join() === 'OPEN,WEP' ? [] : ['OPEN', 'WEP'] })}>
          Só risco (aberta ou WEP)
        </button>
        <button type="button" className="chip" aria-pressed={filters.hidden === 'only'} onClick={() => set({ hidden: filters.hidden === 'only' ? '' : 'only' })}>
          Ocultas
        </button>
        <button type="button" className="chip" aria-pressed={filters.wps === 'yes'} onClick={() => set({ wps: filters.wps === 'yes' ? '' : 'yes' })}>
          WPS ativo
        </button>
      </div>

      <Group title="Criptografia" active={filters.enc.length > 0} open>
        <div className="chips">
          {ENCRYPTIONS.map((e) => (
            <label key={e} className={`chip chip-enc enc-${ENC_KEY[e]}`}>
              <input type="checkbox" checked={filters.enc.includes(e)} onChange={() => set({ enc: toggle(filters.enc, e) })} />
              <span>{encLabel(e)}</span>
            </label>
          ))}
        </div>
      </Group>

      <Group title="Canal" active={filters.channel.length > 0}>
        <div className="chips">
          {CHANNELS.map((c) => (
            <label key={c} className="chip chip-num">
              <input type="checkbox" checked={filters.channel.includes(c)} onChange={() => set({ channel: toggle(filters.channel, c) })} />
              <span>{c}</span>
            </label>
          ))}
        </div>
      </Group>

      <Group title="Sinal (dBm)" active={!!filters.rssiMin || !!filters.rssiMax}>
        <label className="field">
          <span>Sinal mínimo</span>
          <select value={SIGNAL_PRESETS.some(([v]) => v === filters.rssiMin) ? filters.rssiMin : ''} onChange={(e) => set({ rssiMin: e.target.value })}>
            {SIGNAL_PRESETS.map(([v, l]) => (
              <option key={v} value={v}>{l}</option>
            ))}
          </select>
        </label>
        <div className="pair">
          <label className="field">
            <span>De (mín.)</span>
            <input type="number" inputMode="numeric" min={-127} max={0} placeholder="−127" value={filters.rssiMin} onChange={(e) => set({ rssiMin: e.target.value })} />
          </label>
          <label className="field">
            <span>Até (máx.)</span>
            <input type="number" inputMode="numeric" min={-127} max={0} placeholder="0" value={filters.rssiMax} onChange={(e) => set({ rssiMax: e.target.value })} />
          </label>
        </div>
      </Group>

      <Group title="Características" active={!!(filters.wps || filters.hidden || filters.defaultName || filters.localAdmin || filters.hasPos || filters.seenMin)}>
        <label className="field">
          <span>WPS</span>
          <select value={filters.wps} onChange={(e) => set({ wps: e.target.value as Filters['wps'] })}>
            <option value="">Tanto faz</option>
            <option value="yes">Ativo</option>
            <option value="no">Inativo</option>
          </select>
        </label>
        <label className="field">
          <span>Nome (SSID)</span>
          <select value={filters.hidden} onChange={(e) => set({ hidden: e.target.value as Filters['hidden'] })}>
            <option value="">Todas</option>
            <option value="only">Somente ocultas</option>
            <option value="no">Somente com nome</option>
          </select>
        </label>
        <label className="check">
          <input type="checkbox" checked={filters.localAdmin} onChange={(e) => set({ localAdmin: e.target.checked })} />
          <span>Ocultas de interface virtual (BSSID local)</span>
        </label>
        <label className="check">
          <input type="checkbox" checked={filters.defaultName} onChange={(e) => set({ defaultName: e.target.checked })} />
          <span>Nome de fábrica não alterado</span>
        </label>
        <label className="check">
          <input type="checkbox" checked={filters.hasPos} onChange={(e) => set({ hasPos: e.target.checked })} />
          <span>Somente com posição</span>
        </label>
        <label className="field">
          <span>Vista pelo menos (vezes)</span>
          <input type="number" inputMode="numeric" min={1} placeholder="1" value={filters.seenMin} onChange={(e) => set({ seenMin: e.target.value })} />
        </label>
      </Group>

      <Group title="Período da captura" active={!!(filters.since || filters.until)}>
        <div className="pair">
          <label className="field">
            <span>De</span>
            <input type="date" value={filters.since} max={filters.until || undefined} onChange={(e) => set({ since: e.target.value })} />
          </label>
          <label className="field">
            <span>Até</span>
            <input type="date" value={filters.until} min={filters.since || undefined} onChange={(e) => set({ until: e.target.value })} />
          </label>
        </div>
      </Group>

      <Group title="Cidade" active={filters.city.length > 0}>
        <Picker items={cities} selected={filters.city} onChange={(city) => set({ city })} what="cidade" />
      </Group>

      <Group title="Fabricante do roteador" active={filters.vendor.length > 0}>
        <Picker items={vendors} selected={filters.vendor} onChange={(vendor) => set({ vendor })} what="fabricante" />
      </Group>
    </form>
  );
}

function Group({ title, active, open, children }: { title: string; active: boolean; open?: boolean; children: React.ReactNode }) {
  return (
    <details className="group" open={open || active}>
      <summary>
        {title}
        {active && <span className="dot-on" aria-label="filtro ativo" />}
      </summary>
      <div className="group-body">{children}</div>
    </details>
  );
}

function Picker({ items, selected, onChange, what }: { items: Facet[]; selected: string[]; onChange: (v: string[]) => void; what: string }) {
  const [term, setTerm] = useState('');
  const shown = useMemo(() => {
    const t = term.trim().toLowerCase();
    const byName = new Map(items.map((i) => [i.name, i]));
    const chosen = selected.map((s) => byName.get(s) ?? { name: s, count: 0 });
    const rest = items.filter((i) => !selected.includes(i.name) && (!t || i.name.toLowerCase().includes(t)));
    return [...chosen, ...rest].slice(0, 80);
  }, [items, selected, term]);

  if (items.length === 0 && selected.length === 0) return <p className="muted small">Nenhum(a) {what} na base ainda.</p>;
  return (
    <div className="picker">
      <input type="search" className="picker-search" placeholder={`Buscar ${what}`} aria-label={`Buscar ${what}`} value={term} onChange={(e) => setTerm(e.target.value)} />
      <ul className="picker-list">
        {shown.map((i) => (
          <li key={i.name}>
            <label className="check">
              <input type="checkbox" checked={selected.includes(i.name)} onChange={() => onChange(toggle(selected, i.name))} />
              <span>{i.name}</span>
              <small className="num">{fmtInt(i.count)}</small>
            </label>
          </li>
        ))}
        {shown.length === 0 && <li className="muted small">Nada encontrado.</li>}
      </ul>
    </div>
  );
}
