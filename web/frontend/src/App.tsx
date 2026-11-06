
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import * as api from './api';
import Tooltip from './charts/Tooltip';
import Footer from './components/Footer';
import Header from './components/Header';
import { fromParams, toParams, type Filters } from './filters';
import About from './pages/About';
import Analysis from './pages/Analysis';
import Dashboard from './pages/Dashboard';
import Networks from './pages/Networks';
import Term from './pages/Term';
import { parseHash, useRoute, VIEW_KEYS, navigate, type Path } from './router';
import type { Facets } from './types';

const OPEN_PATHS: Path[] = ['/sobre', '/termo'];

export default function App() {
  const route = useRoute();
  const [session, setSession] = useState<{ accepted: boolean; termVersion: string } | null>(null);
  const [facets, setFacets] = useState<Facets | null>(null);
  const [fatal, setFatal] = useState('');
  const main = useRef<HTMLElement>(null);

  const filters = useMemo(() => fromParams(route.params), [route.params]);

  useEffect(() => {
    api.me().then(setSession).catch(() => setFatal('Não foi possível falar com o servidor.'));
  }, []);


  const termLost = useCallback(() => {
    setSession((s) => (s ? { ...s, accepted: false } : s));
    setFacets(null);
  }, []);

  useEffect(() => {
    if (!session?.accepted) return;
    api.getFacets().then(setFacets).catch((e) => e instanceof api.TermError && termLost());
  }, [session?.accepted, termLost]);


  useEffect(() => {
    main.current?.focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }, [route.path]);

  useEffect(() => {
    const names: Record<Path, string> = { '/': 'Painel', '/redes': 'Redes', '/analise': 'Análise', '/sobre': 'Sobre o projeto', '/termo': 'Termo de compromisso' };
    document.title = `${names[route.path]} · Wardriver Web`;
  }, [route.path]);

  const setFilters = useCallback((next: Filters) => {
    const p = toParams(next);
    const current = parseHash(location.hash).params;
    for (const k of VIEW_KEYS) if (current.has(k) && k !== 'p') p.set(k, current.get(k) as string);
    navigate(parseHash(location.hash).path, p, true);
  }, []);

  if (fatal) return <p className="fatal">{fatal}</p>;
  if (!session) return null;

  const locked = !session.accepted && !OPEN_PATHS.includes(route.path);
  const page = locked ? '/termo' : route.path;

  return (
    <div id="app">
      <a className="skip" href="#conteudo" onClick={(e) => { e.preventDefault(); main.current?.focus(); }}>
        Ir para o conteúdo
      </a>
      <Header route={route} filters={filters} accepted={session.accepted} />
      <main id="conteudo" ref={main} tabIndex={-1} className={`page page-${page === '/' ? 'dashboard' : page.slice(1)}`}>
        {page === '/termo' && (
          <Term
            accepted={session.accepted}
            version={session.termVersion}
            next={route.params.get('next')}
            onAccepted={() => setSession({ ...session, accepted: true })}
            onRevoked={() => {
              setSession({ ...session, accepted: false });
              setFacets(null);
            }}
          />
        )}
        {(page === '/sobre' || page === '/termo') && <About accepted={session.accepted} />}
        {page === '/' && <Dashboard route={route} filters={filters} setFilters={setFilters} facets={facets} onTermLost={termLost} />}
        {page === '/redes' && <Networks route={route} filters={filters} setFilters={setFilters} facets={facets} onTermLost={termLost} />}
        {page === '/analise' && <Analysis filters={filters} setFilters={setFilters} facets={facets} onTermLost={termLost} />}
      </main>
      {page !== '/' && <Footer />}
      <Tooltip />
    </div>
  );
}
