import {jsPDF} from 'jspdf';
import autoTable from 'jspdf-autotable';
import {gradeFor,gradeText,periodColumns,statusLabel} from './historyFormat.js';

const HEAD={fillColor:[7,91,128]};
const LEFT=40;

export function downloadHistoryPdf(data){
  const {student,terms,sectionChanges,credentials}=data;
  const doc=new jsPDF({unit:'pt',format:'a4'});
  doc.setFontSize(16);doc.text('Consolidated Student Academic History',LEFT,48);
  doc.setFontSize(10);doc.setTextColor(90);
  doc.text('Iligan Computer Institute',LEFT,64);
  doc.text(`Generated ${new Date().toLocaleString()}`,LEFT,78);
  doc.setTextColor(0);
  autoTable(doc,{startY:92,theme:'plain',styles:{fontSize:10,cellPadding:2},columnStyles:{0:{fontStyle:'bold',cellWidth:110}},body:[
    ['Student',student.name],['Student number',student.studentNumber],['LRN',student.lrn??'Not recorded'],
    ['Program',`${student.program} / ${student.programName}`],['Campus',student.campus],['Record status',student.active?'Active':'Archived'],
  ]});

  function heading(text){
    let y=doc.lastAutoTable.finalY+24;
    if(y>doc.internal.pageSize.getHeight()-120){doc.addPage();y=48;}
    doc.setFontSize(12);doc.setFont(undefined,'bold');doc.text(text,LEFT,y);doc.setFont(undefined,'normal');
    return y+8;
  }

  for(const term of terms){
    const columns=periodColumns(term);
    const startY=heading(`${term.academicYear} / ${term.term}: ${term.section??'No section assigned'} (${statusLabel(term.status)})`);
    autoTable(doc,{startY,head:[['Code','Subject','Units',...columns.map(column=>column.period)]],headStyles:HEAD,styles:{fontSize:9},
      body:term.subjects.length
        ?term.subjects.map(subject=>[subject.code,subject.name,subject.units,...columns.map(column=>gradeText(gradeFor(subject,column.sequence)))])
        :[[{content:'No class records for this term.',colSpan:3+columns.length}]]});
  }
  doc.setFontSize(8);doc.text('* Submitted by the teacher, not yet finalized.',LEFT,doc.lastAutoTable.finalY+14);

  const sectionY=heading('Section history');
  autoTable(doc,{startY:sectionY,head:[['Term','Section','Grade level','Adviser','Room']],headStyles:HEAD,styles:{fontSize:9},
    body:terms.length?terms.map(term=>[`${term.academicYear} / ${term.term}`,term.section??'Unassigned',term.gradeLevel??'',term.adviser??'Not assigned',term.room??'']):[[{content:'No enrollment records.',colSpan:5}]]});
  if(sectionChanges.length){
    autoTable(doc,{startY:doc.lastAutoTable.finalY+10,head:[['Changed','From','To','By']],headStyles:HEAD,styles:{fontSize:9},
      body:sectionChanges.map(change=>[new Date(change.occurredAt).toLocaleString(),change.fromSection??'Unassigned',change.toSection??'Unassigned',change.actor??'System'])});
  }

  const credentialY=heading(credentials?`Credential completion: ${credentials.overallStatus} (${credentials.submitted} of ${credentials.mandatoryTotal} mandatory submitted)`:'Credential completion');
  autoTable(doc,{startY:credentialY,head:[['Credential','Requirement','Status']],headStyles:HEAD,styles:{fontSize:9},
    body:credentials?.items.length
      ?credentials.items.map(item=>[item.name,item.mandatory?'Mandatory':'Optional',statusLabel(item.status)])
      :[[{content:'No application record is linked to this student.',colSpan:3}]]});

  const pages=doc.getNumberOfPages();
  for(let page=1;page<=pages;page++){
    doc.setPage(page);doc.setFontSize(8);
    doc.text(`Page ${page} of ${pages}`,doc.internal.pageSize.getWidth()-LEFT,doc.internal.pageSize.getHeight()-24,{align:'right'});
  }
  doc.save(`academic-history-${student.studentNumber}.pdf`);
}