import { test, expect } from '@playwright/test';

test('navigate modules, display deferred policy and handle unknown paths', async ({page}) => {
  await page.goto('/');
  await expect(page.getByRole('heading',{name:'A foundation for student services'})).toBeVisible();
  await page.screenshot({path:'test-results/workspace-desktop.png',fullPage:true});
  await page.getByRole('link',{name:'Reporting & monitoring',exact:true}).click();
  await expect(page.getByText('Honor eligibility is deferred',{exact:false})).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading',{name:'Reporting & monitoring'})).toBeVisible();
  await page.goto('/not-a-page');
  await expect(page.getByRole('heading',{name:'Page not found'})).toBeVisible();
});
test('dialog, keyboard dismissal and notification work', async ({page}) => {
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
  await page.setViewportSize({width:390,height:844});
  await page.goto('/');
  await page.getByRole('button',{name:'Menu',exact:true}).click();
  await page.getByRole('link',{name:'Academic records',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Academic records'})).toBeVisible();
  await expect(page.getByRole('button',{name:'Menu',exact:true})).toHaveAttribute('aria-expanded','false');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({path:'test-results/workspace-mobile.png',fullPage:true});
});
