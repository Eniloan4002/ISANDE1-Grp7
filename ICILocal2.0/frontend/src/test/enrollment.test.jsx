import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor,within} from '@testing-library/react';
import {MemoryRouter} from 'react-router';
import EnrollmentWorkspace,{SectionConfiguration} from '../EnrollmentWorkspace.jsx';
import {apiRequest} from '../api.js';
vi.mock('../api.js',()=>({apiRequest:vi.fn()}));
const reference={campuses:[{id:1,name:'Iligan Main'}],terms:[{id:2,name:'Semester 1',academicYear:'2026-2027'}],programs:[{id:3,name:'STEM'}],advisers:[]};
const applicant={id:10,applicationNumber:'APP-10',name:'Ana Test',campusId:1,campus:'Iligan Main',termId:2,programId:3,program:'STEM',gradeLevel:'11',requirementsStatus:'Pending',status:'pre_enlisted'};
const credential={code:'PSA',name:'PSA Birth Certificate',mandatory:true,status:'pending',version:0,notes:null};
const fixture=()=>({application:{...applicant,version:0},checklist:{applicationId:10,summary:{overallStatus:'Pending',mandatoryTotal:1,submitted:0,missing:0,invalid:0},items:[credential]},attendance:[],attendancePresent:false,enrollment:null,canConfirm:false,blockers:['Submit every mandatory credential.']});
const section={id:30,name:'STEM A',campusId:1,campus:'Iligan Main',termId:2,term:'Semester 1',programId:3,program:'STEM',gradeLevel:'11',capacity:40,occupied:39,available:1,version:0,room:'204'};
let detail,sections;
function show(mode){render(<MemoryRouter initialEntries={['/enrollment/requirements?applicationId=10']}><EnrollmentWorkspace mode={mode}/></MemoryRouter>);}
beforeEach(()=>{
  detail=fixture();sections=[section];
  HTMLDialogElement.prototype.showModal=function(){this.open=true;};HTMLDialogElement.prototype.close=function(){this.open=false;};
  apiRequest.mockImplementation(async(path,options={})=>{
    if(options.method&&options.method!=='GET')return {};
    if(path.includes('/reference-data'))return reference;
    if(path.startsWith('/api/enrollment/applications'))return {items:[applicant],total:1};
    if(path.includes('/requirements/audit'))return {items:[],total:0};
    if(path.endsWith('/enrollment'))return detail;
    if(path.startsWith('/api/sections'))return {items:sections,total:sections.length};
    throw new Error(`Unexpected API request ${path}`);
  });
});
afterEach(()=>{cleanup();vi.clearAllMocks();});

describe('requirement verification UI',()=>{
  it('saves exact catalog codes, status, notes and optimistic version',async()=>{
    show('requirements');await screen.findByLabelText('Status: PSA');
    fireEvent.change(screen.getByLabelText('Status: PSA'),{target:{value:'submitted'}});
    fireEvent.change(screen.getByLabelText('Verification notes: PSA'),{target:{value:'Original inspected'}});
    fireEvent.click(screen.getByRole('button',{name:'Save PSA'}));
    await waitFor(()=>expect(apiRequest).toHaveBeenCalledWith('/api/applications/10/requirements/PSA',expect.objectContaining({method:'PUT',body:JSON.stringify({status:'submitted',notes:'Original inspected',version:0})})));
    await screen.findByText('Document verification saved.');
  });
  it('keeps unsaved edits visible after a version conflict',async()=>{
    const original=apiRequest.getMockImplementation();apiRequest.mockImplementation((path,options)=>options?.method==='PUT'?Promise.reject(new Error('Record changed; reload before saving.')):original(path,options));
    show('requirements');await screen.findByLabelText('Verification notes: PSA');
    fireEvent.change(screen.getByLabelText('Verification notes: PSA'),{target:{value:'Keep my verification note'}});fireEvent.click(screen.getByRole('button',{name:'Save PSA'}));
    await screen.findByRole('alert');expect(screen.getByLabelText('Verification notes: PSA')).toHaveValue('Keep my verification note');
  });
  it('preserves another document’s draft while refreshing a saved item',async()=>{
    detail.checklist.items=[credential,{...credential,code:'FORM137',name:'Report Card'}];
    const original=apiRequest.getMockImplementation();apiRequest.mockImplementation((path,options)=>{
      if(options?.method==='PUT'){
        detail={...detail,checklist:{...detail.checklist,items:detail.checklist.items.map(item=>item.code==='PSA'?{...item,version:1,status:'submitted'}:item)}};
        return Promise.resolve(detail.checklist);
      }
      return original(path,options);
    });
    show('requirements');await screen.findByLabelText('Verification notes: FORM137');
    fireEvent.change(screen.getByLabelText('Verification notes: FORM137'),{target:{value:'Keep this separate draft'}});
    fireEvent.change(screen.getByLabelText('Status: PSA'),{target:{value:'submitted'}});
    fireEvent.click(screen.getByRole('button',{name:'Save PSA'}));
    await screen.findByText('Document verification saved.');
    await waitFor(()=>expect(screen.getByLabelText('Status: PSA')).toHaveValue('submitted'));
    expect(screen.getByLabelText('Verification notes: FORM137')).toHaveValue('Keep this separate draft');
  });
  it('opens the mockup follow-up dialog with scoped student and guardian contacts',async()=>{
    detail.checklist.studentId=7;detail.application.phone='09123456789';
    const original=apiRequest.getMockImplementation();apiRequest.mockImplementation((path,options)=>path==='/api/students/7'?Promise.resolve({guardians:[{full_name:'Maria Santos',relationship:'Mother',phone:'09987654321'}]}):original(path,options));
    show('requirements');await screen.findByRole('button',{name:'Contact details for follow-up'});
    fireEvent.click(screen.getByRole('button',{name:'Contact details for follow-up'}));
    await screen.findByText('Maria Santos');expect(screen.getByRole('dialog')).toHaveTextContent('09123456789');expect(screen.getByRole('dialog')).toHaveTextContent('09987654321');
    fireEvent.click(screen.getByRole('button',{name:'Close contact details'}));expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(apiRequest.mock.calls.every(([,options])=>!options?.method||options.method==='GET')).toBe(true);
  });
  it('submits completeness filters',async()=>{
    show('requirements');await screen.findByText('Credential checklist');
    fireEvent.change(screen.getByLabelText('Document completeness'),{target:{value:'Incomplete'}});fireEvent.click(screen.getByRole('button',{name:'Apply filters'}));
    await waitFor(()=>expect(apiRequest.mock.calls.some(([path])=>path.includes('requirementsStatus=Incomplete'))).toBe(true));
  });
  it('shows attributed history and renders verification notes as plain text',async()=>{
    const original=apiRequest.getMockImplementation();apiRequest.mockImplementation((path,options)=>path.includes('/requirements/audit')?Promise.resolve({items:[{id:1,requirementCode:'PSA',oldStatus:'pending',newStatus:'submitted',actorName:'Registrar Ana',occurredAt:'2026-06-01T08:00:00Z',newNotes:'<script>example</script>'}],total:1}):original(path,options));
    show('requirements');await screen.findByText('<script>example</script>');expect(screen.getByText(/Registrar Ana/)).toBeInTheDocument();expect(document.querySelector('script')).toBeNull();
  });
});
describe('attendance and confirmation UI',()=>{
  it('blocks premature confirmation and sends the saved date version',async()=>{
    detail.attendance=[{date:'2026-06-01',status:'pending',version:4}];show('attendance');await screen.findByLabelText('Attendance date');
    expect(screen.getByRole('button',{name:'Confirm enrollment'})).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Attendance date'),{target:{value:'2026-06-01'}});fireEvent.change(screen.getByLabelText('Attendance status'),{target:{value:'present'}});fireEvent.click(screen.getByRole('button',{name:'Save attendance'}));
    await waitFor(()=>expect(apiRequest).toHaveBeenCalledWith('/api/applications/10/attendance/2026-06-01',expect.objectContaining({method:'PUT',body:JSON.stringify({status:'present',version:4})})));
  });
  it('requires explicit confirmation and sends the application version',async()=>{
    detail.canConfirm=true;detail.blockers=[];detail.application.version=7;show('attendance');await screen.findByRole('button',{name:'Confirm enrollment'});fireEvent.click(screen.getByRole('button',{name:'Confirm enrollment'}));
    expect(screen.getByRole('dialog')).toBeInTheDocument();expect(apiRequest.mock.calls.some(([,options])=>options?.method==='POST')).toBe(false);fireEvent.click(screen.getByRole('button',{name:'Confirm',exact:true}));
    await waitFor(()=>expect(apiRequest).toHaveBeenCalledWith('/api/applications/10/confirm-enrollment',expect.objectContaining({method:'POST',body:JSON.stringify({version:7})})));
  });
});
describe('section configuration and allocation',()=>{
  it('creates a section using scoped numeric parent IDs',async()=>{
    render(<MemoryRouter><SectionConfiguration/></MemoryRouter>);await screen.findByLabelText('Academic term');
    await waitFor(()=>expect(screen.getByLabelText('Academic term').options).toHaveLength(2));
    for(const [label,value] of [['Campus','1'],['Academic term','2'],['Program / strand','3'],['Section name','STEM B'],['Grade level','11'],['Room','205'],['Maximum seats','35']])fireEvent.change(within(screen.getByRole('button',{name:'Create section'}).closest('form')).getByLabelText(label,{exact:true}),{target:{value}});
    fireEvent.click(screen.getByRole('button',{name:'Create section'}));
    await waitFor(()=>expect(apiRequest).toHaveBeenCalledWith('/api/sections',expect.objectContaining({method:'POST',body:expect.any(String)})));
    const [,options]=apiRequest.mock.calls.find(([,options])=>options?.method==='POST');expect(JSON.parse(options.body)).toMatchObject({campusId:1,termId:2,programId:3,name:'STEM B',capacity:35,adviserId:null});
  });
  it('disables full sections and sends the enrollment version',async()=>{
    detail.enrollment={id:20,status:'enrolled',version:3,sectionId:null};sections=[section,{...section,id:31,name:'STEM B',occupied:40,available:0}];show('allocation');await screen.findByText('STEM B');
    const buttons=screen.getAllByRole('button',{name:'Assign to this section'});expect(buttons[1]).toBeDisabled();fireEvent.click(buttons[0]);fireEvent.click(screen.getByRole('button',{name:'Confirm',exact:true}));
    await waitFor(()=>expect(apiRequest).toHaveBeenCalledWith('/api/enrollments/20/section',expect.objectContaining({method:'PUT',body:JSON.stringify({sectionId:30,version:3})})));
  });
  it('does not offer assignment to unconfirmed applicants',async()=>{
    show('allocation');await screen.findByText(/Confirm enrollment before assigning a section/);expect(screen.queryByRole('button',{name:'Assign to this section'})).not.toBeInTheDocument();
  });
});
