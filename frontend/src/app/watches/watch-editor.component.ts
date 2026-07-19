import { Component, EventEmitter, Input, OnInit, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { Connection, UserWatch, UserWatchRequest } from '../core/models';
import { HelpTextComponent } from '../help/help-text.component';
import { HelpExampleComponent } from '../help/help-example.component';
import { HelpTooltipComponent } from '../help/help-tooltip.component';

const CRON_EXAMPLES = [
  { expr: '0 0 2 * * ?', label: 'Every day at 02:00' },
  { expr: '0 0 3 ? * MON', label: 'Every Monday at 03:00' },
  { expr: '0 0 4 1 * ?', label: 'First of the month at 04:00' },
];

@Component({
  selector: 'watch-editor', standalone: true,
  imports: [CommonModule, FormsModule, HelpTextComponent, HelpExampleComponent, HelpTooltipComponent],
  template: `
    <form (ngSubmit)="emit()" class="card p-3" style="max-width:680px">
      <div class="mb-2"><label class="form-label">Name</label>
        <input class="form-control" [(ngModel)]="model.name" name="name" required></div>
      <div class="mb-2"><label class="form-label">Source type</label>
        <select class="form-select" [(ngModel)]="model.type" name="type" (ngModelChange)="refilter()">
          <option value="KEYCLOAK">Keycloak → Keycloak</option>
          <option value="SAMBA">Samba → Keycloak</option>
        </select></div>
      <div class="row g-2">
        <div class="col"><label class="form-label">Source</label>
          <select class="form-select" [(ngModel)]="model.sourceConnId" name="src" (ngModelChange)="loadSourceUsers()">
            <option *ngFor="let c of sources" [ngValue]="c.id">{{c.name}}</option></select></div>
        <div class="col"><label class="form-label">Target</label>
          <select class="form-select" [(ngModel)]="model.targetConnId" name="dst">
            <option *ngFor="let c of targets" [ngValue]="c.id">{{c.name}}</option></select></div>
      </div>

      <div class="mb-2 mt-2"><label class="form-label">Selection</label>
        <select class="form-select" [(ngModel)]="model.selectionMode" name="selmode">
          <option value="LIST">Pick specific users</option>
          <option value="FILTER">Filter by search term</option></select></div>

      <div *ngIf="model.selectionMode === 'LIST'" class="mb-2 border rounded p-2" style="max-height:200px;overflow:auto">
        <div *ngIf="sourceUsers.length === 0" class="text-muted small">Select a source to load its users.</div>
        <div class="form-check" *ngFor="let u of sourceUsers">
          <input class="form-check-input" type="checkbox" [id]="'u-'+u"
            [checked]="picked.has(u)" (change)="togglePick(u)">
          <label class="form-check-label" [for]="'u-'+u">{{u}}</label>
        </div>
      </div>

      <div *ngIf="model.selectionMode === 'FILTER'" class="mb-2">
        <label class="form-label">Search term
          <help-tooltip text="Case-insensitive substring matched against the source username."></help-tooltip></label>
        <input class="form-control" [(ngModel)]="model.selectionPayload" name="term" placeholder="e.g. teller">
        <help-text>Users whose username contains this term are watched. Blank = all source users.</help-text>
      </div>

      <div class="form-check mb-2"><input class="form-check-input" type="checkbox" id="wir"
        [(ngModel)]="model.includeRoles" name="ir">
        <label class="form-check-label" for="wir">Include roles</label></div>

      <div class="mb-2"><label class="form-label">On source removal
        <help-tooltip text="What to do on the target when a watched user is deleted at the source."></help-tooltip></label>
        <select class="form-select" [(ngModel)]="model.onDelete" name="ondel">
          <option value="DISABLE">Disable on target (safe, reversible)</option>
          <option value="DELETE">Delete on target (permanent)</option>
          <option value="IGNORE">Ignore (report only)</option></select></div>

      <div class="mb-2"><label class="form-label">Run mode
        <help-tooltip text="Report-only records what would change without touching the target."></help-tooltip></label>
        <select class="form-select" [(ngModel)]="model.runMode" name="runmode">
          <option value="REPORT_ONLY">Report only (dry-run)</option>
          <option value="ENFORCE">Enforce (apply changes)</option></select></div>

      <div class="mb-2"><label class="form-label">Cron (sec min hour day month weekday)
        <help-tooltip text="Spring 6-field cron. e.g. 0 0 2 * * ? = daily at 02:00"></help-tooltip></label>
        <input class="form-control" [(ngModel)]="model.cron" name="cron" placeholder="0 0 2 * * ?">
        <help-example title="Cron examples">
          <div *ngFor="let e of examples"><code>{{e.expr}}</code> — {{e.label}}</div>
        </help-example></div>

      <div class="form-check mb-3"><input class="form-check-input" type="checkbox" id="wen"
        [(ngModel)]="model.enabled" name="en"><label class="form-check-label" for="wen">Enabled</label></div>
      <div class="d-flex gap-2">
        <button type="submit" class="btn btn-success btn-sm">Save</button>
        <button type="button" class="btn btn-outline-secondary btn-sm" (click)="cancel.emit()">Cancel</button>
      </div>
    </form>`,
})
export class WatchEditorComponent implements OnInit {
  @Input() existing?: UserWatch;
  @Output() save = new EventEmitter<UserWatchRequest>();
  @Output() cancel = new EventEmitter<void>();
  private api = inject(ApiService);
  examples = CRON_EXAMPLES;
  all: Connection[] = [];
  sources: Connection[] = [];
  targets: Connection[] = [];
  sourceUsers: string[] = [];
  picked = new Set<string>();
  model: UserWatchRequest = { name: '', type: 'KEYCLOAK', sourceConnId: 0, targetConnId: 0,
    selectionMode: 'LIST', selectionPayload: '', includeRoles: false,
    onDelete: 'DISABLE', runMode: 'REPORT_ONLY', cron: '0 0 2 * * ?', enabled: true };

  ngOnInit() {
    if (this.existing) {
      const e = this.existing;
      this.model = { name: e.name, type: e.type, sourceConnId: e.sourceConnId, targetConnId: e.targetConnId,
        selectionMode: e.selectionMode, selectionPayload: e.selectionPayload, includeRoles: e.includeRoles,
        onDelete: e.onDelete, runMode: e.runMode, cron: e.cron, enabled: e.enabled };
      if (e.selectionMode === 'LIST') {
        e.selectionPayload.split(',').map(s => s.trim()).filter(Boolean).forEach(u => this.picked.add(u));
      }
    }
    this.api.connections().subscribe(cs => { this.all = cs; this.refilter(); this.loadSourceUsers(); });
  }
  refilter() {
    const st = this.model.type === 'KEYCLOAK' ? 'KEYCLOAK' : 'LDAP';
    this.sources = this.all.filter(c => c.type === st);
    this.targets = this.all.filter(c => c.type === 'KEYCLOAK');
  }
  loadSourceUsers() {
    if (!this.model.sourceConnId) { this.sourceUsers = []; return; }
    this.api.watchSourceUsers(this.model.sourceConnId).subscribe(us => (this.sourceUsers = us));
  }
  togglePick(u: string) {
    if (this.picked.has(u)) this.picked.delete(u); else this.picked.add(u);
    this.model.selectionPayload = Array.from(this.picked).join(',');
  }
  emit() {
    if (this.model.selectionMode === 'LIST') this.model.selectionPayload = Array.from(this.picked).join(',');
    this.save.emit(this.model);
  }
}
