
import { useCallback, useEffect, useState } from 'react';

export type Path = '/' | '/redes' | '/analise' | '/sincronizacoes' | '/sobre' | '/termo';
const PATHS: Path[] = ['/', '/redes', '/analise', '/sincronizacoes', '/sobre', '/termo'];

export const VIEW_KEYS = ['v', 'sel', 'sort', 'p', 'mode', 'next'];

export interface Route {
  path: Path;
  params: URLSearchParams;
}

export function parseHash(hash: string): Route {
  const raw = hash.replace(/^#/, '');
  const [p, q = ''] = raw.split('?');
  const path = (PATHS as string[]).includes(p) ? (p as Path) : '/';
  return { path, params: new URLSearchParams(q) };
}

export const hrefOf = (path: Path, params?: URLSearchParams): string => {
  const q = params?.toString();
  return `#${path}${q ? `?${q}` : ''}`;
};

export function navigate(path: Path, params?: URLSearchParams, replace = false): void {
  const href = hrefOf(path, params);
  if (replace) history.replaceState(null, '', href);
  else history.pushState(null, '', href);
  window.dispatchEvent(new Event('wd-route'));
}

export function useRoute(): Route {
  const [route, setRoute] = useState<Route>(() => parseHash(location.hash));
  useEffect(() => {
    const on = () => setRoute(parseHash(location.hash));
    window.addEventListener('hashchange', on);
    window.addEventListener('popstate', on);
    window.addEventListener('wd-route', on);
    return () => {
      window.removeEventListener('hashchange', on);
      window.removeEventListener('popstate', on);
      window.removeEventListener('wd-route', on);
    };
  }, []);
  return route;
}


export function useSetParams(route: Route): (change: (p: URLSearchParams) => void, replace?: boolean) => void {
  return useCallback(
    (change, replace = true) => {
      const p = new URLSearchParams(parseHash(location.hash).params);
      change(p);
      navigate(route.path, p, replace);
    },
    [route.path],
  );
}
