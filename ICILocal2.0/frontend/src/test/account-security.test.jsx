import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {MemoryRouter} from 'react-router';
import AccountSecurity from '../AccountSecurity.jsx';
const auth=vi.hoisted(()=>({user:{passwordChangeRequired:true},changePassword:vi.fn()}));
vi.mock('../auth/AuthContext.jsx',()=>({useAuth:()=>auth}));
beforeEach(()=>auth.changePassword.mockResolvedValue(undefined));afterEach(()=>{cleanup();vi.clearAllMocks();});
function fill(){render(<MemoryRouter><AccountSecurity/></MemoryRouter>);fireEvent.change(screen.getByLabelText('Current password',{exact:true}),{target:{value:'TemporaryCurrent2026'}});fireEvent.change(screen.getByLabelText('New password',{exact:true}),{target:{value:'PrivateNewPassword2026'}});}
it('requires matching confirmation before calling the credential-change service',()=>{fill();fireEvent.change(screen.getByLabelText('Confirm new password'),{target:{value:'DifferentPassword2026'}});fireEvent.click(screen.getByRole('button',{name:'Change password',exact:true}));expect(screen.getByRole('alert')).toHaveTextContent('do not match');expect(auth.changePassword).not.toHaveBeenCalled();});
it('submits the current and new passwords while keeping all credential fields masked',async()=>{fill();fireEvent.change(screen.getByLabelText('Confirm new password'),{target:{value:'PrivateNewPassword2026'}});fireEvent.click(screen.getByRole('button',{name:'Change password',exact:true}));await waitFor(()=>expect(auth.changePassword).toHaveBeenCalledWith('TemporaryCurrent2026','PrivateNewPassword2026'));expect(screen.getByLabelText('New password',{exact:true})).toHaveAttribute('type','password');});
