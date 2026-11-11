import type { Filters } from '../filters';
import type { Stats } from '../types';
import { fmtDay, fmtInt, pct } from '../util';

interface Item {
  key: string;
  label: string;
  value: number | null;
  sub: string;

  apply?: Partial<Filters>;

  clear?: Partial<Filters>;
  on: boolean;
}

export default function BigNumbers({ stats, filters, onChange }: { stats: Stats | null; filters: Filters; onChange: (f: Filters) => void }) {
  const t = stats?.total ?? 0;
  const wpa3 = stats?.encryption.find((e) => e.name === 'WPA3')?.count ?? 0;
  const items: Item[] = [
    { key: 'total', label: 'Redes únicas', value: stats ? t : null, sub: stats?.last_seen_ms ? `de ${fmtDay(stats.first_seen_ms)} a ${fmtDay(stats.last_seen_ms)}` : '—', on: false },
    { key: 'pos', label: 'Com posição', value: stats?.with_position ?? null, sub: `${pct(stats?.with_position ?? 0, t)}% do total`, apply: { hasPos: true }, clear: { hasPos: false }, on: filters.hasPos },
    { key: 'weak', label: 'Aberta ou WEP', value: stats?.weak ?? null, sub: `sem criptografia forte · ${pct(stats?.weak ?? 0, t)}%`, apply: { enc: ['OPEN', 'WEP'] }, clear: { enc: [] }, on: filters.enc.join() === 'OPEN,WEP' },
    { key: 'wpa3', label: 'WPA3', value: stats ? wpa3 : null, sub: `${pct(wpa3, t)}% do total`, apply: { enc: ['WPA3'] }, clear: { enc: [] }, on: filters.enc.join() === 'WPA3' },
    { key: 'hidden', label: 'SSID oculto', value: stats?.hidden ?? null, sub: `${pct(stats?.hidden ?? 0, t)}% do total`, apply: { hidden: 'only' }, clear: { hidden: '' }, on: filters.hidden === 'only' },
    { key: 'wps', label: 'WPS ativo', value: stats?.wps ?? null, sub: `${pct(stats?.wps ?? 0, t)}% do total`, apply: { wps: 'yes' }, clear: { wps: '' }, on: filters.wps === 'yes' },
    { key: 'default', label: 'Nome de fábrica', value: stats?.default_names ?? null, sub: `${pct(stats?.default_names ?? 0, t)}% do total`, apply: { defaultName: true }, clear: { defaultName: false }, on: filters.defaultName },
  ];

  return (
    <section className="ruler" aria-label="Números principais">
      {items.map((i) => {
        const body = (
          <>
            <span className="reading-label">{i.label}</span>
            <span className="reading-value">{i.value === null ? '—' : fmtInt(i.value)}</span>
            <span className="reading-sub">{i.sub}</span>
          </>
        );
        return i.apply ? (
          <button
            key={i.key}
            type="button"
            className="reading"
            aria-pressed={i.on}
            title={i.on ? 'Remover este filtro' : 'Filtrar o mapa por este número'}
            onClick={() => onChange({ ...filters, ...(i.on ? i.clear : i.apply) })}
          >
            {body}
          </button>
        ) : (
          <div key={i.key} className="reading">
            {body}
          </div>
        );
      })}
    </section>
  );
}
