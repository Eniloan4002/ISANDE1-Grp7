import { test, expect } from '@playwright/test';

test('navigate reserved routes and handle unknown paths', async ({page}) => {
  test.skip(!process.env.ICI_LIVE_API,'Requires the live authentication fixture.');
  await signIn(page);
  await expect(page.getByRole('heading',{name:'Academic setup'})).toBeVisible();
  await page.screenshot({path:'test-results/workspace-desktop.png',fullPage:true});
  await page.goto('/administration/users');
  await expect(page.getByRole('heading',{name:'User Accounts'})).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading',{name:'User Accounts'})).toBeVisible();
  await page.goto('/not-a-page');
  await expect(page.getByRole('heading',{name:'Page not found'})).toBeVisible();
});
test('dialog, keyboard dismissal and notification work', async ({page}) => {
  test.skip(!process.env.ICI_LIVE_API,'Requires the live authentication fixture.');
  await signIn(page);
  await page.goto('/design-system');
  await page.getByRole('button',{name:'Open dialog'}).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button',{name:'Open dialog'})).toBeFocused();
  await page.getByRole('button',{name:'Show notification'}).click();
  await expect(page.getByText('Example action completed.')).toBeVisible();
});
test('mobile navigation is usable without horizontal overflow', async ({page}) => {
  test.skip(!process.env.ICI_LIVE_API,'Requires the live authentication fixture.');
  await page.setViewportSize({width:390,height:844});
  await signIn(page);
  await expect(page.getByRole('button',{name:'Toggle navigation'})).toHaveAttribute('aria-expanded','false');
  await page.getByRole('button',{name:'Toggle navigation'}).click();
  await page.getByRole('link',{name:'User Accounts',exact:true}).click();
  await expect(page.getByRole('heading',{name:'User Accounts'})).toBeVisible();
  await expect(page.getByRole('button',{name:'Toggle navigation'})).toHaveAttribute('aria-expanded','false');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({path:'test-results/workspace-mobile.png',fullPage:true,animations:'disabled'});
});
async function signIn(page){
  await page.goto('/setup');
  await page.getByLabel('Username',{exact:true}).fill(process.env.ICI_TEST_USERNAME||'browser-admin');
  await page.getByLabel('Password',{exact:true}).fill(process.env.ICI_TEST_PASSWORD||'Browser-test-password-123!');
  await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Academic setup'})).toBeVisible();
}
