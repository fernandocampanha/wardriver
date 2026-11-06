import { useState } from 'react';
import { ChartCard, Columns, HBars } from '../charts/charts';
import FilterPanel from '../components/FilterPanel';
import { activeCount } from '../filters';
import type { Stats } from '../types';
import { encLabel, fmtDate, fmtInt, pct } from '../util';
import { useStats, type PageProps } from './Dashboard';

export default function Analysis({ filters, setFilters, facets, onTermLost }: PageProps) {
  const { stats, error } = useStats(filters, onTermLost);
  const [panelOpen, setPanelOpen] = useState(false);
  const n = activeCount(filters);
  return (
    <div className="dash-body">
      <button type="button" className="btn filters-toggle" aria-expanded={panelOpen} aria-controls="painel-filtros" onClick={() => setPanelOpen((v) => !v)}>
        {panelOpen ? 'Fechar filtros' : `Filtros${n ? ` (${n})` : ''}`}
      </button>
      <div id="painel-filtros" className={`side${panelOpen ? ' is-open' : ''}`}>
        <FilterPanel filters={filters} onChange={setFilters} cities={facets?.cities ?? []} vendors={facets?.vendors ?? []}
          count={stats ? `${fmtInt(stats.total)} rede(s) na análise` : 'Carregando…'} />
      </div>
      <section className="stage report" aria-label="Análise">
        {error && <p className="error" role="alert">{error}</p>}
        {stats && <Report stats={stats} />}
      </section>
    </div>
  );
}

function Report({ stats }: { stats: Stats }) {
  if (stats.total === 0) return <p className="empty">Nenhuma rede com estes filtros.</p>;
  const t = stats.total;
  const hid = stats.hidden_origin;
  const encItems = stats.encryption.map((e) => ({ label: encLabel(e.name), value: e.count })).sort((a, b) => b.value - a.value);
  const chan = stats.channels.filter((c) => c.channel <= 13 || c.count > 0).map((c) => ({ label: `Canal ${c.channel}`, short: String(c.channel), value: c.count }));
  const sig = stats.signal.map((s) => ({ label: s.name, value: s.count }));
  const cities = stats.cities.map((c) => ({ label: c.name, value: c.count }));
  const vendors = stats.vendors.map((c) => ({ label: c.name, value: c.count }));
  const rows = (items: { label: string; value: number }[]) => items.map((i) => [i.label, fmtInt(i.value), `${pct(i.value, t)}%`]);

  return (
    <>
      <div className="grid-2">
        <ChartCard title="Criptografia" subtitle="Redes por tipo de proteção" chart={<HBars items={encItems} total={t} unit="rede(s)" />}
          tableHead={['Tipo', 'Redes', '% do total']} tableRows={rows(encItems)} note="OPEN e WEP não oferecem proteção adequada ao tráfego." />
        <ChartCard title="Canais" subtitle="Redes por canal de 2,4 GHz" chart={<Columns items={chan} total={t} unit="rede(s)" />}
          tableHead={['Canal', 'Redes', '% do total']} tableRows={rows(chan)}
          note={stats.channels_other > 0 ? `${fmtInt(stats.channels_other)} rede(s) sem canal conhecido ou fora de 1–14.` : null} />
      </div>
      <div className="grid-2">
        <ChartCard title="Intensidade do sinal" subtitle="Quão perto o coletor passou das redes" chart={<HBars items={sig} total={t} unit="rede(s)" />}
          tableHead={['Faixa', 'Redes', '% do total']} tableRows={rows(sig)} note="Sinal mais forte (mais perto de 0 dBm) indica captura mais próxima." />
        <ChartCard title="Fabricantes" subtitle="Os 10 mais frequentes (pelo prefixo do BSSID)" chart={vendors.length ? <HBars items={vendors} total={t} unit="rede(s)" /> : <p className="empty">Sem fabricante identificado.</p>}
          tableHead={['Fabricante', 'Redes', '% do total']} tableRows={rows(vendors)} note="Endereços virtuais ou aleatórios podem ficar sem fabricante." />
      </div>
      <div className="grid-2">
        <ChartCard title="Cidades" subtitle="As 10 com mais redes" chart={cities.length ? <HBars items={cities} total={t} unit="rede(s)" /> : <p className="empty">Nenhuma rede com cidade conhecida.</p>}
          tableHead={['Cidade', 'Redes', '% do total']} tableRows={rows(cities)} note="Cidade = centro urbano mais próximo (até 20 km) de uma lista embutida; não é limite municipal." />
        <section className="chart-card">
          <div className="chart-head"><div><h2>De onde vêm as redes ocultas</h2><p>Heurística de mesma origem; não é prova</p></div></div>
          <div className="hidden-stats">
            <div><strong>{fmtInt(hid.unnamed)}</strong> sem nome ({pct(hid.unnamed, t)}% da seleção)</div>
            <div><strong>{fmtInt(hid.with_visible_sibling)}</strong> com rede visível da mesma origem ({pct(hid.with_visible_sibling, hid.unnamed)}% das ocultas)</div>
            <div><strong>{fmtInt(hid.locally_administered)}</strong> com BSSID de interface virtual ({pct(hid.locally_administered, hid.unnamed)}% das ocultas)</div>
          </div>
          <p className="note">Roteadores de operadora e mesh publicam interfaces extras (visitante, operadora, backhaul) sem divulgar o nome. Elas contam como ocultas sem ninguém ter escondido nada.</p>
        </section>
      </div>
      <section className="chart-card">
        <div className="chart-head"><div><h2>Nomes mais frequentes</h2><p>Vários BSSIDs com o mesmo SSID (repetidores, mesh, redes de operadora)</p></div></div>
        {stats.top_ssids.length === 0 ? <p className="empty">Nenhum SSID visível na seleção.</p> : (
          <table className="data">
            <thead><tr><th>#</th><th>Nome (SSID)</th><th className="num">Redes</th></tr></thead>
            <tbody>{stats.top_ssids.map((s, i) => (<tr key={s.ssid}><td>{i + 1}</td><td>{s.ssid}</td><td className="num">{fmtInt(s.count)}</td></tr>))}</tbody>
          </table>
        )}
      </section>
      {stats.last_import && (
        <p className="note">Última atualização da base: {fmtDate(stats.last_import.at_ms)} ({fmtInt(stats.last_import.added)} nova(s), {fmtInt(stats.last_import.updated)} atualizada(s)).</p>
      )}
    </>
  );
}
