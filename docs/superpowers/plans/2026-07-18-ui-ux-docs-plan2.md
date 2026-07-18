# UI/UX Redesign + Docs (Plan 2 of 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Angular UI on Bootstrap as a left-sidebar console — Connections manager (with test-connection + Samba pre-fill), two sync flows with hybrid help + dry-run preview → confirm, and a History page — then write the README, architecture, and security/audit docs.

**Architecture:** Angular 18 standalone + Angular Router. A shell (`AppComponent`) gates on OIDC auth and renders a Bootstrap sidebar + `<router-outlet>`. Routes: `/connections`, `/sync/keycloak`, `/sync/samba`, `/history`. A shared `SyncFlowComponent` drives both sync routes (differs only by endpoint + connection filter). Reusable help components deliver the hybrid inline/tooltip/example pattern. Backend endpoints are those delivered by Plan 1.

**Tech Stack:** Angular 18 (standalone, Router), Bootstrap 5 (CSS only — no JS dep), `angular-auth-oidc-client`, Karma/Jasmine.

## Global Constraints

- Angular 18 standalone components; Angular Router via `provideRouter`.
- Bootstrap 5 CSS only (added via `angular.json` styles). No Bootstrap/popper JS — tooltips/accordions use CSS + native `<details>`.
- App is reached at `http://localhost:4200`; OIDC authority `http://app.localtest.me:8082/realms/app`; API base `http://localhost:9090/api` (unchanged from prior work).
- Backend contract (Plan 1): `GET/POST/PUT/DELETE /api/connections`, `POST /api/connections/{id}/test`, `POST /api/keycloak/plan|sync`, `POST /api/samba/plan|sync`, `GET /api/audit`. Sync/plan body: `{sourceConnId, targetConnId, mode, includeRoles}`. Plan response: `{actions:[{username, action}]}` where action ∈ `CREATE|UPDATE|DELETE|SKIP`.
- `mode` ∈ `CREATE_ONLY|CREATE_UPDATE|MIRROR`. Frequent commits, TDD where a unit is testable.

---

## File Structure

```
frontend/
  angular.json                         # + bootstrap css
  package.json                         # + bootstrap
  src/styles.css                       # console theme tweaks
  src/app/
    app.config.ts                      # + provideRouter(routes)
    app.routes.ts                      # route table
    app.component.ts                   # sidebar shell + auth gate
    core/models.ts                     # + Connection, SyncPlan, SyncRun, etc.
    core/api.service.ts                # rewritten to Plan 1 endpoints
    help/help-text.component.ts        # always-on one-liner
    help/help-tooltip.component.ts     # ⓘ hover tooltip
    help/help-example.component.ts     # expandable <details> example
    help/option-help.ts                # help content for each mode/option
    connections/connections.component.ts    # list + test + delete
    connections/connection-editor.component.ts # create/edit form
    sync/sync-flow.component.ts        # shared: pickers + help + dry-run + confirm
    sync/keycloak-sync.component.ts    # wraps sync-flow (KC source filter)
    sync/samba-sync.component.ts       # wraps sync-flow (LDAP source filter)
    history/history.component.ts       # audit table
docs/
  README.md
  architecture.md
  security-audit.md
```

---

## PHASE A — Foundation: Bootstrap, router, models, API

### Task 1: Add Bootstrap + Router + rewrite models & API service

**Files:**
- Modify: `frontend/package.json`, `frontend/angular.json`
- Modify: `frontend/src/app/app.config.ts`
- Create: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/core/models.ts`
- Modify: `frontend/src/app/core/api.service.ts`
- Test: `frontend/src/app/core/api.service.spec.ts`

**Interfaces:**
- Produces:
  - Bootstrap CSS globally available.
  - `app.routes.ts` exporting `routes` (see Task 2 for the actual paths; created here as a stub importing components added later — so this task adds an empty-but-valid `routes` array and Task 2 fills it once components exist). To avoid a forward-reference, **define `routes` as `[]` here and populate it in Task 2**.
  - `models.ts`: `type SyncMode`, `type ConnectionType = 'KEYCLOAK'|'LDAP'`, `type ActionType = 'CREATE'|'UPDATE'|'DELETE'|'SKIP'`, and interfaces `Connection`, `ConnectionRequest`, `SyncRunRequest`, `PlannedAction`, `SyncPlan`, `SyncResult`, `SyncRun`.
  - `ApiService` methods: `connections()`, `createConnection(r)`, `updateConnection(id,r)`, `deleteConnection(id)`, `testConnection(id)`, `keycloakPlan(r)`, `keycloakSync(r)`, `sambaPlan(r)`, `sambaSync(r)`, `audit()`.

- [ ] **Step 1: Install Bootstrap**

Run:
```bash
cd frontend && npm install bootstrap@5
```
Expected: bootstrap added to dependencies.

- [ ] **Step 2: Register Bootstrap CSS**

In `frontend/angular.json`, under `projects.frontend.architect.build.options.styles`, change the array to:
```json
"styles": [
  "node_modules/bootstrap/dist/css/bootstrap.min.css",
  "src/styles.css"
]
```

- [ ] **Step 3: Rewrite models**

`frontend/src/app/core/models.ts`:
```ts
export type SyncMode = 'CREATE_ONLY' | 'CREATE_UPDATE' | 'MIRROR';
export type ConnectionType = 'KEYCLOAK' | 'LDAP';
export type ActionType = 'CREATE' | 'UPDATE' | 'DELETE' | 'SKIP';

export interface Connection {
  id: number; name: string; type: ConnectionType; serverUrl: string;
  realm?: string; baseDn?: string; clientId?: string; bindDn?: string;
  userSearchBase?: string; secretRef: string;
}
export interface ConnectionRequest {
  name: string; type: ConnectionType; serverUrl: string;
  realm?: string; baseDn?: string; clientId?: string; bindDn?: string;
  userSearchBase?: string; secret?: string;
}
export interface SyncRunRequest { sourceConnId: number; targetConnId: number; mode: SyncMode; includeRoles: boolean; }
export interface PlannedAction { username: string; action: ActionType; }
export interface SyncPlan { actions: PlannedAction[]; }
export interface SyncResult { created: number; updated: number; skipped: number; deleted: number; errors: string[]; }
export interface TestResult { ok: boolean; message: string; }
export interface SyncRun {
  id: number; timestamp: string; actor: string; sourceConn: string; targetConn: string;
  mode: string; includeRoles: boolean; created: number; updated: number; deleted: number;
  skipped: number; errorCount: number; status: string;
}
```

- [ ] **Step 4: Rewrite ApiService**

`frontend/src/app/core/api.service.ts`:
```ts
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Connection, ConnectionRequest, SyncRunRequest, SyncPlan, SyncResult, SyncRun, TestResult } from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  private base = 'http://localhost:9090/api';

  connections(): Observable<Connection[]> { return this.http.get<Connection[]>(`${this.base}/connections`); }
  createConnection(r: ConnectionRequest): Observable<Connection> { return this.http.post<Connection>(`${this.base}/connections`, r); }
  updateConnection(id: number, r: ConnectionRequest): Observable<Connection> { return this.http.put<Connection>(`${this.base}/connections/${id}`, r); }
  deleteConnection(id: number): Observable<void> { return this.http.delete<void>(`${this.base}/connections/${id}`); }
  testConnection(id: number): Observable<TestResult> { return this.http.post<TestResult>(`${this.base}/connections/${id}/test`, {}); }

  keycloakPlan(r: SyncRunRequest): Observable<SyncPlan> { return this.http.post<SyncPlan>(`${this.base}/keycloak/plan`, r); }
  keycloakSync(r: SyncRunRequest): Observable<SyncResult> { return this.http.post<SyncResult>(`${this.base}/keycloak/sync`, r); }
  sambaPlan(r: SyncRunRequest): Observable<SyncPlan> { return this.http.post<SyncPlan>(`${this.base}/samba/plan`, r); }
  sambaSync(r: SyncRunRequest): Observable<SyncResult> { return this.http.post<SyncResult>(`${this.base}/samba/sync`, r); }
  audit(): Observable<SyncRun[]> { return this.http.get<SyncRun[]>(`${this.base}/audit`); }
}
```

- [ ] **Step 5: Add empty routes + wire router**

`frontend/src/app/app.routes.ts`:
```ts
import { Routes } from '@angular/router';
export const routes: Routes = [];
```
In `frontend/src/app/app.config.ts`, add the router provider (keep the existing OIDC + http providers):
```ts
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
// add to providers array: provideRouter(routes),
```

- [ ] **Step 6: Write the API service test**

`frontend/src/app/core/api.service.spec.ts`:
```ts
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ApiService } from './api.service';

describe('ApiService', () => {
  let api: ApiService; let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule], providers: [ApiService] });
    api = TestBed.inject(ApiService); http = TestBed.inject(HttpTestingController);
  });

  it('posts a keycloak plan request', () => {
    api.keycloakPlan({ sourceConnId: 1, targetConnId: 2, mode: 'CREATE_UPDATE', includeRoles: true }).subscribe();
    const req = http.expectOne('http://localhost:9090/api/keycloak/plan');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.sourceConnId).toBe(1);
    req.flush({ actions: [] });
  });
});
```

- [ ] **Step 7: Build + test**

Run:
```bash
cd frontend && npm run build && npm test -- --watch=false --browsers=ChromeHeadless --include='**/api.service.spec.ts'
```
Expected: build succeeds; api.service test passes.

- [ ] **Step 8: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/angular.json frontend/src/app/core frontend/src/app/app.routes.ts frontend/src/app/app.config.ts
git commit -m "feat(ui): add Bootstrap + Router, Plan-1 models & API service"
```

### Task 2: Sidebar console shell + routes

**Files:**
- Modify: `frontend/src/app/app.component.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes: components created in Tasks 3–7 (routes reference them). To avoid a forward reference, **this task adds the routes and shell but the referenced components must exist** — so in execution order, do Tasks 3–7 first OR stub each route component. The chosen order: implement this shell with routes pointing at the real components, and run its build only after Tasks 3–7 are present. (If executing strictly top-to-bottom, create each component as an empty standalone shell in this task, then flesh out in later tasks.)
- Produces: authenticated shell with Bootstrap sidebar nav (Connections, Keycloak→KC, Samba→KC, History) + `<router-outlet>`; unauthenticated shows a centered Login card.

- [ ] **Step 1: Fill the route table**

`frontend/src/app/app.routes.ts`:
```ts
import { Routes } from '@angular/router';
import { ConnectionsComponent } from './connections/connections.component';
import { KeycloakSyncComponent } from './sync/keycloak-sync.component';
import { SambaSyncComponent } from './sync/samba-sync.component';
import { HistoryComponent } from './history/history.component';

export const routes: Routes = [
  { path: '', redirectTo: 'connections', pathMatch: 'full' },
  { path: 'connections', component: ConnectionsComponent },
  { path: 'sync/keycloak', component: KeycloakSyncComponent },
  { path: 'sync/samba', component: SambaSyncComponent },
  { path: 'history', component: HistoryComponent },
];
```

- [ ] **Step 2: Rewrite the shell**

`frontend/src/app/app.component.ts`:
```ts
import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <ng-container *ngIf="authenticated; else loginTpl">
      <div class="d-flex vh-100">
        <nav class="text-white p-3 d-flex flex-column" style="width:230px;background:#0d3b66">
          <h6 class="mb-4">🔐 KC User Sync</h6>
          <a class="nav-link text-white-50 mb-1" routerLink="/connections" routerLinkActive="text-white fw-bold">🔌 Connections</a>
          <a class="nav-link text-white-50 mb-1" routerLink="/sync/keycloak" routerLinkActive="text-white fw-bold">↔ Keycloak → KC</a>
          <a class="nav-link text-white-50 mb-1" routerLink="/sync/samba" routerLinkActive="text-white fw-bold">↔ Samba → KC</a>
          <a class="nav-link text-white-50 mb-1" routerLink="/history" routerLinkActive="text-white fw-bold">📘 History</a>
          <div class="mt-auto small">
            <div class="text-white-50 mb-1">{{ username }}</div>
            <button class="btn btn-sm btn-outline-light" (click)="logout()">Logout</button>
          </div>
        </nav>
        <main class="flex-grow-1 p-4 overflow-auto bg-light"><router-outlet></router-outlet></main>
      </div>
    </ng-container>
    <ng-template #loginTpl>
      <div class="d-flex vh-100 align-items-center justify-content-center bg-light">
        <div class="card shadow-sm p-4 text-center">
          <h5 class="mb-3">🔐 Keycloak User Sync</h5>
          <button class="btn btn-primary" (click)="login()">Login</button>
        </div>
      </div>
    </ng-template>
  `,
})
export class AppComponent implements OnInit {
  private oidc = inject(OidcSecurityService);
  authenticated = false;
  username = '';
  ngOnInit() {
    this.oidc.checkAuth().subscribe(r => {
      this.authenticated = r.isAuthenticated;
      this.username = (r.userData?.preferred_username as string) ?? '';
    });
  }
  login() { this.oidc.authorize(); }
  logout() { this.oidc.logoff().subscribe(); }
}
```

- [ ] **Step 3: Add theme tweaks**

Append to `frontend/src/styles.css`:
```css
.nav-link { cursor: pointer; }
.help-tip { border-bottom: 1px dotted #888; cursor: help; }
```

- [ ] **Step 4: Build (after Tasks 3–7 exist) + commit**

Run: `cd frontend && npm run build`
Expected: build succeeds once route components exist.
```bash
git add frontend/src/app/app.component.ts frontend/src/app/app.routes.ts frontend/src/styles.css
git commit -m "feat(ui): Bootstrap sidebar console shell + routes"
```

---

## PHASE B — Help components

### Task 3: Reusable hybrid-help components + content

**Files:**
- Create: `frontend/src/app/help/help-text.component.ts`
- Create: `frontend/src/app/help/help-tooltip.component.ts`
- Create: `frontend/src/app/help/help-example.component.ts`
- Create: `frontend/src/app/help/option-help.ts`
- Test: `frontend/src/app/help/help-example.component.spec.ts`

**Interfaces:**
- Produces:
  - `<help-text>one-liner</help-text>` — muted inline help (`<small class="text-muted">`).
  - `<help-tooltip text="...">` — an `ⓘ` with a CSS hover tooltip (uses `title` + `.help-tip`).
  - `<help-example [title]="'Example'"><...projected...></help-example>` — a native `<details>` accordion styled Bootstrap-ish.
  - `option-help.ts` exporting `MODE_HELP: Record<SyncMode, {summary: string; example: string}>` and `INCLUDE_ROLES_HELP`.

- [ ] **Step 1: Write the help content**

`frontend/src/app/help/option-help.ts`:
```ts
import { SyncMode } from '../core/models';

export const MODE_HELP: Record<SyncMode, { summary: string; example: string }> = {
  CREATE_ONLY: {
    summary: 'Adds users missing in the target; never touches existing ones.',
    example: 'Target has alice. Source has alice, bruno → bruno created, alice skipped.',
  },
  CREATE_UPDATE: {
    summary: 'Upsert: creates missing users and refreshes details of existing ones. Never deletes.',
    example: "alice's email changed at source → alice updated; bruno new → created; nothing deleted.",
  },
  MIRROR: {
    summary: 'Makes the target match the source exactly, including DELETING target users not in the source.',
    example: 'Target has alice, stale. Source has alice → alice updated, stale DELETED.',
  },
};

export const INCLUDE_ROLES_HELP = {
  summary: 'Also copy each user’s realm roles, creating any missing roles in the target.',
  example: 'carla has auditor+teller; target lacks auditor → auditor role auto-created, then assigned.',
};
```

- [ ] **Step 2: Write help-text + help-tooltip**

`frontend/src/app/help/help-text.component.ts`:
```ts
import { Component } from '@angular/core';
@Component({
  selector: 'help-text', standalone: true,
  template: `<small class="text-muted d-block mt-1"><ng-content></ng-content></small>`,
})
export class HelpTextComponent {}
```

`frontend/src/app/help/help-tooltip.component.ts`:
```ts
import { Component, Input } from '@angular/core';
@Component({
  selector: 'help-tooltip', standalone: true,
  template: `<span class="help-tip text-primary" [title]="text">&#9432;</span>`,
})
export class HelpTooltipComponent { @Input() text = ''; }
```

- [ ] **Step 3: Write the failing help-example test**

`frontend/src/app/help/help-example.component.spec.ts`:
```ts
import { TestBed } from '@angular/core/testing';
import { HelpExampleComponent } from './help-example.component';

describe('HelpExampleComponent', () => {
  it('renders a details element with the given title', () => {
    TestBed.configureTestingModule({ imports: [HelpExampleComponent] });
    const f = TestBed.createComponent(HelpExampleComponent);
    f.componentInstance.title = 'See example';
    f.detectChanges();
    const summary = f.nativeElement.querySelector('summary');
    expect(summary.textContent).toContain('See example');
    expect(f.nativeElement.querySelector('details')).toBeTruthy();
  });
});
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/help-example.component.spec.ts'`
Expected: FAIL — component missing.

- [ ] **Step 5: Write help-example**

`frontend/src/app/help/help-example.component.ts`:
```ts
import { Component, Input } from '@angular/core';
@Component({
  selector: 'help-example', standalone: true,
  template: `
    <details class="mt-1">
      <summary class="text-primary small" style="cursor:pointer">{{ title }}</summary>
      <div class="border rounded bg-light p-2 mt-1 small"><ng-content></ng-content></div>
    </details>`,
})
export class HelpExampleComponent { @Input() title = 'See example'; }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/help-example.component.spec.ts'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/help
git commit -m "feat(ui): reusable hybrid-help components (text, tooltip, example) + content"
```

---

## PHASE C — Connections

### Task 4: Connection editor component

**Files:**
- Create: `frontend/src/app/connections/connection-editor.component.ts`
- Test: `frontend/src/app/connections/connection-editor.component.spec.ts`

**Interfaces:**
- Consumes: `ConnectionRequest`, `ConnectionType`, help components.
- Produces: `<connection-editor [existing]="conn?" (save)="..." (cancel)="...">`. Emits a `ConnectionRequest` on save. Type-conditional fields (KEYCLOAK: realm, clientId; LDAP: baseDn, bindDn, userSearchBase). When creating an LDAP connection with no `existing`, the form is **pre-filled** with the Samba defaults. `SAMBA_DEFAULTS` is an exported const.

- [ ] **Step 1: Write the failing test (pre-fill + emit)**

`frontend/src/app/connections/connection-editor.component.spec.ts`:
```ts
import { TestBed } from '@angular/core/testing';
import { ConnectionEditorComponent } from './connection-editor.component';

describe('ConnectionEditorComponent', () => {
  it('emits a ConnectionRequest on save', () => {
    TestBed.configureTestingModule({ imports: [ConnectionEditorComponent] });
    const f = TestBed.createComponent(ConnectionEditorComponent);
    const c = f.componentInstance;
    const emitted: any[] = [];
    c.save.subscribe((r: any) => emitted.push(r));
    c.model = { name: 'X', type: 'KEYCLOAK', serverUrl: 'http://x', realm: 'x', clientId: 'agent', secret: 's' } as any;
    c.emit();
    expect(emitted[0].name).toBe('X');
    expect(emitted[0].type).toBe('KEYCLOAK');
  });

  it('prefills Samba defaults when creating an LDAP connection', () => {
    TestBed.configureTestingModule({ imports: [ConnectionEditorComponent] });
    const f = TestBed.createComponent(ConnectionEditorComponent);
    const c = f.componentInstance;
    c.useSambaDefaults();
    expect(c.model.type).toBe('LDAP');
    expect(c.model.baseDn).toContain('DC=ORGA');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/connection-editor.component.spec.ts'`
Expected: FAIL.

- [ ] **Step 3: Implement the editor**

`frontend/src/app/connections/connection-editor.component.ts`:
```ts
import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Connection, ConnectionRequest, ConnectionType } from '../core/models';
import { HelpTooltipComponent } from '../help/help-tooltip.component';

export const SAMBA_DEFAULTS: ConnectionRequest = {
  name: 'Samba', type: 'LDAP', serverUrl: 'ldap://localhost:389',
  baseDn: 'DC=ORGA,DC=LOCAL', bindDn: 'CN=Administrator,CN=Users,DC=ORGA,DC=LOCAL',
  userSearchBase: 'CN=Users', secret: '',
};

@Component({
  selector: 'connection-editor', standalone: true,
  imports: [CommonModule, FormsModule, HelpTooltipComponent],
  template: `
    <form (ngSubmit)="emit()" class="card p-3">
      <div class="mb-2"><label class="form-label">Name</label>
        <input class="form-control" [(ngModel)]="model.name" name="name" required></div>
      <div class="mb-2"><label class="form-label">Type</label>
        <select class="form-select" [(ngModel)]="model.type" name="type">
          <option value="KEYCLOAK">Keycloak</option><option value="LDAP">LDAP</option>
        </select></div>
      <div class="mb-2"><label class="form-label">Server URL</label>
        <input class="form-control" [(ngModel)]="model.serverUrl" name="serverUrl"></div>

      <ng-container *ngIf="model.type === 'KEYCLOAK'">
        <div class="mb-2"><label class="form-label">Realm</label>
          <input class="form-control" [(ngModel)]="model.realm" name="realm"></div>
        <div class="mb-2"><label class="form-label">Client ID
          <help-tooltip text="Service-account client used for the Admin API (no admin password)."></help-tooltip></label>
          <input class="form-control" [(ngModel)]="model.clientId" name="clientId"></div>
      </ng-container>

      <ng-container *ngIf="model.type === 'LDAP'">
        <div class="mb-2"><label class="form-label">Base DN</label>
          <input class="form-control" [(ngModel)]="model.baseDn" name="baseDn"></div>
        <div class="mb-2"><label class="form-label">Bind DN</label>
          <input class="form-control" [(ngModel)]="model.bindDn" name="bindDn"></div>
        <div class="mb-2"><label class="form-label">User search base</label>
          <input class="form-control" [(ngModel)]="model.userSearchBase" name="userSearchBase"></div>
      </ng-container>

      <div class="mb-2"><label class="form-label">Secret
        <help-tooltip text="Stored in Vault, never in the app database."></help-tooltip></label>
        <input class="form-control" type="password" [(ngModel)]="model.secret" name="secret"
               [placeholder]="existing ? 'unchanged if left blank' : ''"></div>

      <div class="d-flex gap-2">
        <button type="submit" class="btn btn-success btn-sm">Save</button>
        <button type="button" class="btn btn-outline-secondary btn-sm" (click)="cancel.emit()">Cancel</button>
        <button type="button" *ngIf="!existing" class="btn btn-outline-info btn-sm ms-auto"
                (click)="useSambaDefaults()">Use Samba defaults</button>
      </div>
    </form>`,
})
export class ConnectionEditorComponent implements OnInit {
  @Input() existing?: Connection;
  @Output() save = new EventEmitter<ConnectionRequest>();
  @Output() cancel = new EventEmitter<void>();
  model: ConnectionRequest = { name: '', type: 'KEYCLOAK', serverUrl: '' };

  ngOnInit() {
    if (this.existing) {
      const e = this.existing;
      this.model = { name: e.name, type: e.type, serverUrl: e.serverUrl, realm: e.realm,
        baseDn: e.baseDn, clientId: e.clientId, bindDn: e.bindDn, userSearchBase: e.userSearchBase, secret: '' };
    }
  }
  useSambaDefaults() { this.model = { ...SAMBA_DEFAULTS }; }
  emit() { this.save.emit(this.model); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/connection-editor.component.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/connections/connection-editor.component.ts frontend/src/app/connections/connection-editor.component.spec.ts
git commit -m "feat(ui): connection editor with type-conditional fields + Samba prefill"
```

### Task 5: Connections list page

**Files:**
- Create: `frontend/src/app/connections/connections.component.ts`

**Interfaces:**
- Consumes: `ApiService`, `Connection`, `ConnectionEditorComponent`, `TestResult`.
- Produces: `ConnectionsComponent` (route `/connections`): table of connections (name, type, url, realm/baseDn, secretRef) with **Test**, **Edit**, **Delete** buttons; a **New connection** button toggling the editor; test result shown inline per row.

- [ ] **Step 1: Implement the component**

`frontend/src/app/connections/connections.component.ts`:
```ts
import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../core/api.service';
import { Connection, ConnectionRequest, TestResult } from '../core/models';
import { ConnectionEditorComponent } from './connection-editor.component';

@Component({
  selector: 'connections-page', standalone: true,
  imports: [CommonModule, ConnectionEditorComponent],
  template: `
    <h4 class="mb-3">Connections</h4>
    <button class="btn btn-primary btn-sm mb-3" (click)="startNew()" *ngIf="!editing">+ New connection</button>

    <connection-editor *ngIf="editing" [existing]="editTarget"
      (save)="onSave($event)" (cancel)="editing=false" class="d-block mb-3"></connection-editor>

    <table class="table table-sm bg-white shadow-sm">
      <thead><tr><th>Name</th><th>Type</th><th>URL</th><th>Realm / Base DN</th><th>Secret</th><th></th></tr></thead>
      <tbody>
        <tr *ngFor="let c of connections">
          <td>{{c.name}}</td><td><span class="badge bg-secondary">{{c.type}}</span></td>
          <td class="small">{{c.serverUrl}}</td><td class="small">{{c.realm || c.baseDn}}</td>
          <td class="small text-success">🔒 {{c.secretRef}}</td>
          <td class="text-nowrap">
            <button class="btn btn-outline-info btn-sm" (click)="test(c)">Test</button>
            <button class="btn btn-outline-secondary btn-sm" (click)="edit(c)">Edit</button>
            <button class="btn btn-outline-danger btn-sm" (click)="remove(c)">Delete</button>
            <div *ngIf="results[c.id]" class="small mt-1" [class.text-success]="results[c.id].ok" [class.text-danger]="!results[c.id].ok">
              {{ results[c.id].ok ? '✓' : '✗' }} {{ results[c.id].message }}
            </div>
          </td>
        </tr>
      </tbody>
    </table>`,
})
export class ConnectionsComponent implements OnInit {
  private api = inject(ApiService);
  connections: Connection[] = [];
  results: Record<number, TestResult> = {};
  editing = false;
  editTarget?: Connection;

  ngOnInit() { this.load(); }
  load() { this.api.connections().subscribe(c => (this.connections = c)); }
  startNew() { this.editTarget = undefined; this.editing = true; }
  edit(c: Connection) { this.editTarget = c; this.editing = true; }
  onSave(r: ConnectionRequest) {
    const done = () => { this.editing = false; this.load(); };
    if (this.editTarget) this.api.updateConnection(this.editTarget.id, r).subscribe(done);
    else this.api.createConnection(r).subscribe(done);
  }
  test(c: Connection) { this.api.testConnection(c.id).subscribe(r => (this.results[c.id] = r)); }
  remove(c: Connection) { this.api.deleteConnection(c.id).subscribe(() => this.load()); }
}
```
Note: `ConnectionsComponent` selector is `connections-page` but the route references the class directly, so the selector is irrelevant to routing.

- [ ] **Step 2: Build check**

Run: `cd frontend && npm run build` (will still fail until sync/history components exist — acceptable; verify no errors in connections files specifically by temporarily building after Task 7, or rely on the final Task 8 build). If building now, expect unresolved imports only from `app.routes.ts` referencing not-yet-created components.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/connections/connections.component.ts
git commit -m "feat(ui): connections list page with test/edit/delete"
```

---

## PHASE D — Sync flows

### Task 6: Shared sync-flow component + the two route wrappers

**Files:**
- Create: `frontend/src/app/sync/sync-flow.component.ts`
- Create: `frontend/src/app/sync/keycloak-sync.component.ts`
- Create: `frontend/src/app/sync/samba-sync.component.ts`
- Test: `frontend/src/app/sync/sync-flow.component.spec.ts`

**Interfaces:**
- Consumes: `ApiService`, models, help components, `MODE_HELP`, `INCLUDE_ROLES_HELP`.
- Produces:
  - `SyncFlowComponent` with `@Input() mode: 'keycloak'|'samba'`. It loads connections, filters the **source** dropdown by type (`keycloak` → KEYCLOAK sources; `samba` → LDAP sources) and the **target** dropdown to KEYCLOAK; renders mode select + include-roles with hybrid help; **Preview** calls the matching `*Plan`; **Confirm & run** calls the matching `*sync`; shows the plan and the result. Exposes `buildRequest()` returning `SyncRunRequest` (unit tested).
  - `KeycloakSyncComponent` (route) → `<sync-flow mode="keycloak">`.
  - `SambaSyncComponent` (route) → `<sync-flow mode="samba">`.

- [ ] **Step 1: Write the failing buildRequest test**

`frontend/src/app/sync/sync-flow.component.spec.ts`:
```ts
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { SyncFlowComponent } from './sync-flow.component';

describe('SyncFlowComponent', () => {
  it('builds a SyncRunRequest from selections', () => {
    TestBed.configureTestingModule({ imports: [SyncFlowComponent, HttpClientTestingModule] });
    const c = TestBed.createComponent(SyncFlowComponent).componentInstance;
    c.sourceId = 1; c.targetId = 2; c.selectedMode = 'MIRROR'; c.includeRoles = true;
    expect(c.buildRequest()).toEqual({ sourceConnId: 1, targetConnId: 2, mode: 'MIRROR', includeRoles: true });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/sync-flow.component.spec.ts'`
Expected: FAIL.

- [ ] **Step 3: Implement sync-flow**

`frontend/src/app/sync/sync-flow.component.ts`:
```ts
import { Component, Input, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { Connection, SyncMode, SyncPlan, SyncResult, SyncRunRequest } from '../core/models';
import { MODE_HELP, INCLUDE_ROLES_HELP } from '../help/option-help';
import { HelpTextComponent } from '../help/help-text.component';
import { HelpExampleComponent } from '../help/help-example.component';
import { HelpTooltipComponent } from '../help/help-tooltip.component';

@Component({
  selector: 'sync-flow', standalone: true,
  imports: [CommonModule, FormsModule, HelpTextComponent, HelpExampleComponent, HelpTooltipComponent],
  template: `
    <h4 class="mb-3">{{ mode === 'keycloak' ? 'Keycloak → Keycloak' : 'Samba → Keycloak' }} sync</h4>
    <div class="card p-3 mb-3" style="max-width:620px">
      <div class="row g-2">
        <div class="col"><label class="form-label">Source</label>
          <select class="form-select" [(ngModel)]="sourceId">
            <option [ngValue]="undefined" disabled>Choose…</option>
            <option *ngFor="let c of sources" [ngValue]="c.id">{{c.name}} ({{c.type}})</option>
          </select></div>
        <div class="col"><label class="form-label">Target</label>
          <select class="form-select" [(ngModel)]="targetId">
            <option [ngValue]="undefined" disabled>Choose…</option>
            <option *ngFor="let c of targets" [ngValue]="c.id">{{c.name}}</option>
          </select></div>
      </div>

      <div class="mt-3"><label class="form-label">Mode
        <help-tooltip [text]="modeHelp(selectedMode).summary"></help-tooltip></label>
        <select class="form-select" [(ngModel)]="selectedMode">
          <option value="CREATE_ONLY">Create only</option>
          <option value="CREATE_UPDATE">Create + update</option>
          <option value="MIRROR">Mirror (deletes extras)</option>
        </select>
        <help-text>{{ modeHelp(selectedMode).summary }}</help-text>
        <help-example title="See example">{{ modeHelp(selectedMode).example }}</help-example>
      </div>

      <div class="form-check mt-3">
        <input class="form-check-input" type="checkbox" id="ir" [(ngModel)]="includeRoles">
        <label class="form-check-label" for="ir">Include roles
          <help-tooltip [text]="rolesHelp.summary"></help-tooltip></label>
        <help-text>{{ rolesHelp.summary }}</help-text>
        <help-example title="See example">{{ rolesHelp.example }}</help-example>
      </div>

      <div class="mt-3 d-flex gap-2">
        <button class="btn btn-outline-primary btn-sm" [disabled]="!ready()" (click)="preview()">Preview (dry-run)</button>
        <button class="btn btn-success btn-sm" [disabled]="!plan" (click)="run()">Confirm &amp; run</button>
      </div>
    </div>

    <div *ngIf="plan" class="card p-3 mb-3" style="max-width:620px">
      <b>Dry-run preview — no changes yet</b>
      <ul class="small mb-0 mt-2">
        <li *ngFor="let a of plan.actions">
          <span class="badge" [ngClass]="badge(a.action)">{{a.action}}</span> {{a.username}}
        </li>
        <li *ngIf="plan.actions.length === 0" class="text-muted">No changes.</li>
      </ul>
    </div>

    <div *ngIf="result" class="alert" [ngClass]="result.errors.length ? 'alert-warning' : 'alert-success'" style="max-width:620px">
      Created {{result.created}} · Updated {{result.updated}} · Skipped {{result.skipped}} · Deleted {{result.deleted}}
      <ul *ngIf="result.errors.length" class="mb-0 mt-1">
        <li *ngFor="let e of result.errors">{{e}}</li>
      </ul>
    </div>`,
})
export class SyncFlowComponent implements OnInit {
  @Input() mode: 'keycloak' | 'samba' = 'keycloak';
  private api = inject(ApiService);
  sources: Connection[] = [];
  targets: Connection[] = [];
  sourceId?: number;
  targetId?: number;
  selectedMode: SyncMode = 'CREATE_UPDATE';
  includeRoles = false;
  plan?: SyncPlan;
  result?: SyncResult;
  rolesHelp = INCLUDE_ROLES_HELP;

  ngOnInit() {
    this.api.connections().subscribe(cs => {
      const sourceType = this.mode === 'keycloak' ? 'KEYCLOAK' : 'LDAP';
      this.sources = cs.filter(c => c.type === sourceType);
      this.targets = cs.filter(c => c.type === 'KEYCLOAK');
    });
  }
  modeHelp(m: SyncMode) { return MODE_HELP[m]; }
  ready() { return this.sourceId != null && this.targetId != null; }
  buildRequest(): SyncRunRequest {
    return { sourceConnId: this.sourceId!, targetConnId: this.targetId!, mode: this.selectedMode, includeRoles: this.includeRoles };
  }
  preview() {
    this.result = undefined;
    const req = this.buildRequest();
    const call = this.mode === 'keycloak' ? this.api.keycloakPlan(req) : this.api.sambaPlan(req);
    call.subscribe(p => (this.plan = p));
  }
  run() {
    const req = this.buildRequest();
    const call = this.mode === 'keycloak' ? this.api.keycloakSync(req) : this.api.sambaSync(req);
    call.subscribe(r => { this.result = r; this.plan = undefined; });
  }
  badge(a: string) {
    return { CREATE: 'bg-success', UPDATE: 'bg-info', DELETE: 'bg-danger', SKIP: 'bg-secondary' }[a] ?? 'bg-secondary';
  }
}
```

`frontend/src/app/sync/keycloak-sync.component.ts`:
```ts
import { Component } from '@angular/core';
import { SyncFlowComponent } from './sync-flow.component';
@Component({
  selector: 'keycloak-sync', standalone: true, imports: [SyncFlowComponent],
  template: `<sync-flow mode="keycloak"></sync-flow>`,
})
export class KeycloakSyncComponent {}
```

`frontend/src/app/sync/samba-sync.component.ts`:
```ts
import { Component } from '@angular/core';
import { SyncFlowComponent } from './sync-flow.component';
@Component({
  selector: 'samba-sync', standalone: true, imports: [SyncFlowComponent],
  template: `<sync-flow mode="samba"></sync-flow>`,
})
export class SambaSyncComponent {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/sync-flow.component.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/sync
git commit -m "feat(ui): shared sync-flow with connection pickers, hybrid help, dry-run + confirm"
```

---

## PHASE E — History

### Task 7: History (audit log) page

**Files:**
- Create: `frontend/src/app/history/history.component.ts`

**Interfaces:**
- Consumes: `ApiService`, `SyncRun`.
- Produces: `HistoryComponent` (route `/history`): a table of audit runs (timestamp, actor, source→target, mode, counts, status badge).

- [ ] **Step 1: Implement the component**

`frontend/src/app/history/history.component.ts`:
```ts
import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../core/api.service';
import { SyncRun } from '../core/models';

@Component({
  selector: 'history-page', standalone: true, imports: [CommonModule],
  template: `
    <h4 class="mb-3">History</h4>
    <table class="table table-sm bg-white shadow-sm">
      <thead><tr><th>When</th><th>Actor</th><th>Source → Target</th><th>Mode</th><th>Result</th><th>Status</th></tr></thead>
      <tbody>
        <tr *ngFor="let r of runs">
          <td class="small">{{ r.timestamp }}</td><td class="small">{{ r.actor }}</td>
          <td class="small">{{ r.sourceConn }} → {{ r.targetConn }}</td>
          <td class="small">{{ r.mode }}<span *ngIf="r.includeRoles"> +roles</span></td>
          <td class="small">+{{r.created}} ~{{r.updated}} -{{r.deleted}} ={{r.skipped}}
            <span *ngIf="r.errorCount" class="text-danger">({{r.errorCount}} err)</span></td>
          <td><span class="badge" [ngClass]="r.status === 'OK' ? 'bg-success' : 'bg-warning text-dark'">{{r.status}}</span></td>
        </tr>
        <tr *ngIf="runs.length === 0"><td colspan="6" class="text-muted small">No syncs yet.</td></tr>
      </tbody>
    </table>`,
})
export class HistoryComponent implements OnInit {
  private api = inject(ApiService);
  runs: SyncRun[] = [];
  ngOnInit() { this.api.audit().subscribe(r => (this.runs = r)); }
}
```

- [ ] **Step 2: Full build (all components now exist)**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/history/history.component.ts
git commit -m "feat(ui): history (audit log) page"
```

---

## PHASE F — Documentation

### Task 8: README

**Files:**
- Create: `docs/README.md`

- [ ] **Step 1: Write docs/README.md**

Write `docs/README.md` with these sections (real content, no placeholders):
- **Overview** — what the tool does (list/sync users; Samba→KC and KC→KC; configurable connections; OIDC-protected).
- **Architecture at a glance** — one paragraph + the container list (3 Keycloaks, Vault, Samba, backend, frontend) linking to `architecture.md`.
- **Prerequisites** — Docker, Java 21, Node 20.
- **Run it** — exact commands:
  ```bash
  docker compose up -d postgres-ubs postgres-cs postgres-app keycloak-ubs keycloak-cs keycloak-app vault
  (cd backend && ./mvnw spring-boot:run)     # or: mvn spring-boot:run
  (cd frontend && npm install && npm start)  # http://localhost:4200, login admin/admin
  ```
- **Hostnames** — the `*.localtest.me` cookie-isolation note and the three admin-console URLs.
- **Using the tool** — Connections (add/test/edit), the two sync flows with dry-run→confirm, History.
- **Secrets** — one line: secrets live in Vault; profiles hold only a `secretRef`; link to `security-audit.md`.
- **Tests** — `cd backend && mvn test`; `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`.

- [ ] **Step 2: Commit**

```bash
git add docs/README.md
git commit -m "docs: project README (run, use, test)"
```

### Task 9: Architecture document (arc42/C4)

**Files:**
- Create: `docs/architecture.md`

- [ ] **Step 1: Write docs/architecture.md**

Write `docs/architecture.md` following an arc42/C4-lite structure with real content:
1. **Introduction & goals** — configurable user-sync tool; quality goals: security (banking), configurability, usability, auditability.
2. **Constraints** — Keycloak 25, Java 21/Spring Boot 3.3, Angular 18, Vault dev, service-account-only auth.
3. **Context (C4 L1)** — actors (admin) and external systems (Keycloak instances, Samba AD, Vault). Include a Mermaid `graph` diagram.
4. **Containers (C4 L2)** — frontend (Angular), backend (Spring Boot), H2, Vault, Keycloaks, Samba. Mermaid diagram + responsibilities table.
5. **Components (C4 L3, backend)** — ConnectionService/SecretStore/ServiceAccountKeycloakFactory/KeycloakSyncService/SambaSyncService/AuditService; how a sync flows (source read → computePlan → execute → audit).
6. **Runtime scenarios** — "dry-run then sync" sequence; "test connection" sequence (Mermaid `sequenceDiagram`).
7. **Data model** — `Connection`, `SyncRun` fields; note profiles hold only `secretRef`.
8. **Cross-cutting** — auth (OIDC app realm; service-account for admin API), secrets (Vault), error handling (per-user error collection), config (H2 + Vault).
9. **Decisions (ADR-style, short)** — two independent pipelines; service-account-only; Vault for secrets; H2 for metadata; dry-run + audit. Reference the spec.

- [ ] **Step 2: Commit**

```bash
git add docs/architecture.md
git commit -m "docs: arc42/C4 architecture document"
```

### Task 10: Security / audit document

**Files:**
- Create: `docs/security-audit.md`

- [ ] **Step 1: Write docs/security-audit.md**

Write `docs/security-audit.md` with real content:
- **Scope** — what secrets exist (Keycloak service-account client secrets, LDAP bind password) and what does NOT (no end-user passwords stored — OIDC login).
- **Secret handling** — the hash-vs-encrypt-vs-vault distinction; why these secrets are vaulted, not hashed; profiles store only `secretRef`; Vault path layout `usersync/<name>`.
- **Authentication & least privilege** — app protected by OIDC (`app` realm); Keycloak Admin API accessed via per-realm `user-sync-agent` service-account client with least-privilege `realm-management` roles (`view-users`, `manage-users`, `view-realm`, `manage-realm`); no master `admin/admin`.
- **Standards mapping** — table mapping controls to PCI-DSS §3.5–3.6 (key management), NIST SP 800-57 (key management) & 800-53 (SC-12/SC-28, IA-5), OWASP ASVS V2/V6. 
- **Dev vs production** — Vault runs in **dev mode** here (in-memory, root token, re-seeded on boot). Production path: HA Vault with KMS/HSM-backed auto-unseal, AppRole/Kubernetes auth (not root token), secret rotation, TLS everywhere, audit device enabled. LDAP: prefer SASL/GSSAPI or mTLS bind over a bind password.
- **Audit trail** — every sync writes a `SyncRun` (actor, timestamp, scope, counts, status); viewable in History and via `GET /api/audit`.
- **Known limitations** — dev-mode Vault; bind password (not Kerberos/mTLS) for LDAP; single-node H2.

- [ ] **Step 2: Commit**

```bash
git add docs/security-audit.md
git commit -m "docs: security/audit rationale + standards mapping"
```

---

## PHASE G — Verification

### Task 11: Build, tests, and live click-through

**Files:** none (verification only).

- [ ] **Step 1: Frontend build + unit tests**

Run:
```bash
cd frontend && npm run build
npm test -- --watch=false --browsers=ChromeHeadless
```
Expected: build succeeds; all component/service specs pass.

- [ ] **Step 2: Bring up the full stack + backend + frontend**

Run (Keycloaks + Vault + backend from Plan 1 may already be running):
```bash
cd /Users/macbook/Desktop/keycloakcomm
docker compose up -d postgres-ubs postgres-cs postgres-app keycloak-ubs keycloak-cs keycloak-app vault
export JAVA_HOME=/Users/macbook/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home
(cd backend && mvn -q spring-boot:run &) ; sleep 40
(cd frontend && npm start &) ; sleep 20
curl -s -o /dev/null -w "front:%{http_code} back:" http://localhost:4200; curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9090/actuator/health
```
Expected: `front:200 back:200`.

- [ ] **Step 3: Manual click-through (record results)**

In a fresh incognito window at `http://localhost:4200`:
1. Login `admin`/`admin` → sidebar console appears.
2. **Connections**: see UBS/CS/Samba seeded; click **Test** on UBS → "auth OK (service account)".
3. **Keycloak → KC**: Source UBS, Target CS, mode Create + update, Include roles; click **Preview** → see CREATE/UPDATE list; click **Confirm & run** → success alert with counts.
4. **History**: the run appears with status OK.
5. Hover an `ⓘ` and expand a "See example" to confirm hybrid help renders.

Record the observed outcome (pass/fail per step) in the task notes.

- [ ] **Step 4: Stop background dev servers when done**

```bash
lsof -ti tcp:9090 | xargs kill 2>/dev/null || true
lsof -ti tcp:4200 | xargs kill 2>/dev/null || true
```

---

## Self-Review Notes

- **Spec coverage:** Bootstrap sidebar console (Task 2), Connections page + editor with Samba pre-fill + test (Tasks 4–5), hybrid help components (Task 3), two sync flows with dry-run→confirm (Task 6), History/audit view (Task 7), and the three docs — README/architecture/security-audit (Tasks 8–10). Maps to spec §§1–3, 5, 8–9 UI/doc portions.
- **Contract alignment:** ApiService + SyncFlow use exactly the Plan 1 endpoints and the `{sourceConnId,targetConnId,mode,includeRoles}` body; `SyncPlan.actions[].action` values match backend `ActionType`.
- **Placeholder scan:** none. (The doc tasks specify concrete section content to write, not TODOs.) The History nav emoji must be corrected to `📘` per Task 2 Step 2 note.
- **Ordering note:** `app.routes.ts` is emptied in Task 1 and populated in Task 2, but the referenced route components (Tasks 3–7) must exist before the full `npm run build` in Task 7 Step 2 / Task 2 Step 4 succeeds. Component unit tests in Tasks 3, 4, 6 run independently before then.
- **Terminal state:** after Task 11, invoke `superpowers:finishing-a-development-branch` to complete the whole `feat/configurable-ui-ux` branch (both plans).
