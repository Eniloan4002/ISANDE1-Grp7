import {apiRequest} from '../api.js';

export const send=(path,method,body)=>apiRequest(path,{method,body:JSON.stringify(body)});
export const statusLabel=value=>({submitted:'Submitted',pre_enlisted:'Pre-enlisted',under_review:'For review',duplicate_flag:'Duplicate flagged',enrolled:'Enrolled',rejected:'Rejected',withdrawn:'Withdrawn'}[value]??value);
export function queueApplicant(row){return {...row,statusCode:row.status,status:statusLabel(row.status),requirements:row.requirementsStatus,strand:row.program,duplicate:row.status==='duplicate_flag'};}
export function referenceData(data){
  if(!data)return null;
  return {...data,programs:[{id:'SHS',name:'Senior High School'},{id:'TVET',name:'Technical-Vocational'}],
    tracks:[{code:'ACADEMIC',name:'Academic',programId:'SHS'},{code:'TVL',name:'TVL',programId:'SHS'},{code:'TVET',name:'TVET',programId:'TVET'}],
    specializations:data.programs.map(program=>({id:program.id,code:program.code,name:program.name,trackCode:program.category==='TVET'?'TVET':program.code.startsWith('TVL_')?'TVL':'ACADEMIC'})),
    terms:data.terms.map(term=>({...term,name:`${term.academicYear} / ${term.name}`}))};
}
export function intakePayload(form,reference){
  const specialization=reference.specializations.find(item=>item.code===form.specializationCode);
  const optional=value=>value?.trim()||null;
  return {campusId:Number(form.campusId),termId:Number(form.termId),programId:specialization?.id,
    category:form.category.toLowerCase(),firstName:form.firstName,middleName:optional(form.middleName),lastName:form.lastName,
    birthDate:form.birthDate,lrn:optional(form.lrn),sex:optional(form.sex),phone:optional(form.phone),email:optional(form.email),
    voucher:form.voucher,track:form.trackCode,gradeLevel:form.gradeLevel,district:optional(form.district),transportRoute:optional(form.transportRoute),
    previousSchool:optional(form.previousSchool),householdIncome:optional(form.householdIncome),guardians:form.guardians};
}
export function applicantDetail(data,reference){
  const profile=data.profile??{};
  const program=reference?.specializations.find(item=>String(item.id)===String(data.programId));
  return {...data,...profile,statusCode:data.status,status:statusLabel(data.status),requirements:data.workflow.checklist.summary.overallStatus,
    campus:reference?.campuses.find(item=>String(item.id)===String(data.campusId))?.name,
    term:reference?.terms.find(item=>String(item.id)===String(data.termId))?.name,
    program:program?.trackCode==='TVET'?'Technical-Vocational':'Senior High School',strand:program?.name,
    track:profile.track,duplicate:data.status==='duplicate_flag'};
}
