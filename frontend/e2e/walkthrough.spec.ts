import { test, expect } from '@playwright/test';
import path from 'path';

const shots = path.resolve(__dirname, '../../docs/screenshots');
const shot = (name: string) => path.join(shots, name);

test('happy-path walkthrough with screenshots', async ({ page }) => {
  // 1. Landing / login page
  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Login' })).toBeVisible();
  await page.screenshot({ path: shot('01-login.png'), fullPage: true });

  // Kick off OIDC → Keycloak login form
  await page.getByRole('button', { name: 'Login' }).click();
  await page.waitForURL(/app\.localtest\.me:8082\/realms\/app\/protocol\/openid-connect\/auth/);
  await page.locator('#username').fill('admin');
  await page.locator('#password').fill('admin');
  await page.locator('#kc-login').click();

  // 2. Connections page (default route after auth)
  await expect(page.getByRole('heading', { name: 'Connections' })).toBeVisible();
  await expect(page.getByText('UBS')).toBeVisible();
  await page.screenshot({ path: shot('02-connections.png'), fullPage: true });

  // 3. Test a connection
  await page.getByRole('button', { name: 'Test' }).first().click();
  await expect(page.getByText(/auth OK|LDAP bind OK|✓/).first()).toBeVisible();
  await page.screenshot({ path: shot('03-test-connection.png'), fullPage: true });

  // 4. Keycloak → KC sync: pick UBS → CS, preview (dry-run)
  await page.getByRole('link', { name: /Keycloak → KC/ }).click();
  await expect(page.getByRole('heading', { name: /Keycloak → Keycloak sync/ })).toBeVisible();
  const selects = page.locator('select');
  await selects.nth(0).selectOption({ label: 'UBS (KEYCLOAK)' });
  await selects.nth(1).selectOption({ label: 'CS' });
  await page.getByRole('checkbox', { name: /Include roles/ }).check();
  await page.getByRole('button', { name: /Preview/ }).click();
  await expect(page.getByText('Dry-run preview')).toBeVisible();
  await page.screenshot({ path: shot('04-sync-preview.png'), fullPage: true });

  // 5. Confirm & run
  await page.getByRole('button', { name: /Confirm/ }).click();
  await expect(page.locator('.alert')).toBeVisible();
  await page.screenshot({ path: shot('05-sync-result.png'), fullPage: true });

  // 6. History
  await page.getByRole('link', { name: /History/ }).click();
  await expect(page.getByRole('heading', { name: 'History' })).toBeVisible();
  await page.screenshot({ path: shot('06-history.png'), fullPage: true });
});
