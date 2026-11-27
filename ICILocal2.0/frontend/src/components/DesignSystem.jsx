import React,{useEffect,useRef,useState} from 'react';
import {Button as UiButton,Dialog as UiDialog,Drawer,FilterChip,Popover,ProgressMeter,StatTile,StatusChip} from '../ui/components.jsx';
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
const TOKENS=[['--ici-blue','#075B80','actions'],['--blue-100','#D7EBF4','selected'],['--blue-50','#EEF6FA','hover, active'],['--ink','#1B2A33','text'],['--ink-2','#4A5A66','secondary text'],['--line','#DCE3E8','borders'],['--canvas','#F4F6F8','app background'],['--success-bg','#E4F5EC','success, text #14654A'],['--attention-bg','#FFF3D6','attention, text #7A5200'],['--blocked-bg','#FDECEE','blocked, text #8E2433']];

function SharedComponents(){
  const [filter,setFilter]=useState('all');
  const [drawer,setDrawer]=useState(false);
  const [confirm,setConfirm]=useState(false);
  return <section className="panel" aria-labelledby="shared-components-title"><h2 id="shared-components-title">Shared components</h2><div className="panel-body">
    <h3>Status chips</h3><div className="component-row"><StatusChip tone="success">Complete</StatusChip><StatusChip tone="attention">Needs documents</StatusChip><StatusChip tone="blocked">Duplicate flag</StatusChip><StatusChip tone="info">Pre-enlisted</StatusChip><StatusChip>Draft</StatusChip></div>
    <h3>Buttons</h3><div className="component-row"><UiButton>Primary action</UiButton><UiButton variant="secondary">Secondary</UiButton><UiButton disabled>Unavailable</UiButton></div>
    <h3>Filter chips</h3><div className="component-row" role="group" aria-label="Example filters">{[['all','All',12],['waiting','Waiting',7],['blocked','Blocked',3]].map(([key,label,count])=><FilterChip key={key} selected={filter===key} count={count} onClick={()=>setFilter(key)}>{label}</FilterChip>)}</div>
    <h3>Stat tiles and progress meter</h3><div className="component-row"><StatTile label="Applicants" value="48" hint="This term"/><StatTile label="Waiting" value="7" tone="attention" hint="Need a decision"/><div style={{minWidth:240,flex:'1 1 240px'}}><ProgressMeter label="Requirements submitted" value={7} max={10}/></div></div>
    <h3>Drawer, dialog and popover</h3><div className="component-row"><UiButton variant="secondary" onClick={()=>setDrawer(true)}>Open details drawer</UiButton><UiButton variant="secondary" onClick={()=>setConfirm(true)}>Open confirmation</UiButton><Popover label="Show hint"><p style={{margin:0}}>Popovers hold short, non-blocking help. Escape closes them.</p></Popover></div>
    <Drawer open={drawer} onClose={()=>setDrawer(false)} title="Applicant details" footer={<UiButton onClick={()=>setDrawer(false)}>Done</UiButton>}><p>Drawers let staff inspect a record without leaving the page.</p></Drawer>
    <UiDialog open={confirm} onClose={()=>setConfirm(false)} title="Confirm this step?" actions={<><UiButton variant="secondary" onClick={()=>setConfirm(false)}>Cancel</UiButton><UiButton onClick={()=>setConfirm(false)}>Confirm</UiButton></>}><p>Dialogs are for irreversible steps and say what will change.</p></UiDialog>
  </div></section>;
}

function DesignSystem() {
  const [dialog, setDialog] = useState(false);
  const [toast, setToast] = useState('');
  useEffect(() => { if (toast) { const timer = setTimeout(() => setToast(''), 4000); return () => clearTimeout(timer); } }, [toast]);
  return <><div className="page-heading"><h1>Interface components</h1><p>Shared foundations for consistent, accessible school workflows.</p></div><div className="two-columns">
    <Panel title="Actions and feedback"><div className="component-row"><Button onClick={() => setToast('Example action completed.')}>Show notification</Button><Button variant="outline" onClick={() => setDialog(true)}>Open dialog</Button><Button disabled>Unavailable action</Button></div><div className="component-row"><Status tone="success">Complete</Status><Status tone="warning">Pending</Status><Status tone="danger">Needs review</Status><Status>Draft</Status></div></Panel>
    <Panel title="Form controls"><label htmlFor="sample-name">Student name</label><input id="sample-name" placeholder="Enter a student name"/><label htmlFor="sample-campus">Campus</label><select id="sample-campus"><option>Iligan Main</option><option>Kapatagan</option><option>Bugo, CDO</option></select><p className="field-help">Examples only. No student data is saved.</p></Panel>
    <Panel title="Colour tokens"><div className="swatches">{TOKENS.map(([name,value,use])=><div key={name}><span style={{background:`var(${name})`}}/><strong>{name}</strong><br/>{value} · {use}</div>)}</div><p className="field-help">Radii 8 (controls), 12 (cards, dialogs), full (chips). Inter 14–16 px with tabular numerals. Focus ring: 2 px ICI blue, 2 px offset.</p></Panel>
    <Panel title="Record states"><div className="empty-state"><h3>No records to display</h3><p>Connect the module API to load authorized records.</p></div><p role="status" aria-live="polite">Loading example: retrieving records…</p><div className="notice notice-error" role="alert">Example error: records could not be loaded. Try again.</div></Panel>
  </div><SharedComponents/>{dialog && <Dialog onClose={() => setDialog(false)} />}<div className="toast" role="status" aria-live="polite">{toast}</div></>;
}


export default DesignSystem;
