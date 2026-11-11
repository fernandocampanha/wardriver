import { useEffect, useRef, useState } from 'react';
import * as api from '../api';
import { toParams, type Filters } from '../filters';
import { MapController, type Look } from '../mapController';
import type { Network } from '../types';
import { fmtInt } from '../util';
import { LocateIcon } from './Icons';

interface Props {
  filters: Filters;

  initialView: string | null;
  selected: string | null;
  look: Look;
  onLook: (l: Look) => void;
  onView: (v: string) => void;
  onSelect: (n: Network | null) => void;
  onTermLost: () => void;
}

export default function MapView({ filters, initialView, selected, look, onLook, onView, onSelect, onTermLost }: Props) {
  const box = useRef<HTMLDivElement>(null);
  const ctl = useRef<MapController | null>(null);
  const [note, setNote] = useState('');
  const [summary, setSummary] = useState('Carregando…');
  const [ready, setReady] = useState(false);

  const live = useRef({ filters, look, selected, onView, onSelect, onTermLost });
  live.current = { filters, look, selected, onView, onSelect, onTermLost };
  const query = useRef({ bbox: '', zoom: 4 });
  const refit = useRef(!initialView);
  const pendingSel = useRef<string | null>(selected);
  const abort = useRef<AbortController | null>(null);

  async function load() {
    const { filters: f, look: lk } = live.current;
    abort.current?.abort();
    const ac = new AbortController();
    abort.current = ac;
    try {
      const res = await api.getMap(f, query.current.bbox, query.current.zoom, lk === 'heat' ? 'cells' : 'auto', ac.signal);
      if (ac.signal.aborted || !ctl.current) return;
      ctl.current.draw(res);
      setSummary(
        res.mode === 'points'
          ? `${fmtInt(res.total)} rede(s) com posição` + (res.truncated ? ' · mostrando as primeiras, aproxime o mapa' : '')
          : `${fmtInt(res.total)} rede(s) com posição · agrupadas, aproxime para ver cada uma`,
      );
      if (refit.current) {
        refit.current = false;
        const ok = ctl.current.fit(res.bounds);
        setNote(ok ? '' : 'Nenhuma rede com posição para este filtro.');
        if (ok) return;
      }
      if (res.mode === 'points' && pendingSel.current) {
        const hit = res.points.find((p) => p.bssid === pendingSel.current);
        pendingSel.current = null;
        if (hit) live.current.onSelect(hit);
      }
    } catch (e) {
      if (ac.signal.aborted) return;
      if (e instanceof api.TermError) live.current.onTermLost();
      else setNote((e as Error).message);
    }
  }
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    if (!box.current) return;
    const c = new MapController(box.current, {
      onMove: (bbox, zoom) => {
        query.current = { bbox, zoom };
        live.current.onView(c.getView());
        void loadRef.current();
      },
      onSelect: (n) => live.current.onSelect(n),
      onNote: setNote,
    });
    ctl.current = c;
    if (initialView) c.setView(initialView);
    setReady(true);
    c.kick();
    return () => {
      abort.current?.abort();
      c.destroy();
      ctl.current = null;
      setReady(false);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const key = toParams(filters).toString();
  const first = useRef(true);
  useEffect(() => {
    if (!ready) return;
    if (first.current) {
      first.current = false;
      return;
    }
    refit.current = true;
    void loadRef.current();
  }, [key, ready]);

  useEffect(() => {
    if (!ready) return;
    ctl.current?.setLook(look);
    void loadRef.current();
  }, [look, ready]);

  useEffect(() => ctl.current?.setSelected(selected), [selected, ready]);

  useEffect(() => {
    const ro = new ResizeObserver(() => ctl.current?.invalidate());
    if (box.current) ro.observe(box.current);
    return () => ro.disconnect();
  }, []);

  return (
    <div className="map-wrap">
      <div className="map-bar">
        <p className="map-count" role="status">{summary}</p>
        <div className="seg" role="group" aria-label="Aparência do mapa">
          <button type="button" aria-pressed={look === 'points'} onClick={() => onLook('points')}>Pontos</button>
          <button type="button" aria-pressed={look === 'heat'} onClick={() => onLook('heat')}>Calor</button>
        </div>
        <button type="button" className="btn btn-small" onClick={() => { refit.current = true; void load(); }}>
          Enquadrar
        </button>
      </div>
      <div className="map" ref={box} role="application" aria-label="Mapa das redes" />
      <ul className="legend" aria-label="Legenda">
        <li><i className="sw enc-open" />Aberta</li>
        <li><i className="sw enc-wep" />WEP</li>
        <li><i className="sw enc-wpa" />WPA</li>
        <li><i className="sw enc-wpa2" />WPA2</li>
        <li><i className="sw enc-wpa3" />WPA3</li>
        <li><i className="sw enc-unknown" />Desconhecida</li>
        <li><i className="sw hollow" />Contorno vazado: SSID oculto</li>
      </ul>
      <button className="fab" type="button" aria-label="Minha posição" onClick={() => ctl.current?.locateMe()}>
        <LocateIcon />
      </button>
      {note && <p className="map-note" role="status">{note}</p>}
    </div>
  );
}
