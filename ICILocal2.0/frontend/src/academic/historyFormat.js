export const statusLabel=value=>({provisional:'Pre-enlisted'})[value]??(value?value[0].toUpperCase()+value.slice(1).replaceAll('_',' '):'Not recorded');

export function gradeText(grade){
  if(!grade)return '—';
  const text=grade.value!=null?Number(grade.value).toFixed(2):grade.mark??'—';
  return grade.state==='finalized'?text:`${text}*`;
}

export function periodColumns(term){
  const seen=new Map();
  for(const subject of term.subjects)for(const grade of subject.grades)seen.set(grade.sequence,grade.period);
  return [...seen.entries()].sort((a,b)=>a[0]-b[0]).map(([sequence,period])=>({sequence,period}));
}

export const gradeFor=(subject,sequence)=>subject.grades.find(grade=>grade.sequence===sequence);