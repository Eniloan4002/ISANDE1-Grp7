import {test,expect} from '@playwright/test';

test('administrator manages a versioned personnel account through its full lifecycle',async({page})=>{
 test.skip(!process.env.ICI_LIVE_API,'Requires a disposable backend.');test.setTimeout(120000);
 await page.route('https://fonts.googleapis.com/**',r=>r.abort());await page.route('https://fonts.gstatic.com/**',r=>r.abort());
 await page.goto('/administration/users');await page.getByLabel('Username',{exact:true}).fill(process.env.ICI_TEST_USERNAME||'browser-admin');await page.getByLabel('Password',{exact:true}).fill(process.env.ICI_TEST_PASSWORD||'Browser-test-password-123!');await page.getByRole('button',{name:'Sign in',exact:true}).click();
 await expect(page.getByRole('heading',{name:'User Accounts',exact:true})).toBeVisible();
 const suffix=Date.now(),name=`personnel_${suffix}`,renamed=name+'_renamed';
 await page.getByLabel('Username',{exact:true}).fill(name);await page.getByLabel('Full name',{exact:true}).fill('Browser Personnel');await page.getByLabel('Email',{exact:true}).fill(`${name}@example.invalid`);await page.getByLabel('Initial password').fill('Personnel-Password-123!');await page.getByLabel('Staff role').selectOption('ROLE_TEACHER');await page.getByLabel('Iligan Main',{exact:true}).check();await page.getByRole('button',{name:'Create staff account',exact:true}).click();await expect(page.getByText(/Staff account created/)).toBeVisible();
 await page.getByLabel('Search staff').fill(name);await page.getByRole('button',{name:`Edit ${name}`,exact:true}).click();await page.getByLabel('Sign-in username').fill(renamed);await page.getByLabel('Change reason (exclude passwords)').fill('Verified username update');await page.getByRole('button',{name:'Review account change'}).click();await page.getByRole('button',{name:'Confirm',exact:true}).click();await expect(page.getByRole('button',{name:`Edit ${renamed}`,exact:true})).toBeVisible();
 await page.getByRole('button',{name:`Deactivate ${renamed}`,exact:true}).click();await expect(page.getByRole('button',{name:`Activate ${renamed}`,exact:true})).toBeVisible();
 await page.getByRole('button',{name:`Activate ${renamed}`,exact:true}).click();await expect(page.getByRole('button',{name:`Deactivate ${renamed}`,exact:true})).toBeVisible();
 await page.getByRole('button',{name:`Recover ${renamed}`,exact:true}).click();await page.getByLabel('Temporary password',{exact:true}).fill('Personnel-Recovery-456!');await page.getByLabel('I verified this staff member’s identity').check();await page.getByLabel('Change reason (exclude passwords)').fill('Verified personnel identity');await page.getByRole('button',{name:'Review account change'}).click();await page.getByRole('button',{name:'Confirm',exact:true}).click();await expect(page.getByText(/Temporary password set/)).toBeVisible();
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
