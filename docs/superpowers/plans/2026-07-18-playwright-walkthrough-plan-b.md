# Playwright Walkthrough + README Screenshots (Plan B of B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Playwright end-to-end test that drives the running app through the happy path, capturing a screenshot per step into `docs/screenshots/`, embedded step-by-step in the README as living documentation.

**Architecture:** Playwright is a dev dependency in `frontend/`. A single spec (`e2e/walkthrough.spec.ts`) runs against the **already-running** stack (Keycloak + Vault + backend + frontend), logs in through the real Keycloak form, walks Connections → Test → Keycloak→KC dry-run → Confirm & run → History, and writes six PNGs to `../docs/screenshots/`. The README gains a **Walkthrough** section embedding them + a **Running the E2E test** subsection.

**Tech Stack:** Playwright (`@playwright/test`), Chromium, the running app on `http://localhost:4200`.

## Global Constraints

- The E2E test requires the **full live stack** up (docker keycloak/vault + backend on 9090 + frontend on 4200). It is **not** part of `mvn test` / `npm test` (unit suites).
- Login credentials `admin` / `admin` against `app.localtest.me:8082`.
- Screenshots are committed to `docs/screenshots/` so the README renders without re-running.
- Prefer role/text-based Playwright locators over brittle CSS.

---

## File Structure

```
frontend/
  package.json                 # + @playwright/test devDependency, e2e script
  playwright.config.ts         # testDir e2e, chromium, no baseURL dependency on webServer
  e2e/walkthrough.spec.ts      # the screenshot walkthrough
docs/
  screenshots/                 # 01-login.png … 06-history.png (committed)
  README.md                    # + Walkthrough section + Running the E2E test
.gitignore / frontend/.gitignore  # ignore Playwright artifacts (test-results, playwright-report)
```

---

## Task 1: Add Playwright + config

**Files:**
- Modify: `frontend/package.json`
- Create: `frontend/playwright.config.ts`
- Modify: `frontend/.gitignore`

**Interfaces:**
- Produces: `@playwright/test` installed; Chromium browser installed; an `e2e` npm script; a config pointing at `e2e/` with `baseURL http://localhost:4200`, headless Chromium, generous navigation timeout (OIDC redirects).

- [ ] **Step 1: Install Playwright + Chromium**

Run:
```bash
cd frontend
npm install -D @playwright/test
npx playwright install chromium
```
Expected: dependency added; Chromium downloaded.

- [ ] **Step 2: Add the e2e script**

In `frontend/package.json` `scripts`, add:
```json
"e2e": "playwright test"
```

- [ ] **Step 3: Write the config**

`frontend/playwright.config.ts`:
```ts
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  retries: 0,
  use: {
    baseURL: 'http://localhost:4200',
    headless: true,
    navigationTimeout: 30_000,
    viewport: { width: 1280, height: 800 },
  },
});
```

- [ ] **Step 4: Ignore Playwright artifacts**

Append to `frontend/.gitignore`:
```
/test-results/
/playwright-report/
/.playwright/
```

- [ ] **Step 5: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/playwright.config.ts frontend/.gitignore
git commit -m "test(e2e): add Playwright + config"
```

## Task 2: Write the walkthrough spec

**Files:**
- Create: `frontend/e2e/walkthrough.spec.ts`

**Interfaces:**
- Consumes: the running app.
- Produces: a spec that writes `docs/screenshots/01-login.png` … `06-history.png`. Steps: login page → Keycloak form submit → Connections → Test UBS → sync dry-run preview → confirm result → History.

- [ ] **Step 1: Write the spec**

`frontend/e2e/walkthrough.spec.ts`:
```ts
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
```
Notes: selectors are text/role based; the Keycloak form uses the theme's standard `#username`, `#password`, `#kc-login` ids. If a locator needs adjustment when first run against the live UI (Task 3), fix it there.

- [ ] **Step 2: Commit the spec**

```bash
git add frontend/e2e/walkthrough.spec.ts
git commit -m "test(e2e): happy-path screenshot walkthrough spec"
```

## Task 3: Run against the live stack + commit screenshots

**Files:**
- Create: `docs/screenshots/01-login.png` … `06-history.png` (generated)

- [ ] **Step 1: Ensure the full stack is up**

Run:
```bash
cd /Users/macbook/Desktop/keycloakcomm
docker compose up -d postgres-ubs postgres-cs postgres-app keycloak-ubs keycloak-cs keycloak-app vault
export JAVA_HOME=/Users/macbook/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home
(cd backend && mvn -q spring-boot:run &) ; sleep 40
(cd frontend && npm start &) ; sleep 20
curl -s -o /dev/null -w "front:%{http_code} back:" http://localhost:4200; curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9090/actuator/health
```
Expected: `front:200 back:200`.

- [ ] **Step 2: Run the walkthrough**

Run:
```bash
mkdir -p docs/screenshots
cd frontend && npx playwright test 2>&1 | tail -20
ls -1 ../docs/screenshots
```
Expected: test passes; `01-login.png` … `06-history.png` present. If a locator fails, read the Playwright error, adjust `walkthrough.spec.ts` (Task 2) to match the live DOM, and re-run.

- [ ] **Step 3: Commit the screenshots**

```bash
cd /Users/macbook/Desktop/keycloakcomm
git add docs/screenshots
git commit -m "docs: walkthrough screenshots (E2E-generated)"
```

## Task 4: README Walkthrough section

**Files:**
- Modify: `docs/README.md`

- [ ] **Step 1: Add the Walkthrough + E2E sections**

Insert a **## Walkthrough** section into `docs/README.md` (after "Using the tool") embedding the six screenshots with a one-line caption each:
```markdown
## Walkthrough

A step-by-step run of the tool (screenshots generated by the Playwright E2E test).

1. **Login** — OIDC login against the `app` realm.
   ![Login](screenshots/01-login.png)
2. **Connections** — seeded UBS/CS/Samba profiles.
   ![Connections](screenshots/02-connections.png)
3. **Test connection** — validates reachability/auth via the service account.
   ![Test connection](screenshots/03-test-connection.png)
4. **Dry-run preview** — exactly what a sync would change, before any write.
   ![Sync preview](screenshots/04-sync-preview.png)
5. **Confirm & run** — the executed sync result.
   ![Sync result](screenshots/05-sync-result.png)
6. **History** — the audit log of every run.
   ![History](screenshots/06-history.png)

### Running the E2E test

The walkthrough is a Playwright test that drives the **running** stack and regenerates the screenshots:

​```bash
# with docker keycloak/vault + backend (9090) + frontend (4200) all up:
cd frontend && npx playwright install chromium   # first time only
npx playwright test
​```

It is an integration/E2E test — separate from the unit suites (`mvn test`, `npm test`).
```
(Remove the zero-width space before the code fences — they are only present here to keep this plan's own fences intact.)

- [ ] **Step 2: Commit**

```bash
git add docs/README.md
git commit -m "docs: README walkthrough section with screenshots + E2E instructions"
```

---

## Self-Review Notes

- **Spec coverage:** Playwright dev-dep + config (Task 1), full happy-path spec producing 6 screenshots (Task 2), live run generating + committing PNGs (Task 3), README Walkthrough + E2E instructions (Task 4). Maps to spec §5.
- **Placeholder scan:** none (the README code fences use a documented zero-width-space escape only within this plan).
- **Locator resilience:** selectors are role/text based; Task 3 Step 2 explicitly allows fixing a locator against the live DOM on first run.
- **Terminal state:** after Task 4, invoke `superpowers:finishing-a-development-branch` to complete `feat/scheduled-sync-e2e` (both plans A + B).
