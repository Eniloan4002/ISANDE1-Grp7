import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {MemoryRouter} from 'react-router';
import StaffAccounts from '../StaffAccounts.jsx';
import {apiRequest} from '../api.js';
vi.mock('../api.js',()=>({apiRequest:vi.fn()}));
vi.mock('../auth/AuthContext.jsx',()=>({useAuth:()=>({user:{userId:1}})}));
beforeEach(()=>{apiRequest.mockImplementation(async(path,options={})=>options.method?{}:path.endsWith('/reference-data')?{roles:[{code:'ROLE_REGISTRAR',name:'Registrar'},{code:'ROLE_TEACHER',name:'Teacher'}],campuses:[{id:2,name:'Iligan Main'}]}:{items:[],total:0});});
afterEach(()=>{cleanup();vi.clearAllMocks();});
function show(){render(<MemoryRouter><StaffAccounts/></MemoryRouter>);}
it('requires campus selection and sends the chosen role with a write-only password',async()=>{
 show();await screen.findByLabelText('Iligan Main');expect(screen.getByRole('button',{name:'Create staff account'})).toBeDisabled();
 for(const [label,value] of [['Username','teacher_one'],['Full name','Teacher One'],['Email','teacher@example.invalid'],['Initial password','ChosenPassword123!'],['Staff role','ROLE_TEACHER']])fireEvent.change(screen.getByLabelText(label,{exact:true}),{target:{value}});
 fireEvent.click(screen.getByLabelText('Iligan Main'));fireEvent.click(screen.getByRole('button',{name:'Create staff account'}));
 await waitFor(()=>expect(apiRequest.mock.calls.some(([,options])=>options?.method==='POST')).toBe(true));
 const [,options]=apiRequest.mock.calls.find(([,options])=>options?.method==='POST');expect(JSON.parse(options.body)).toMatchObject({username:'teacher_one',role:'ROLE_TEACHER',campusIds:[2],password:'ChosenPassword123!'});
 await screen.findByRole('status');expect(screen.getByLabelText('Initial password')).toHaveValue('');
});
it('retains the account form and exposes a retryable API error',async()=>{
 const original=apiRequest.getMockImplementation();apiRequest.mockImplementation((path,options)=>options?.method==='POST'?Promise.reject(new Error('Username already in use.')):original(path,options));
 show();await screen.findByLabelText('Iligan Main');
 for(const [label,value] of [['Username','existing_teacher'],['Full name','Teacher One'],['Email','teacher@example.invalid'],['Initial password','ChosenPassword123!']])fireEvent.change(screen.getByLabelText(label,{exact:true}),{target:{value}});
 fireEvent.click(screen.getByLabelText('Iligan Main'));fireEvent.click(screen.getByRole('button',{name:'Create staff account'}));await screen.findByRole('alert');expect(screen.getByLabelText('Username')).toHaveValue('existing_teacher');
});
