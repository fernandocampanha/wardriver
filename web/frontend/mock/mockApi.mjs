

const TERM_VERSION = '2026-10-10';
const COOKIE = 'wd_term';
const MOCK_PASSWORD = 'dev';
const sessions = new Set();

let seed = 7;
const rnd = () => ((seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296);
const pick = (a) => a[Math.floor(rnd() * a.length)];
const gauss = () => (rnd() + rnd() + rnd() + rnd() - 2) / 2;
const hex = (n) => Math.floor(rnd() * n).toString(16).toUpperCase().padStart(2, '0');

const CITIES = [
  ['Catanduva - SP', -21.1378, -48.9728, 0.03, 2600], ['São Paulo - SP', -23.5505, -46.6333, 0.07, 2400],
  ['Campinas - SP', -22.9099, -47.0626, 0.04, 700], ['Santos - SP', -23.9608, -46.3336, 0.03, 400],
  ['Rio de Janeiro - RJ', -22.9068, -43.1729, 0.05, 600],
];
const VENDORS = ['TP-Link Technologies Co.,Ltd.', 'Intelbras', 'Huawei Technologies Co.,Ltd', 'ZTE Corporation', 'Arris Group, Inc.', 'Tenda Technology Co.,Ltd.', 'D-Link International', 'Multilaser Industrial', 'Apple, Inc.', 'Ubiquiti Inc'];
const NAMES = ['VIVO-%X', 'CLARO_%X', 'Casa %d', 'Apto %d', 'TP-Link_%X', 'Loja %d', 'NET_%X', 'Escritorio %d', '#CLARO-WIFI', 'Wi-Fi a bordo', 'CAMPUS_2.4G'];
const DEFAULT_RE = /^(TP-LINK|VIVO|CLARO|OI-|OI_|SKY|NET_|NET-|TENDA|D-LINK|DLINK|HUAWEI|MULTILASER|INTELBRAS|ARRIS|WIFI[0-9]|GVT-|ZTE)[ _-]?[0-9A-F]*$/i;

const NETS = [];
for (const [city, lat0, lon0, spread, n] of CITIES) {
  for (let i = 0; i < n; i++) {
    const hidden = rnd() < 0.35;
    const tpl = pick(NAMES);
    const ssid = hidden ? '' : tpl.includes('%X') ? tpl.replace('%X', Math.floor(rnd() * 65535).toString(16).toUpperCase()) : tpl.includes('%d') ? tpl.replace('%d', String(1 + Math.floor(rnd() * 300))) : tpl;
    const mac = [hex(256), hex(256), hex(256), hex(256), hex(256), hex(256)];
    if (hidden && rnd() < 0.4) mac[0] = (parseInt(mac[0], 16) | 2).toString(16).toUpperCase().padStart(2, '0');
    const enc = rnd() < 0.13 ? 'OPEN' : rnd() < 0.01 ? 'WEP' : rnd() < 0.01 ? 'WPA' : rnd() < 0.04 ? 'WPA3' : 'WPA2';
    NETS.push({
      bssid: mac.join(':'), ssid, hidden, channel: pick([1, 1, 6, 6, 6, 11, 11, 4, 8, 3, 9]), rssi: -35 - Math.floor(rnd() * 58), enc,
      seen: 1 + Math.floor(rnd() * 30), wps: rnd() < 0.5, lat: lat0 + gauss() * spread, lon: lon0 + gauss() * spread * 1.2,
      last: 1760000000000 + Math.floor(rnd() * 60) * 86400000, city, vendor: rnd() < 0.85 ? pick(VENDORS) : '',
    });
  }
}
for (let i = 0; i < 300; i++) NETS.push({ ...NETS[i], bssid: `AA:00:${hex(256)}:${hex(256)}:${hex(256)}:${hex(256)}`, lat: null, lon: null, city: '' });
for (const n of NETS) {
  n.defaultName = !!n.ssid && DEFAULT_RE.test(n.ssid);
  n.localAdmin = !!(parseInt(n.bssid.slice(0, 2), 16) & 2);
}

const list = (v, sep) => (v ? String(v).split(sep).filter(Boolean) : []);
function matches(n, q) {
  const num = (k) => (q.get(k) === null || q.get(k) === '' ? null : Number(q.get(k)));
  for (const t of (q.get('q') || '').toLowerCase().split(/\s+/).filter(Boolean)) {
    const b = n.bssid.toLowerCase();
    if (!(n.ssid.toLowerCase().includes(t) || b.includes(t) || b.replaceAll(':', '').includes(t) || (n.hidden && ['oculta', 'oculto', 'hidden'].includes(t)))) return false;
  }
  const enc = list(q.get('enc'), ','); if (enc.length && !enc.includes(n.enc)) return false;
  const ch = list(q.get('channel'), ',').map(Number); if (ch.length && !ch.includes(n.channel)) return false;
  if (num('rssi_min') !== null && n.rssi < num('rssi_min')) return false;
  if (num('rssi_max') !== null && n.rssi > num('rssi_max')) return false;
  if (q.get('wps') === 'true' && !n.wps) return false;
  if (q.get('wps') === 'false' && n.wps) return false;
  if (q.get('hidden') === 'only' && !n.hidden) return false;
  if (q.get('hidden') === 'no' && n.hidden) return false;
  if (q.get('default_name') === 'true' && !n.defaultName) return false;
  if (q.get('local_admin') === 'true' && !(n.localAdmin && n.hidden)) return false;
  if (q.get('has_pos') === 'true' && n.lat === null) return false;
  if (num('seen_min') !== null && n.seen < num('seen_min')) return false;
  if (num('since') !== null && n.last < num('since')) return false;
  if (num('until') !== null && n.last > num('until')) return false;
  const city = list(q.get('city'), '|'); if (city.length && !city.includes(n.city)) return false;
  const vendor = list(q.get('vendor'), '|'); if (vendor.length && !vendor.includes(n.vendor)) return false;
  const bbox = q.get('bbox');
  if (bbox) {
    const [s, w, no, e] = bbox.split(',').map(Number);
    if (n.lat === null || n.lat < s || n.lat > no || n.lon < w || n.lon > e) return false;
  }
  return true;
}

const facet = (nets, key, limit) => {
  const m = new Map();
  for (const n of nets) if (n[key]) m.set(n[key], (m.get(n[key]) || 0) + 1);
  return [...m].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count || a.name.localeCompare(b.name)).slice(0, limit);
};

function stats(nets) {
  const by = (f) => { const m = new Map(); for (const n of nets) m.set(f(n), (m.get(f(n)) || 0) + 1); return m; };
  const enc = by((n) => n.enc), chan = by((n) => n.channel), ssids = by((n) => n.ssid);
  ssids.delete('');
  const seen = nets.map((n) => n.last);
  const bands = [['Forte (acima de -50 dBm)', -50, 1], ['Bom (-50 a -60)', -60, -50], ['Médio (-60 a -70)', -70, -60], ['Fraco (-70 a -80)', -80, -70], ['Muito fraco (abaixo de -80)', -128, -80]];
  const hid = nets.filter((n) => n.hidden);
  return {
    total: nets.length, with_position: nets.filter((n) => n.lat !== null).length, hidden: hid.length, wps: nets.filter((n) => n.wps).length,
    weak: (enc.get('OPEN') || 0) + (enc.get('WEP') || 0), default_names: nets.filter((n) => n.defaultName).length,
    hidden_origin: { unnamed: hid.length, with_visible_sibling: Math.floor(hid.length * 0.13), locally_administered: hid.filter((n) => n.localAdmin).length },
    encryption: ['OPEN', 'WEP', 'WPA', 'WPA2', 'WPA3', 'UNKNOWN'].filter((k) => enc.get(k)).map((name) => ({ name, count: enc.get(name) })),
    channels: Array.from({ length: 14 }, (_, i) => ({ channel: i + 1, count: chan.get(i + 1) || 0 })), channels_other: 0,
    signal: bands.map(([name, lo, hi]) => ({ name, count: nets.filter((n) => n.rssi >= lo && n.rssi < hi).length })),
    top_ssids: [...ssids].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).slice(0, 10).map(([ssid, count]) => ({ ssid, count })),
    cities: facet(nets, 'city', 10), vendors: facet(nets, 'vendor', 10),
    first_seen_ms: seen.length ? Math.min(...seen) : null, last_seen_ms: seen.length ? Math.max(...seen) : null,
    last_import: { id: 1, at_ms: Date.now() - 3600000, source: 'app', added: 120, updated: 45, names_filled: 8 },
  };
}

const row = (n) => ({ bssid: n.bssid, ssid: n.ssid, hidden: n.hidden, channel: n.channel, rssi: n.rssi, enc: n.enc, seen: n.seen, wps: n.wps, lat: n.lat, lon: n.lon, last: n.last, city: n.city, vendor: n.vendor });
const SORT = { rssi: (n) => n.rssi, ssid: (n) => n.ssid.toLowerCase(), last: (n) => n.last, seen: (n) => n.seen, channel: (n) => n.channel, bssid: (n) => n.bssid, city: (n) => n.city, vendor: (n) => n.vendor };

function handle(url, method, accepted) {
  const q = url.searchParams, p = url.pathname;
  if (p === '/api/health') return [200, { ok: true }];
  if (p === '/api/me') return [200, { accepted, term_version: TERM_VERSION }];
  if (!accepted) return [403, { error: 'termo de compromisso nao aceito' }];
  const sel = NETS.filter((n) => matches(n, q));
  if (p === '/api/networks') {
    const key = SORT[q.get('sort')] ? q.get('sort') : 'rssi', dir = q.get('order') === 'asc' ? 1 : -1;
    const f = SORT[key];
    sel.sort((a, b) => (f(a) < f(b) ? -1 : f(a) > f(b) ? 1 : 0) * dir || a.bssid.localeCompare(b.bssid));
    const limit = Math.min(200, Number(q.get('limit')) || 50), offset = Number(q.get('offset')) || 0;
    return [200, { total: sel.length, limit, offset, networks: sel.slice(offset, offset + limit).map(row) }];
  }
  if (p === '/api/map') {
    const pos = sel.filter((n) => n.lat !== null), mode = q.get('mode') || 'auto', zoom = Number(q.get('zoom')) || 10;
    let bounds = null;
    if (pos.length) bounds = [Math.min(...pos.map((n) => n.lat)), Math.min(...pos.map((n) => n.lon)), Math.max(...pos.map((n) => n.lat)), Math.max(...pos.map((n) => n.lon))];
    if (mode === 'points' || (mode === 'auto' && (pos.length <= 3000 || zoom >= 16))) {
      return [200, { mode: 'points', total: pos.length, truncated: pos.length > 8000, bounds, points: pos.slice(0, 8000).map(row) }];
    }
    const cell = (44 * 360) / (256 * 2 ** zoom), g = new Map();
    for (const n of pos) {
      const k = `${Math.floor(n.lon / cell)}|${Math.floor(n.lat / cell)}`, c = g.get(k) || { lat: 0, lon: 0, n: 0, weak: 0 };
      c.lat += n.lat; c.lon += n.lon; c.n++; if (n.enc === 'OPEN' || n.enc === 'WEP') c.weak++; g.set(k, c);
    }
    return [200, { mode: 'cells', total: pos.length, bounds, cells: [...g.values()].map((c) => ({ lat: c.lat / c.n, lon: c.lon / c.n, n: c.n, weak: c.weak })) }];
  }
  if (p === '/api/stats') return [200, stats(sel)];
  if (p === '/api/facets') return [200, { cities: facet(NETS, 'city', 300), vendors: facet(NETS, 'vendor', 300), term_version: TERM_VERSION }];
  return [404, { error: 'nao encontrado' }];
}


export function mockApi() {
  return {
    name: 'wardriver-mock-api',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (!req.url || !req.url.startsWith('/api/')) return next();
        const url = new URL(req.url, 'http://localhost');
        const token = (req.headers.cookie || '').split(/;\s*/).find((c) => c.startsWith(`${COOKIE}=`))?.slice(COOKIE.length + 1);
        const accepted = !!token && sessions.has(token);
        const send = (status, body, headers = {}) => {
          res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...headers });
          res.end(JSON.stringify(body));
        };
        if (url.pathname === '/api/term/accept' && req.method === 'POST') {
          let raw = '';
          req.on('data', (d) => (raw += d));
          req.on('end', () => {
            let version = '', password = '';
            try { ({ version, password } = JSON.parse(raw)); } catch {  }
            if (password !== MOCK_PASSWORD) return send(401, { error: 'senha incorreta' });
            if (version !== TERM_VERSION) return send(409, { error: 'o termo foi atualizado; recarregue a pagina' });
            const t = Math.random().toString(36).slice(2);
            sessions.add(t);
            send(200, { accepted: true, term_version: TERM_VERSION }, { 'Set-Cookie': `${COOKIE}=${t}; Path=/; HttpOnly; SameSite=Strict` });
          });
          return;
        }
        if (url.pathname === '/api/term/revoke' && req.method === 'POST') {
          if (token) sessions.delete(token);
          return send(200, { accepted: false }, { 'Set-Cookie': `${COOKIE}=; Path=/; Max-Age=0` });
        }
        const [status, body] = handle(url, req.method, accepted);
        send(status, body);
      });
    },
  };
}
