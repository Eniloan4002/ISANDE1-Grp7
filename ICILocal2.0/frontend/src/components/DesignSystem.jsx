import React,{useEffect,useRef,useState} from 'react';
export function Button({ variant = 'primary', ...props }) {
  return <button className={`button button-${variant}`} {...props} />;
}
export function Status({ children, tone = 'neutral' }) {
  return <span className={`status status-${tone}`}>{children}</span>;
}
export function Panel({ title, children }) {
  return <section className="panel"><h2>{title}</h2><div className="panel-body">{children}</div></section>;
}

function Dialog({ onClose }) {
  const ref = useRef(null);
  const trigger = useRef(document.activeElement);
  useEffect(() => {
    const dialog = ref.current;
    dialog.showModal();
    return () => {
      dialog.close();
      trigger.current?.focus();
    };
  }, []);
  return <dialog ref={ref} aria-labelledby="dialog-title" onCancel={event => { event.preventDefault(); onClose(); }}><h2 id="dialog-title">Confirm your action</h2><p>Dialogs provide a focused confirmation before a record changes.</p><Button onClick={onClose}>Close dialog</Button></dialog>;
}
function DesignSystem() {
  const [dialog, setDialog] = useState(false);
  const [toast, setToast] = useState('');
  useEffect(() => { if (toast) { const timer = setTimeout(() => setToast(''), 4000); return () => clearTimeout(timer); } }, [toast]);
  return <><div className="page-heading"><h1>Interface components</h1><p>Shared foundations for consistent, accessible school workflows.</p></div><div className="two-columns">
    <Panel title="Actions and feedback"><div className="component-row"><Button onClick={() => setToast('Example action completed.')}>Show notification</Button><Button variant="outline" onClick={() => setDialog(true)}>Open dialog</Button><Button disabled>Unavailable action</Button></div><div className="component-row"><Status tone="success">Complete</Status><Status tone="warning">Pending</Status><Status tone="danger">Needs review</Status><Status>Draft</Status></div></Panel>
    <Panel title="Form controls"><label htmlFor="sample-name">Student name</label><input id="sample-name" placeholder="Enter a student name"/><label htmlFor="sample-campus">Campus</label><select id="sample-campus"><option>Iligan Main</option><option>Kapatagan</option><option>Bugo, CDO</option></select><p className="field-help">Examples only. No student data is saved.</p></Panel>
    <Panel title="Brand palette"><div className="swatches">{['primary', 'slate', 'coral', 'mint', 'charcoal', 'mist'].map(name => <div key={name}><span style={{ background: `var(--brand-${name})` }} />{name}</div>)}</div></Panel>
    <Panel title="Record states"><div className="empty-state"><h3>No records to display</h3><p>Connect the module API to load authorized records.</p></div><p role="status" aria-live="polite">Loading example: retrieving records…</p><div className="notice notice-error" role="alert">Example error: records could not be loaded. Try again.</div></Panel>
  </div>{dialog && <Dialog onClose={() => setDialog(false)} />}<div className="toast" role="status" aria-live="polite">{toast}</div></>;
}


export default DesignSystem;
