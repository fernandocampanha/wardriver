
export const BrandMark = () => (
  <svg className="brandmark" viewBox="0 0 28 28" width="30" height="30" aria-hidden="true" focusable="false">
    <circle cx="14" cy="14" r="12" fill="none" stroke="currentColor" strokeWidth="1.4" />
    <circle cx="14" cy="14" r="7.2" fill="none" stroke="currentColor" strokeWidth="1" strokeDasharray="2 2.6" opacity="0.7" />
    <g className="sweep">
      <path d="M14 14 L14 2 A12 12 0 0 1 24.4 8 Z" fill="currentColor" opacity="0.28" />
      <line x1="14" y1="14" x2="14" y2="2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </g>
    <circle cx="14" cy="14" r="1.8" fill="currentColor" />
    <circle cx="19.5" cy="9.5" r="1.4" fill="#ff4d6a" />
  </svg>
);

export const SearchIcon = () => (
  <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" focusable="false">
    <path fill="none" stroke="currentColor" strokeWidth="2" d="M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13Zm4.8 11.3L20 20" />
  </svg>
);

export const LocateIcon = () => (
  <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
    <path fill="none" stroke="currentColor" strokeWidth="2" d="M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Zm0-6v4m0 12v4M2 12h4m12 0h4" />
  </svg>
);
