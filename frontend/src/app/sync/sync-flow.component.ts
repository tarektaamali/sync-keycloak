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
