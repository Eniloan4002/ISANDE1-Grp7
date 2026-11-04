import React, { useEffect, useRef, useState } from 'react';
import {apiRequest} from './api.js';

const definitions = {
  subjects: {label:'Subjects',singular:'subject',endpoint:'subjects',fields:[
    {key:'code',label:'Subject code',maxLength:40},{key:'name',label:'Subject name',maxLength:200},
    {key:'units',label:'Units',type:'number',min:'0.01',max:'999.99',step:'.01'},
    {key:'active',label:'Availability',type:'active'},
  ]},
  years: {label:'Academic years',singular:'academic year',endpoint:'academic-years',fields:[
    {key:'label',label:'Year label',maxLength:20},{key:'startsOn',column:'starts_on',label:'Start date',type:'date'},
    {key:'endsOn',column:'ends_on',label:'End date',type:'date'},
  ]},
  terms: {label:'Terms',singular:'term',endpoint:'terms',fields:[
    {key:'academicYearId',column:'academic_year_id',label:'Academic year',type:'parent',source:'years'},
    {key:'code',label:'Term code',maxLength:20},{key:'name',label:'Term name',maxLength:200},
    {key:'startsOn',column:'starts_on',label:'Start date',type:'date'},
    {key:'endsOn',column:'ends_on',label:'End date',type:'date'},
  ]},
  periods: {label:'Grading periods',singular:'grading period',endpoint:'grading-periods',fields:[
    {key:'termId',column:'term_id',label:'Term',type:'parent',source:'terms'},
    {key:'code',label:'Period code',maxLength:20},
    {key:'sequence',label:'Sequence',type:'number',min:1,max:32767,step:1},
    {key:'startsOn',column:'starts_on',label:'Start date',type:'date'},
    {key:'endsOn',column:'ends_on',label:'End date',type:'date'},
  ]},
};

async function request(path, options) {
  return apiRequest(`/api/setup/${path}`,options);
}

const fresh=definition=>Object.fromEntries(definition.fields.map(field=>[field.key,field.type==='active'?'true':'']));
export default function AcademicSetup() {
  const [tab,setTab]=useState('subjects');
  const definition=definitions[tab];
  const [data,setData]=useState({subjects:[],years:[],terms:[],periods:[]});
  const [form,setForm]=useState(()=>fresh(definitions.subjects));
  const [editing,setEditing]=useState(null);
  const [loading,setLoading]=useState(true);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [errors,setErrors]=useState({});
  const [notice,setNotice]=useState('');
  const [deleting,setDeleting]=useState(null);
  const dialog=useRef(null);
  const deleteTrigger=useRef(null);
  const firstField=useRef(null);
  const tabButtons=useRef({});
  const mounted=useRef(true);

  async function load() {
    setLoading(true);setError('');
    try {
      const values=await Promise.all(Object.values(definitions).map(item=>request(item.endpoint)));
      if(mounted.current) setData(Object.fromEntries(Object.keys(definitions).map((key,index)=>[key,values[index]])));
    }catch(error){if(mounted.current)setError(error.message);}
    finally {if(mounted.current)setLoading(false);}
  }
  useEffect(()=>{mounted.current=true;load();return()=>{mounted.current=false;};},[]);
  useEffect(()=>{if(notice){const timer=setTimeout(()=>setNotice(''),5000);return()=>clearTimeout(timer);}},[notice]);
  useEffect(()=>{
    if(deleting){dialog.current.showModal();return()=>{dialog.current?.close();deleteTrigger.current?.focus();};}
  },[deleting]);

  function reset(next=tab) {setEditing(null);setForm(fresh(definitions[next]));setErrors({});setError('');}
  function chooseTab(next) {if(busy)return;setTab(next);reset(next);setNotice('');}
  function parentLabel(source,id) {
    const row=data[source].find(row=>String(row.id)===String(id));
    if(!row)return 'Unavailable';
    return source==='years'?row.label:`${parentLabel('years',row.academic_year_id)} / ${row.name} (${row.code})`;
  }
  function display(row,field) {
    const value=row[field.column||field.key];
    if(field.type==='parent')return parentLabel(field.source,value);
    if(field.type==='active')return value?'Active':'Inactive';
    return value??'—';
  }
  function edit(row) {
    setEditing(row);setError('');setErrors({});
    setForm(Object.fromEntries(definition.fields.map(field=>[field.key,String(row[field.column||field.key]??'')])));
    firstField.current?.focus();
  }
  async function save(event) {
    event.preventDefault();setError('');setErrors({});
    if(form.startsOn&&form.endsOn&&form.endsOn<form.startsOn){setError('End date must be on or after start date.');return;}
    if(tab==='years'&&form.startsOn===form.endsOn){setError('An academic year must end after its start date.');return;}
    const parent=tab==='terms'?data.years.find(row=>String(row.id)===form.academicYearId):tab==='periods'?data.terms.find(row=>String(row.id)===form.termId):null;
    if(parent&&(form.startsOn<parent.starts_on||form.endsOn>parent.ends_on)){setError('Choose dates within the selected parent’s start and end dates.');return;}
    const payload=Object.fromEntries(definition.fields.map(field=>[field.key,
      field.type==='number'||field.type==='parent'?Number(form[field.key]):field.type==='active'?form[field.key]==='true':form[field.key].trim()]));
    if(editing)payload.version=editing.version;
    setBusy(true);
    try {
      await request(definition.endpoint+(editing?`/${editing.id}`:''),{method:editing?'PUT':'POST',body:JSON.stringify(payload)});
      const message=`${definition.singular[0].toUpperCase()+definition.singular.slice(1)} ${editing?'updated':'created'}.`;
      reset();await load();setNotice(message);
    }catch(error){setError(error.message);setErrors(error.fields);}
    finally {setBusy(false);}
  }
  async function remove() {
    setBusy(true);setError('');
    try {
      await request(`${definition.endpoint}/${deleting.id}?version=${deleting.version}`,{method:'DELETE'});
      if(editing?.id===deleting.id)reset();
      setDeleting(null);await load();setNotice('Record deleted.');
    }catch(error){setDeleting(null);setError(error.message);}
    finally {setBusy(false);}
  }
  const missingParent=definition.fields.find(field=>field.type==='parent'&&!data[field.source].length);
  return <><div className="page-heading"><h1>Academic setup</h1><p>Manage the subjects and calendar used by class assignments and student grades.</p></div>
    <div className="setup-tabs" role="tablist" aria-label="Academic configuration">{Object.entries(definitions).map(([key,item],index,items)=><button ref={node=>tabButtons.current[key]=node} key={key} id={`tab-${key}`} role="tab" tabIndex={tab===key?0:-1} aria-selected={tab===key} aria-controls="setup-panel" disabled={busy} onClick={()=>chooseTab(key)} onKeyDown={event=>{
      let next;if(event.key==='ArrowRight')next=items[(index+1)%items.length][0];else if(event.key==='ArrowLeft')next=items[(index+items.length-1)%items.length][0];else if(event.key==='Home')next=items[0][0];else if(event.key==='End')next=items.at(-1)[0];
      if(next){event.preventDefault();chooseTab(next);tabButtons.current[next]?.focus();}
    }}>{item.label} <span>({data[key].length})</span></button>)}</div>
    <div id="setup-panel" role="tabpanel" aria-labelledby={`tab-${tab}`}>
    {error&&<div className="notice notice-error" role="alert">{error}<button className="button button-outline retry-button" onClick={load} disabled={busy||loading}>Reload records</button></div>}
    {loading&&<p role="status">Loading setup records…</p>}
    {!loading&&<div className="setup-layout"><section className="panel"><h2>{editing?`Edit ${definition.singular}`:`Add ${definition.singular}`}</h2><form className="panel-body" onSubmit={save}>
      {missingParent&&<p className="notice">Create {missingParent.source==='years'?'an academic year':'a term'} before adding {definition.label.toLowerCase()}.</p>}
      {definition.fields.map((field,index)=><div key={`${tab}-${field.key}`}><label htmlFor={`setup-${field.key}`}>{field.label}</label>{field.type==='parent'||field.type==='active'?<select ref={index===0?firstField:undefined} id={`setup-${field.key}`} required value={form[field.key]??''} disabled={busy||Boolean(editing&&field.type==='parent')} aria-invalid={Boolean(errors?.[field.key])} onChange={event=>setForm({...form,[field.key]:event.target.value})}>
        {field.type==='active'?<><option value="true">Active</option><option value="false">Inactive</option></>:<><option value="">Select {field.label.toLowerCase()}…</option>{data[field.source].map(row=><option key={row.id} value={row.id}>{parentLabel(field.source,row.id)}</option>)}</>}
      </select>:<input ref={index===0?firstField:undefined} id={`setup-${field.key}`} type={field.type||'text'} required maxLength={field.maxLength} min={field.min} max={field.max} step={field.step} value={form[field.key]??''} disabled={busy} aria-invalid={Boolean(errors?.[field.key])} onChange={event=>setForm({...form,[field.key]:event.target.value})}/>} {errors?.[field.key]&&<p className="field-error">{errors[field.key]}</p>}</div>)}
      {editing&&definition.fields.some(field=>field.type==='parent')&&<p className="field-help">The parent is fixed to preserve historical assignments. Create a new record for a different parent.</p>}
      <div className="component-row"><button className="button" disabled={busy||Boolean(missingParent)}>{busy?'Saving…':editing?'Save changes':`Create ${definition.singular}`}</button>{editing&&<button className="button button-outline" type="button" disabled={busy} onClick={()=>reset()}>Cancel edit</button>}</div>
    </form></section><section className="panel"><h2>{definition.label}</h2><div className="panel-body"><p className="field-help">Referenced records are protected from deletion. Edit their details to preserve history.</p>{data[tab].length?<div className="setup-table-wrap"><table className="setup-table"><thead><tr>{definition.fields.map(field=><th key={field.key} scope="col">{field.label}</th>)}<th scope="col">Actions</th></tr></thead><tbody>{data[tab].map(row=><tr key={row.id}>{definition.fields.map(field=><td key={field.key}>{display(row,field)}</td>)}<td><div className="table-actions"><button className="button button-outline" disabled={busy} aria-label={`Edit ${row.name||row.label||row.code}`} onClick={()=>edit(row)}>Edit</button><button className="button button-outline delete-action" disabled={busy} aria-label={`Delete ${row.name||row.label||row.code}`} onClick={event=>{deleteTrigger.current=event.currentTarget;setDeleting(row);}}>Delete</button></div></td></tr>)}</tbody></table></div>:<div className="empty-state"><h3>No {definition.label.toLowerCase()} yet</h3><p>Add the first {definition.singular} using the form.</p></div>}</div></section></div>}
    </div><div className="toast" role="status" aria-live="polite">{notice}</div>
    {deleting&&<dialog ref={dialog} aria-labelledby="delete-title" onCancel={event=>{event.preventDefault();if(!busy)setDeleting(null);}}><h2 id="delete-title">Delete {definition.singular}?</h2><p>Delete {deleting.name||deleting.label||deleting.code}? Referenced records will be kept and the service will explain why deletion is blocked.</p><div className="component-row"><button className="button button-outline" disabled={busy} onClick={()=>setDeleting(null)}>Keep record</button><button className="button" disabled={busy} onClick={remove}>Confirm deletion</button></div></dialog>}
  </>;
}
