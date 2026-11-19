import {apiRequest} from '../api.js';
import {api} from './client.js';

// ICI2-27 registrar review of grade correction requests (requests come from ICI2-26).
// Runs on mock data until the backend exists. When the endpoints are live, set
// VITE_GRADE_CORRECTIONS_MOCK=false in frontend/.env and restart Vite.
const USE_MOCK=import.meta.env.VITE_GRADE_CORRECTIONS_MOCK!=='false';

/* Proposed contract (hand this to the backend owner)
 GET  /api/grade-corrections?status=pending|approved|rejected|all&page=0&size=25
      -> {items:[{id,requestNumber,studentName,studentNumber,subject,section,gradingPeriod,
                  currentGrade,requestedGrade (number, or a mark: INC|NG|DRP),requestedBy,submittedAt,status,canReview}],total}
 GET  /api/grade-corrections/{id}
      -> list fields + {reason,version,evidence:[{id,name,size}],history:[{id,status,actor,occurredAt,reason}]}
 POST /api/grade-corrections/{id}/decision  {action:'approve'|'reject',remarks,version}
      -> updates the grade and appends an audit row in one transaction; 409 if version is stale
 GET  /api/grade-corrections/{id}/evidence/{fileId}  -> file bytes
*/

const wait=()=>new Promise(resolve=>setTimeout(resolve,250));
const mockCases=[
 {id:101,requestNumber:'GCR-2026-0101',studentName:'Dela Cruz, Maria Angela',studentNumber:'2024-00123',subject:'Mathematics 9',section:'9-Rizal',gradingPeriod:'Q2',currentGrade:78,requestedGrade:85,requestedBy:'Santos, Ramon',submittedAt:'2026-11-10T08:30:00Z',status:'pending',canReview:true,version:1,
  reason:'Quiz 4 and the periodical exam were encoded under the wrong learner. Recomputed class record attached.',
  evidence:[{id:1,name:'recomputed-class-record.pdf',size:182400},{id:2,name:'periodical-exam-scan.jpg',size:512300}],
  history:[{id:1,status:'pending',actor:'Santos, Ramon',occurredAt:'2026-11-10T08:30:00Z',reason:'Request submitted'}]},
 {id:102,requestNumber:'GCR-2026-0102',studentName:'Reyes, Joshua Miguel',studentNumber:'2024-00456',subject:'Science 9',section:'9-Bonifacio',gradingPeriod:'Q2',currentGrade:88,requestedGrade:82,requestedBy:'Lim, Patricia',submittedAt:'2026-11-11T02:15:00Z',status:'pending',canReview:true,version:1,
  reason:'Extra credit was applied twice during encoding. Correcting to the computed grade.',
  evidence:[{id:3,name:'class-record-page.pdf',size:96200}],
  history:[{id:2,status:'pending',actor:'Lim, Patricia',occurredAt:'2026-11-11T02:15:00Z',reason:'Request submitted'}]},
 {id:103,requestNumber:'GCR-2026-0103',studentName:'Garcia, Sofia Beatrice',studentNumber:'2023-00987',subject:'English 10',section:'10-Mabini',gradingPeriod:'Q1',currentGrade:74,requestedGrade:76,requestedBy:'Tan, Eduardo',submittedAt:'2026-10-28T06:00:00Z',status:'approved',canReview:false,version:2,
  reason:'Late project submission was accepted by the adviser but not encoded.',
  evidence:[{id:4,name:'adviser-memo.pdf',size:70400}],
  history:[{id:4,status:'pending',actor:'Tan, Eduardo',occurredAt:'2026-10-28T06:00:00Z',reason:'Request submitted'},{id:5,status:'approved',actor:'Registrar Staff',occurredAt:'2026-10-30T03:20:00Z',reason:'Memo verified against the class record.'}]},
 {id:104,requestNumber:'GCR-2026-0104',studentName:'Mendoza, Carlo Antonio',studentNumber:'2024-00311',subject:'Filipino 9',section:'9-Rizal',gradingPeriod:'Q2',currentGrade:80,requestedGrade:90,requestedBy:'Villanueva, Liza',submittedAt:'2026-11-05T09:45:00Z',status:'rejected',canReview:false,version:2,
  reason:'Requesting a higher grade for a performance task.',
  evidence:[],
  history:[{id:6,status:'pending',actor:'Villanueva, Liza',occurredAt:'2026-11-05T09:45:00Z',reason:'Request submitted'},{id:7,status:'rejected',actor:'Registrar Staff',occurredAt:'2026-11-06T01:10:00Z',reason:'No supporting evidence for the change.'}]},
];
const summary=({reason,evidence,history,version,...row})=>row;

export async function listGradeCorrections({status,page}){
 if(USE_MOCK){await wait();const rows=mockCases.filter(row=>status==='all'||row.status===status);return {items:rows.map(summary),total:rows.length};}
 return apiRequest(`/api/grade-corrections?${new URLSearchParams({status,page:String(page),size:'25'})}`);
}
export async function getGradeCorrection(id){
 if(USE_MOCK){await wait();const found=mockCases.find(row=>String(row.id)===String(id));if(!found)throw new Error('Correction request not found.');return structuredClone(found);}
 return apiRequest(`/api/grade-corrections/${id}`);
}
export async function decideGradeCorrection(id,decision){
 if(!['approve','reject'].includes(decision.action))throw new Error('Choose approve or reject.');
 if(!decision.remarks?.trim())throw new Error('Remarks are required for the audit trail.');
 if(USE_MOCK){
  await wait();
  const found=mockCases.find(row=>String(row.id)===String(id));
  if(!found)throw new Error('Correction request not found.');
  if(found.status!=='pending'||found.version!==decision.version)throw new Error('This request was already decided or changed. Reload and review it again.');
  found.status=decision.action==='approve'?'approved':'rejected';
  found.canReview=false;found.version+=1;
  if(decision.action==='approve')found.currentGrade=found.requestedGrade;
  found.history.push({id:Date.now(),status:found.status,actor:'Registrar Staff',occurredAt:new Date().toISOString(),reason:decision.remarks});
  return {id:found.id,status:found.status};
 }
 return apiRequest(`/api/grade-corrections/${id}/decision`,{method:'POST',body:JSON.stringify(decision)});
}
function saveBlob(blob,name){
 const url=URL.createObjectURL(blob),link=document.createElement('a');
 link.href=url;link.download=name;document.body.appendChild(link);link.click();link.remove();URL.revokeObjectURL(url);
}
export async function downloadEvidence(caseId,file){
 if(USE_MOCK){await wait();saveBlob(new Blob([`Mock evidence file: ${file.name}`],{type:'text/plain'}),file.name);return;}
 const response=await api.get(`/grade-corrections/${caseId}/evidence/${file.id}`,{responseType:'blob'});
 saveBlob(response.data,file.name);
}