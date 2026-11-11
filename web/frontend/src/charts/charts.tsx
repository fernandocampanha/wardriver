import { useState, type ReactNode } from 'react';
import { fmtInt, niceMax, pct } from '../util';

export interface BarItem {
  label: string;
  value: number;
}
export interface ColumnItem extends BarItem {
  short: string;
}

const tipProps = (value: string, label: string) => ({
  'data-tip': '',
  tabIndex: 0,
  'data-tip-value': value,
  'data-tip-label': label,
});

const summary = (items: BarItem[]) => items.map((i) => `${i.label}: ${i.value}`).join('; ');

export function HBars({ items, total, unit }: { items: BarItem[]; total: number; unit: string }) {
  const max = Math.max(1, ...items.map((i) => i.value));
  return (
    <div className="hbars" role="img" aria-label={summary(items)}>
      {items.map((item) => (
        <div
          key={item.label}
          className="hbar"
          {...tipProps(`${fmtInt(item.value)} ${unit}`, `${item.label} · ${pct(item.value, total)}% do total`)}
        >
          <span className="hbar-label">{item.label}</span>
          <div className="hbar-track">
            <div className="bar-fill" style={{ width: `${(item.value / max) * 78}%` }} />
            <span className="bar-value">{fmtInt(item.value)}</span>
          </div>
        </div>
      ))}
    </div>
  );
}

export function Columns({ items, total, unit }: { items: ColumnItem[]; total: number; unit: string }) {
  const top = Math.max(1, ...items.map((i) => i.value));
  const scale = niceMax(top);
  return (
    <div className="columns" role="img" aria-label={summary(items)}>
      {[0.5, 1].map((f) => (
        <div key={f} className="gridline" style={{ bottom: `calc(22px + (100% - 22px) * ${f})` }}>
          <span>{fmtInt(Math.round(scale * f))}</span>
        </div>
      ))}
      <div className="axis-base" />
      {items.map((item) => (
        <div
          key={item.short}
          className="col"
          {...tipProps(`${fmtInt(item.value)} ${unit}`, `${item.label} · ${pct(item.value, total)}% do total`)}
        >
          <div className="col-fill" style={{ height: `${(item.value / scale) * 100}%` }} />
          <span className="col-label">{item.short}</span>
          {item.value === top && top > 0 && (
            <span className="col-cap" style={{ position: 'absolute', bottom: `calc(${(item.value / scale) * 100}% + 2px)` }}>
              {fmtInt(item.value)}
            </span>
          )}
        </div>
      ))}
    </div>
  );
}

export function Tile({ label, value, sub }: { label: string; value: string; sub?: string | null }) {
  return (
    <div className="tile">
      <div className="tile-label">{label}</div>
      <div className="tile-value">{value}</div>
      {sub && <div className="tile-sub">{sub}</div>}
    </div>
  );
}

export function ChartCard(props: {
  title: string;
  subtitle?: string;
  chart: ReactNode;
  tableHead: string[];
  tableRows: string[][];
  note?: string | null;
}) {
  const [asTable, setAsTable] = useState(false);
  return (
    <section className="chart-card">
      <div className="chart-head">
        <div>
          <h2>{props.title}</h2>
          {props.subtitle && <p>{props.subtitle}</p>}
        </div>
        <button type="button" className="link-btn" aria-pressed={asTable} onClick={() => setAsTable((v) => !v)}>
          {asTable ? 'Ver gráfico' : 'Ver tabela'}
        </button>
      </div>
      {asTable ? (
        <table className="data">
          <thead>
            <tr>
              {props.tableHead.map((c, i) => (
                <th key={c} className={i > 0 ? 'num' : ''}>
                  {c}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {props.tableRows.map((row) => (
              <tr key={row[0]}>
                {row.map((c, i) => (
                  <td key={i} className={i > 0 ? 'num' : ''}>
                    {c}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        props.chart
      )}
      {props.note && <p className="note">{props.note}</p>}
    </section>
  );
}
