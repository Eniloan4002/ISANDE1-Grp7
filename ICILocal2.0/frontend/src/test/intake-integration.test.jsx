import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {MemoryRouter} from 'react-router';
import {EnrollmentWorkspace} from '../enrollment/EnrollmentScreens.jsx';
import RequirementsScreen from '../enrollment/RequirementsScreen.jsx';
import {apiRequest} from '../api.js';
vi.mock('../api.js',()=>({apiRequest:vi.fn()}));
const catalogs={campuses:[{id:1,name:'Iligan Main'}],terms:[{id:2,name:'Semester 1',academicYear:'2026-2027'}],programs:[{id:3,code:'STEM',name:'STEM',category:'SHS'}],vouchers:[{code:'QVR',name:'QVR'}]};
const row={id:10,applicationNumber:'APP-10',firstName:'Ana',lastName:'Santos',name:'Ana Santos',status:'submitted',requirementsStatus:'Pending',program:'STEM'};
let data;
beforeEach(()=>{
 HTMLDialogElement.prototype.showModal=function(){this.open=true;};HTMLDialogElement.prototype.close=function(){this.open=false;};
 data={...row,profile:{guardians:[{fullName:'Maria Santos',relationship:'mother',phone:'09912345678'}]},workflow:{application:{id:10,name:'Ana Santos'},checklist:{applicationId:10,studentId:null,summary:{overallStatus:'Pending',submitted:0,mandatoryTotal:1,missing:0,invalid:0},items:[{code:'PSA',name:'PSA Birth Certificate',mandatory:true,status:'pending',version:0}]}}};
 apiRequest.mockImplementation(async(path,options={})=>{
  if(options.method==='POST')return data;
  if(path.endsWith('/reference-data'))return catalogs;
  if(path==='/api/intake/statistics')return {total:1,submitted:1,preEnlisted:0,duplicates:0,enrolled:0};
  if(path.startsWith('/api/enrollment/applications?'))return {items:[row],total:1};
  if(path==='/api/intake/applications/10')return data;
  if(path.includes('/requirements/audit'))return {items:[],total:0};
  if(options.method==='PUT')return {};
  throw new Error(`Unexpected ${path}`);
 });
});
afterEach(()=>{cleanup();vi.clearAllMocks();});
function show(screenName){render(<MemoryRouter initialEntries={['/enrollment/intake']}><EnrollmentWorkspace screen={screenName}/></MemoryRouter>);}
async function fillIntake(){
 await screen.findByLabelText(/First name/);
 for(const [label,value] of [[/First name/,'Ana'],[/Last name/,'Santos'],[/Date of birth/,'2009-01-01'],[/Applicant category/,'new'],[/Preferred campus/,'1'],[/Academic term/,'2'],[/^Program/,'SHS'],[/^Track/,'ACADEMIC'],[/Strand \/ specialization/,'STEM'],[/Grade level/,'11'],[/Voucher classification/,'QVR']])fireEvent.change(screen.getByLabelText(label),{target:{value}});
}
it('submits Pauline’s form with numeric catalog IDs, optional LRN and multiple guardians',async()=>{
 show('intake');await fillIntake();fireEvent.change(screen.getByLabelText('Full name'),{target:{value:'Maria Santos'}});fireEvent.change(screen.getByLabelText('Relationship'),{target:{value:'mother'}});
 fireEvent.click(screen.getByRole('button',{name:'Add another parent / guardian'}));
 fireEvent.change(screen.getAllByLabelText('Full name')[1],{target:{value:'Juan Santos'}});fireEvent.change(screen.getAllByLabelText('Relationship')[1],{target:{value:'father'}});
 fireEvent.click(screen.getByRole('button',{name:'Add applicant to queue'}));
 await waitFor(()=>expect(apiRequest.mock.calls.some(([path,options])=>path==='/api/intake/applications'&&options?.method==='POST')).toBe(true));
 const [,options]=apiRequest.mock.calls.find(([path,options])=>path==='/api/intake/applications'&&options?.method==='POST');
 expect(JSON.parse(options.body)).toMatchObject({campusId:1,termId:2,programId:3,category:'new',track:'ACADEMIC',lrn:null,gradeLevel:'11',guardians:[{fullName:'Maria Santos'},{fullName:'Juan Santos'}]});
});
it('keeps the intake draft when the service rejects a submission',async()=>{
 const original=apiRequest.getMockImplementation();apiRequest.mockImplementation((path,options)=>options?.method==='POST'?Promise.reject(new Error('Service unavailable; retry.')):original(path,options));
 show('intake');await fillIntake();fireEvent.click(screen.getByRole('button',{name:'Add applicant to queue'}));await screen.findByRole('alert');expect(screen.getByLabelText(/First name/)).toHaveValue('Ana');expect(screen.getByRole('button',{name:'Add applicant to queue'})).toBeEnabled();
});
it('loads the persistent queue and applies server-side campus filters',async()=>{
 show('queue');await screen.findByText('APP-10');fireEvent.change(screen.getByLabelText('Campus'),{target:{value:'1'}});
 await waitFor(()=>expect(apiRequest.mock.calls.some(([path])=>path.includes('/api/enrollment/applications?')&&path.includes('campusId=1'))).toBe(true));
});
it('uses the persisted checklist in Pauline’s dialog and preserves notes after a conflict',async()=>{
 render(<MemoryRouter initialEntries={['/enrollment/requirements?applicationId=10']}><RequirementsScreen/></MemoryRouter>);
 await screen.findByLabelText('Verification notes: PSA');const original=apiRequest.getMockImplementation();apiRequest.mockImplementation((path,options)=>options?.method==='PUT'?Promise.reject(new Error('Version conflict; reload.')):original(path,options));
 fireEvent.change(screen.getByLabelText('Verification notes: PSA'),{target:{value:'Keep my note'}});fireEvent.click(screen.getByRole('button',{name:'Save PSA'}));await screen.findByRole('alert');expect(screen.getByLabelText('Verification notes: PSA')).toHaveValue('Keep my note');
});
it('shows intake guardian contacts before a student record exists and does not simulate sending',async()=>{
 render(<MemoryRouter initialEntries={['/enrollment/requirements?applicationId=10']}><RequirementsScreen/></MemoryRouter>);
 await screen.findByRole('button',{name:'Contact details for follow-up'});fireEvent.click(screen.getByRole('button',{name:'Contact details for follow-up'}));await screen.findByText('Maria Santos');expect(screen.queryByRole('button',{name:/Send/})).not.toBeInTheDocument();
 expect(apiRequest.mock.calls.every(([,options])=>!options?.method||options.method==='GET')).toBe(true);
});
