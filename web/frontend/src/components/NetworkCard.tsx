import { useState } from 'react';
import { hrefOf } from '../router';
import type { Network } from '../types';
import { encLabel, fmtDate, fmtPos, hasPos } from '../util';

const ENC_KEY: Record<string, string> = { OPEN: 'open', WEP: 'wep', WPA: 'wpa', WPA2: 'wpa2', WPA3: 'wpa3', UNKNOWN: 'unknown' };


export default function NetworkCard({ net, onClose, onShowOnMap, mapLink = true }: { net: Network; onClose?: () => void; onShowOnMap?: () => void; mapLink?: boolean }) {
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(net.bssid);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {

    }
  }
  const rows: [string, string][] = [
    ['BSSID', net.bssid],
    ['Fabricante', net.vendor || 'desconhecido'],
    ['Cidade', net.city || '—'],
    ['Canal', net.channel > 0 ? String(net.channel) : '—'],
    ['Sinal', `${net.rssi} dBm`],
    ['WPS', net.wps ? 'ativo' : 'inativo'],
    ['Vista', `${net.seen}×`],
    ['Última captura', fmtDate(net.last)],
    ['Posição', fmtPos(net)],
  ];
  return (
    <section className="card" aria-label="Detalhes da rede">
      <div className="card-head">
        <div>
          <h2>{net.hidden ? '(SSID oculto)' : net.ssid}</h2>
          <p className={`enc-tag enc-${ENC_KEY[net.enc] ?? 'unknown'}`}>{encLabel(net.enc)}</p>
        </div>
        {onClose && (
          <button type="button" className="icon-btn" onClick={onClose} aria-label="Fechar detalhes">
            ×
          </button>
        )}
      </div>
      <dl className="kv">
        {rows.map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd className={k === 'BSSID' || k === 'Posição' ? 'mono' : undefined}>{v}</dd>
          </div>
        ))}
      </dl>
      <div className="row">
        <button type="button" className="btn" onClick={copy}>
          {copied ? 'Copiado' : 'Copiar BSSID'}
        </button>
        {hasPos(net) && onShowOnMap && (
          <button type="button" className="btn" onClick={onShowOnMap}>
            Ver no mapa
          </button>
        )}
        {hasPos(net) && mapLink && !onShowOnMap && (
          <a className="btn" href={hrefOf('/', new URLSearchParams({ v: `${net.lat},${net.lon},18`, sel: net.bssid }))}>
            Ver no mapa
          </a>
        )}
      </div>
    </section>
  );
}
