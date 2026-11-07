import { useEffect, useState } from 'react';
import * as api from '../api';
import { navigate, type Route } from '../router';
import type { ImportRow, SyncHistory as SyncHistoryData } from '../types';
import { fmtDate, fmtInt } from '../util';

const PAGE_SIZE = 25;
const SOURCE_LABEL: Record<string, string> = { app: 'Aplicativo Android' };
const sourceLabel = (s: string): string => SOURCE_LABEL[s] ?? s;

export default function SyncHistory({ route, onTermLost }: { route: Route; onTermLost: () => void }) {
  const pageIdx = Math.max(0, Math.min(200_000, Number(route.params.get('p')) || 0));
  const [data, setData] = useState<SyncHistoryData | null>(null);
  const [error, setError] = useState('');
  const key = `${pageIdx}`;

  useEffect(() => {
    const ac = new AbortController();
    const t = setTimeout(() => {
      api.getSyncHistory(pageIdx * PAGE_SIZE, PAGE_SIZE, ac.signal).then(
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

  const patch = (change: (p: URLSearchParams) => void) => {
    const p = new URLSearchParams(location.hash.split('?')[1] ?? '');
    change(p);
    navigate('/sincronizacoes', p, true);
  };

  const total = data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="sync-body">
      <section className="stage table-stage" aria-label="Histórico de sincronizações">
        <p className="note">
          Cada linha é um envio de redes recebido pelo servidor (um ciclo de sincronização do aplicativo). Mostra o que
          mudou na base com aquele envio, não o progresso da sincronização em si entre o ESP32 e o celular.
        </p>
        {error && <p className="error" role="alert">{error}</p>}
        <div className="table-scroll">
          <table className="data">
            <thead>
              <tr>
                <th>Quando</th>
                <th>Origem</th>
                <th className="num">Redes enviadas</th>
                <th className="num">Novas</th>
                <th className="num">Atualizadas</th>
                <th className="num">Nomes preenchidos</th>
                <th className="num">Com posição</th>
              </tr>
            </thead>
            <tbody>
              {data?.events.map((e: ImportRow) => (
                <tr key={e.id}>
                  <td data-label="Quando">{fmtDate(e.at_ms)}</td>
                  <td data-label="Origem">
                    {sourceLabel(e.source)}
                    {e.rows_skipped > 0 && <span className="muted small"> · {fmtInt(e.rows_skipped)} linha(s) ignorada(s)</span>}
                  </td>
                  <td className="num" data-label="Redes enviadas">{fmtInt(e.networks)}</td>
                  <td className="num" data-label="Novas">{fmtInt(e.added)}</td>
                  <td className="num" data-label="Atualizadas">{fmtInt(e.updated)}</td>
                  <td className="num" data-label="Nomes preenchidos">{fmtInt(e.names_filled)}</td>
                  <td className="num" data-label="Com posição">{fmtInt(e.with_position)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {data && data.events.length === 0 && <p className="empty">Nenhuma sincronização registrada ainda.</p>}
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
    </div>
  );
}

