import { toParams, type Filters } from '../filters';
import { hrefOf, type Path, type Route } from '../router';
import { BrandMark } from './Icons';

const LINKS: { path: Path; label: string; data: boolean }[] = [
  { path: '/', label: 'Painel', data: true },
  { path: '/redes', label: 'Redes', data: true },
  { path: '/analise', label: 'Análise', data: true },
  { path: '/sobre', label: 'Sobre', data: false },
];

export default function Header({
  route,
  filters,
  accepted,
}: {
  route: Route;
  filters: Filters;
  accepted: boolean;
}) {

  const carry = toParams(filters);
  return (
    <header className="masthead">
      <a className="brand" href={hrefOf('/')} aria-label="Wardriver Web, página inicial">
        <BrandMark />
        <span>
          <strong>Wardriver</strong>
          <small>Web</small>
        </span>
      </a>
      <nav className="nav" aria-label="Seções">
        {LINKS.map((l) => (
          <a
            key={l.path}
            href={hrefOf(l.path, l.data ? carry : undefined)}
            aria-current={route.path === l.path ? 'page' : undefined}
            title={l.data && !accepted ? 'Exige aceite do termo de compromisso' : undefined}
          >
            {l.label}
          </a>
        ))}
      </nav>
      <div className="masthead-actions">
        <a className={`btn btn-ghost${accepted ? ' is-ok' : ''}`} href={hrefOf('/termo')} aria-current={route.path === '/termo' ? 'page' : undefined}>
          {accepted ? 'Termo aceito' : 'Termo'}
        </a>
      </div>
    </header>
  );
}
