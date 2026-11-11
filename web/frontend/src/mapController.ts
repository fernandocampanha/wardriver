import L from 'leaflet';
import type { Bounds, Cell, MapResponse, Network } from './types';
import { hasPos } from './util';

const BRAZIL_CENTER: L.LatLngTuple = [-14.2, -51.9];
const cssVar = (name: string): string => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const ENC_VAR: Record<string, string> = {
  OPEN: '--enc-open', WEP: '--enc-wep', WPA: '--enc-wpa', WPA2: '--enc-wpa2', WPA3: '--enc-wpa3', UNKNOWN: '--enc-unknown',
};

export interface MapHandlers {
  onMove: (bbox: string, zoom: number) => void;
  onSelect: (n: Network | null) => void;
  onNote: (text: string) => void;
}

export type Look = 'points' | 'heat';

export function boundsToQuery(b: L.LatLngBounds): string {
  const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
  const p = b.pad(0.1);
  return [clamp(p.getSouth(), -90, 90), clamp(p.getWest(), -180, 180), clamp(p.getNorth(), -90, 90), clamp(p.getEast(), -180, 180)]
    .map((v) => v.toFixed(5))
    .join(',');
}

export class MapController {
  private map: L.Map;
  private renderer = L.canvas({ padding: 0.5, tolerance: 8 });
  private layer = L.layerGroup();
  private ring: L.CircleMarker | null = null;
  private hoverTip = L.tooltip({ direction: 'top', offset: [0, -8] });
  private userMarker: L.Marker | null = null;
  private drawn: { net: Network; marker: L.CircleMarker }[] = [];
  private last: MapResponse | null = null;
  private look: Look = 'points';
  private selected: string | null = null;
  private moveTimer = 0;

  constructor(container: HTMLElement, private h: MapHandlers) {
    this.map = L.map(container, { zoomControl: true, worldCopyJump: true, maxZoom: 19 }).setView(BRAZIL_CENTER, 4);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a>',
    }).addTo(this.map);
    this.layer.addTo(this.map);
    this.map.on('moveend', () => {
      window.clearTimeout(this.moveTimer);
      this.moveTimer = window.setTimeout(() => this.h.onMove(boundsToQuery(this.map.getBounds()), this.map.getZoom()), 250);
    });
    this.map.on('click', () => this.h.onSelect(null));
  }

  destroy(): void {
    window.clearTimeout(this.moveTimer);
    this.map.remove();
  }

  invalidate(): void {
    this.map.invalidateSize();
  }

  kick(): void {
    this.h.onMove(boundsToQuery(this.map.getBounds()), this.map.getZoom());
  }

  getView(): string {
    const c = this.map.getCenter();
    return `${c.lat.toFixed(5)},${c.lng.toFixed(5)},${this.map.getZoom()}`;
  }

  setView(v: string): boolean {
    const [lat, lon, z] = v.split(',').map(Number);
    if (![lat, lon, z].every(Number.isFinite) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return false;
    this.map.setView([lat, lon], Math.min(19, Math.max(1, Math.round(z))), { animate: false });
    return true;
  }

  fit(b: Bounds | null): boolean {
    if (!b) {
      this.map.setView(BRAZIL_CENTER, 4);
      return false;
    }
    const [s, w, n, e] = b;
    if (s === n && w === e) this.map.setView([s, w], 17);
    else this.map.fitBounds([[s, w], [n, e]], { padding: [48, 48], maxZoom: 17 });
    return true;
  }

  setLook(look: Look): void {
    this.look = look;
    if (this.last) this.draw(this.last);
  }

  setSelected(bssid: string | null): void {
    this.selected = bssid;
    this.updateRing();
  }

  private tierColor(n: Network): string {
    return cssVar(ENC_VAR[n.enc] ?? '--enc-unknown');
  }

  private updateRing(): void {
    this.ring?.remove();
    this.ring = null;
    const hit = this.selected ? this.drawn.find((d) => d.net.bssid === this.selected) : undefined;
    if (!hit) return;
    this.ring = L.circleMarker(hit.marker.getLatLng(), { radius: 13, color: cssVar('--signal'), weight: 3, fill: false, interactive: false }).addTo(this.map);
  }

  draw(res: MapResponse): void {
    this.last = res;
    this.layer.clearLayers();
    this.drawn = [];
    this.map.removeLayer(this.hoverTip);
    if (res.mode === 'points') this.drawPoints(res.points);
    else this.drawCells(res.cells);
    this.updateRing();
  }

  private drawPoints(points: Network[]): void {
    const surface = cssVar('--panel');
    if (this.look === 'heat') {
      for (const n of points) {
        if (hasPos(n)) L.circleMarker([n.lat, n.lon], { renderer: this.renderer, radius: 16, stroke: false, fillColor: cssVar('--signal'), fillOpacity: 0.12, interactive: false }).addTo(this.layer);
      }
      return;
    }
    for (const n of points) {
      if (!hasPos(n)) continue;
      const color = this.tierColor(n);
      const hollow = n.hidden;
      const marker = L.circleMarker([n.lat, n.lon], {
        renderer: this.renderer, radius: 6, color: hollow ? color : surface, fillColor: hollow ? surface : color, fillOpacity: 1, weight: hollow ? 3 : 2,
      });
      marker.on('click', (e) => {
        L.DomEvent.stopPropagation(e);
        this.map.removeLayer(this.hoverTip);
        this.h.onSelect(n);
      });
      marker.on('mouseover', (e) => {
        this.hoverTip.setLatLng(e.latlng).setContent(textNode(n.hidden ? '(SSID oculto)' : n.ssid)).addTo(this.map);
      });
      marker.on('mouseout', () => this.map.removeLayer(this.hoverTip));
      marker.addTo(this.layer);
      this.drawn.push({ net: n, marker });
    }
  }

  private drawCells(cells: Cell[]): void {
    const heat = this.look === 'heat';
    for (const c of cells) {
      const share = c.weak / c.n;
      const color = heat ? cssVar('--signal') : cssVar(share > 0.5 ? '--enc-open' : share > 0.15 ? '--enc-wpa' : '--enc-wpa2');
      const radius = heat ? 10 + 7 * Math.log2(1 + c.n) : 9 + 4 * Math.log2(1 + c.n);
      const circle = L.circleMarker([c.lat, c.lon], {
        renderer: this.renderer, radius: Math.min(heat ? 60 : 30, radius), stroke: !heat, color: cssVar('--panel'), weight: 2,
        fillColor: color, fillOpacity: heat ? Math.min(0.5, 0.14 + c.n / 400) : 0.78, interactive: !heat,
      });
      if (!heat) {
        circle.on('click', (e) => {
          L.DomEvent.stopPropagation(e);
          this.map.setView([c.lat, c.lon], Math.min(19, this.map.getZoom() + 2));
        });
        circle.bindTooltip(textNode(`${c.n} redes${c.weak ? ` · ${c.weak} sem criptografia forte` : ''}`), { direction: 'top' });
      }
      circle.addTo(this.layer);
    }
  }

  refreshColors(): void {
    if (this.last) this.draw(this.last);
  }

  focus(n: Network): void {
    if (hasPos(n)) this.map.setView([n.lat, n.lon], Math.max(this.map.getZoom(), 18));
  }

  locateMe(): void {
    if (!navigator.geolocation) {
      this.h.onNote('Este navegador não oferece localização.');
      return;
    }
    this.h.onNote('Aguardando sinal de GPS…');
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        const at: L.LatLngTuple = [pos.coords.latitude, pos.coords.longitude];
        this.userMarker?.remove();
        this.userMarker = L.marker(at, {
          icon: L.divIcon({ className: '', html: '<div class="user-dot"></div>', iconSize: [16, 16], iconAnchor: [8, 8] }),
          interactive: false, keyboard: false,
        }).addTo(this.map);
        this.map.setView(at, Math.max(this.map.getZoom(), 16));
        this.h.onNote('');
      },
      () => this.h.onNote('Sem permissão de localização: não dá para mostrar sua posição.'),
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 30000 },
    );
  }
}

function textNode(text: string): HTMLElement {
  const span = document.createElement('span');
  span.textContent = text;
  return span;
}
