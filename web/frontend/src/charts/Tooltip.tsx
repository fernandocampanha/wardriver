import { useEffect, useRef } from 'react';


export default function Tooltip() {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const tip = ref.current;
    if (!tip) return;
    const show = (target: HTMLElement, x: number, y: number) => {
      const value = document.createElement('strong');
      value.textContent = target.dataset.tipValue ?? '';
      const label = document.createElement('span');
      label.textContent = target.dataset.tipLabel ?? '';
      tip.replaceChildren(value, label);
      tip.hidden = false;
      const pad = 12;
      const w = tip.offsetWidth;
      const h = tip.offsetHeight;
      tip.style.left = `${Math.min(Math.max(8, x + pad), window.innerWidth - w - 8)}px`;
      tip.style.top = `${Math.min(Math.max(8, y - h - pad), window.innerHeight - h - 8)}px`;
    };
    const hide = () => {
      tip.hidden = true;
    };
    const closestTip = (t: EventTarget | null) => (t as Element | null)?.closest?.('[data-tip]') as HTMLElement | null;
    const onMove = (e: PointerEvent) => {
      const target = closestTip(e.target);
      if (target) show(target, e.clientX, e.clientY);
      else hide();
    };
    const onFocus = (e: FocusEvent) => {
      const target = closestTip(e.target);
      if (!target) return;
      const r = target.getBoundingClientRect();
      show(target, r.left + r.width / 2, r.top);
    };
    document.addEventListener('pointermove', onMove);
    document.addEventListener('focusin', onFocus);
    document.addEventListener('focusout', hide);
    document.addEventListener('scroll', hide, true);
    return () => {
      document.removeEventListener('pointermove', onMove);
      document.removeEventListener('focusin', onFocus);
      document.removeEventListener('focusout', hide);
      document.removeEventListener('scroll', hide, true);
    };
  }, []);

  return <div ref={ref} id="tooltip" className="tooltip" role="tooltip" hidden />;
}
