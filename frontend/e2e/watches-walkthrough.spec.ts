import { test, expect } from '@playwright/test';
import path from 'path';

const shots = path.resolve(__dirname, '../../docs/screenshots');
const shot = (name: string) => path.join(shots, name);

/**
 * Captures the User Watch & Reconciliation feature end-to-end against the running stack.
 * Prereq: full stack up (docker infra + backend + frontend), and at least one watch
 * ("tellers") already present with recorded members/history from prior runs.
 */
test('watches walkthrough with screenshots', async ({ page }) => {
  // Login via OIDC
  await page.goto('/');
  await page.getByRole('button', { name: 'Login' }).click();
  await page.waitForURL(/app\.localtest\.me:8082\/realms\/app\/protocol\/openid-connect\/auth/);
  await page.locator('#username').fill('admin');
  await page.locator('#password').fill('admin');
  await page.locator('#kc-login').click();
  await expect(page.getByRole('heading', { name: 'Connections' })).toBeVisible();

  // 1. Watches list
  await page.getByRole('link', { name: /Watches/ }).click();
  await expect(page.getByRole('heading', { name: 'Watches' })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'tellers', exact: true })).toBeVisible(); // wait for async load
  await page.screenshot({ path: shot('watch-01-list.png'), fullPage: true });

  // 2. New watch editor — LIST mode with the source user-picker loaded
  await page.getByRole('button', { name: /New watch/ }).click();
  const selects = page.locator('watch-editor select');
  await selects.nth(1).selectOption('UBS');   // source (nth 0 = type, 1 = source, 2 = target)
  await selects.nth(2).selectOption('CS');     // target
  await expect(page.getByText('alice')).toBeVisible();   // picker populated from source
  await page.getByLabel('alice').check();
  await page.getByLabel('carla').check();
  await page.screenshot({ path: shot('watch-02-editor-list.png'), fullPage: true });

  // 3. Editor — FILTER mode + policy / run-mode controls
  await page.locator('watch-editor select[name="selmode"]').selectOption('FILTER');
  await page.locator('watch-editor input[name="term"]').fill('teller');
  await page.locator('watch-editor select[name="ondel"]').selectOption('DISABLE');
  await page.locator('watch-editor select[name="runmode"]').selectOption('REPORT_ONLY');
  await page.screenshot({ path: shot('watch-03-editor-filter.png'), fullPage: true });

  // Close the editor without saving
  await page.getByRole('button', { name: 'Cancel' }).click();

  // 4. Members snapshot for the existing "tellers" watch (governed-identity audit view)
  await page.getByRole('button', { name: 'Members' }).first().click();
  await expect(page.getByRole('heading', { name: /Members — tellers/ })).toBeVisible();
  await page.screenshot({ path: shot('watch-04-members.png'), fullPage: true });

  // 5. History — the immutable audit trail incl. the disabled (⊘) figure and REPORT/ENFORCE runs
  await page.getByRole('link', { name: /History/ }).click();
  await expect(page.getByRole('heading', { name: 'History' })).toBeVisible();
  await expect(page.getByText('watch:tellers').first()).toBeVisible(); // wait for async audit load
  await page.screenshot({ path: shot('watch-05-history.png'), fullPage: true });
});
