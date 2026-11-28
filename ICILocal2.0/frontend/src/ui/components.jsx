import { useCallback, useEffect, useId, useRef, useState } from 'react';

// Shared ICI Local 2.0 components (ICI2-96). Class names live in ./ui.css.
const cx = (...parts) => parts.filter(Boolean).join(' ');

/** Dot plus word. The word is always visible so state never relies on colour alone. */
export function StatusChip({ tone = 'neutral', children, ...rest }) {
  return <span className={cx('ui-chip', `ui-chip-${tone}`)} {...rest}><span className="ui-chip-dot" aria-hidden="true" />{children}</span>;
}

export function Button({ variant = 'primary', type = 'button', className, ...rest }) {
  return <button type={type} className={cx('ui-btn', `ui-btn-${variant}`, className)} {...rest} />;
}

/** Toggle filter with an optional count; exposes pressed state to assistive tech. */
export function FilterChip({ selected = false, count, children, className, ...rest }) {
  return <button type="button" aria-pressed={selected} className={cx('ui-filter', selected && 'is-selected', className)} {...rest}>
    <span>{children}</span>{count !== undefined && count !== null && <span className="ui-filter-count">{count}</span>}
  </button>;
}

export function StatTile({ label, value, hint, tone }) {
  return <div className={cx('ui-stat', tone && `ui-stat-${tone}`)}>
    <div className="ui-stat-label">{label}</div>
    <div className="ui-stat-value">{value}</div>
    {hint && <div className="ui-stat-hint">{hint}</div>}
  </div>;
}

export function ProgressMeter({ value, max = 100, label, valueText }) {
  const safeMax = max > 0 ? max : 1;
  const clamped = Math.min(Math.max(value, 0), safeMax);
  const text = valueText ?? `${value} / ${max}`;
  return <div className="ui-meter">
    <div className="ui-meter-head"><span>{label}</span><b>{text}</b></div>
    <div className="ui-meter-track" role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={safeMax} aria-valuenow={clamped} aria-valuetext={text}>
      <div className="ui-meter-fill" style={{ width: `${(clamped / safeMax) * 100}%` }} />
    </div>
  </div>;
}

// Modal behaviour shared by Dialog and Drawer: native <dialog> gives the focus trap and Escape.
function useModal(open, onClose) {
  const ref = useRef(null);
  const trigger = useRef(null);
  useEffect(() => {
    const node = ref.current;
    if (!node || !open) return undefined;
    trigger.current = document.activeElement;
    if (typeof node.showModal === 'function') { if (!node.open) node.showModal(); } else node.setAttribute('open', '');
    return () => {
      if (typeof node.close === 'function') { if (node.open) node.close(); } else node.removeAttribute('open');
      if (trigger.current instanceof HTMLElement && trigger.current.isConnected) trigger.current.focus();
    };
  }, [open]);
  const onCancel = useCallback(event => { event.preventDefault(); onClose?.(); }, [onClose]);
  return { ref, onCancel };
}

/** Confirmation dialog. Backdrop clicks do not dismiss it: confirmations must be answered. */
export function Dialog({ open, onClose, title, children, actions, tone }) {
  const titleId = useId();
  const { ref, onCancel } = useModal(open, onClose);
  if (!open) return null;
  return <dialog ref={ref} className={cx('ui-dialog', tone && `ui-dialog-${tone}`)} aria-labelledby={titleId} onCancel={onCancel}>
    <h2 id={titleId} className="ui-dialog-title">{title}</h2>
    <div className="ui-dialog-body">{children}</div>
    {actions && <div className="ui-dialog-actions">{actions}</div>}
  </dialog>;
}

/** Side drawer for looking at a record without leaving the page. Backdrop click and Escape close it. */
export function Drawer({ open, onClose, title, children, footer }) {
  const titleId = useId();
  const { ref, onCancel } = useModal(open, onClose);
  if (!open) return null;
  return <dialog ref={ref} className="ui-drawer" aria-labelledby={titleId} onCancel={onCancel} onClick={event => { if (event.target === ref.current) onClose?.(); }}>
    <div className="ui-drawer-panel">
      <header className="ui-drawer-head"><h2 id={titleId}>{title}</h2><button type="button" className="ui-icon-btn" aria-label="Close" onClick={onClose}><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg></button></header>
      <div className="ui-drawer-body">{children}</div>
      {footer && <footer className="ui-drawer-foot">{footer}</footer>}
    </div>
  </dialog>;
}

/** Small anchored panel. Opens from its own button; Escape or an outside click closes it and focus returns to the button. */
export function Popover({ label, children, align = 'start', className }) {
  const [open, setOpen] = useState(false);
  const wrapper = useRef(null);
  const button = useRef(null);
  const panelId = useId();
  useEffect(() => {
    if (!open) return undefined;
    const onPointer = event => { if (!wrapper.current?.contains(event.target)) setOpen(false); };
    const onKey = event => { if (event.key === 'Escape') { setOpen(false); button.current?.focus(); } };
    document.addEventListener('pointerdown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('pointerdown', onPointer); document.removeEventListener('keydown', onKey); };
  }, [open]);
  return <div className={cx('ui-popover', className)} ref={wrapper}>
    <button type="button" ref={button} className="ui-btn ui-btn-secondary" aria-expanded={open} aria-controls={panelId} onClick={() => setOpen(value => !value)}>{label}</button>
    {open && <div id={panelId} className={cx('ui-popover-panel', `ui-popover-${align}`)} role="group" aria-label={typeof label === 'string' ? label : undefined}>{children}</div>}
  </div>;
}
