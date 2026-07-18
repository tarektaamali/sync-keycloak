import { Component, EventEmitter, Input, OnInit, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { Connection, ScheduledJob, ScheduleRequest } from '../core/models';
import { MODE_HELP, INCLUDE_ROLES_HELP } from '../help/option-help';
import { HelpTextComponent } from '../help/help-text.component';
import { HelpExampleComponent } from '../help/help-example.component';
import { HelpTooltipComponent } from '../help/help-tooltip.component';

export const CRON_EXAMPLES = [
  { expr: '0 0 2 * * ?', label: 'Every day at 02:00' },
  { expr: '0 0 * * * ?', label: 'Every hour' },
  { expr: '0 */15 * * * ?', label: 'Every 15 minutes' },
  { expr: '0 0 3 ? * MON', label: 'Every Monday at 03:00' },
];

@Component({
  selector: 'schedule-editor', standalone: true,
  imports: [CommonModule, FormsModule, HelpTextComponent, HelpExampleComponent, HelpTooltipComponent],
  template: `
    <form (ngSubmit)="emit()" class="card p-3" style="max-width:640px">
      <div class="mb-2"><label class="form-label">Name</label>
        <input class="form-control" [(ngModel)]="model.name" name="name" required></div>
      <div class="mb-2"><label class="form-label">Type</label>
        <select class="form-select" [(ngModel)]="model.type" name="type" (ngModelChange)="refilter()">
          <option value="KEYCLOAK">Keycloak → Keycloak</option>
          <option value="SAMBA">Samba → Keycloak</option>
        </select></div>
      <div class="row g-2">
        <div class="col"><label class="form-label">Source</label>
          <select class="form-select" [(ngModel)]="model.sourceConnId" name="src">
            <option *ngFor="let c of sources" [ngValue]="c.id">{{c.name}}</option></select></div>
        <div class="col"><label class="form-label">Target</label>
          <select class="form-select" [(ngModel)]="model.targetConnId" name="dst">
            <option *ngFor="let c of targets" [ngValue]="c.id">{{c.name}}</option></select></div>
      </div>
      <div class="mb-2 mt-2"><label class="form-label">Mode
        <help-tooltip [text]="modeHelp(model.mode).summary"></help-tooltip></label>
        <select class="form-select" [(ngModel)]="model.mode" name="mode">
          <option value="CREATE_ONLY">Create only</option>
          <option value="CREATE_UPDATE">Create + update</option>
          <option value="MIRROR">Mirror (deletes extras)</option></select>
        <help-text>{{ modeHelp(model.mode).summary }}</help-text></div>
      <div class="form-check mb-2"><input class="form-check-input" type="checkbox" id="sir"
        [(ngModel)]="model.includeRoles" name="ir">
        <label class="form-check-label" for="sir">Include roles</label></div>
      <div class="mb-2"><label class="form-label">Cron (sec min hour day month weekday)
        <help-tooltip text="Spring 6-field cron. e.g. 0 0 2 * * ? = daily at 02:00"></help-tooltip></label>
        <input class="form-control" [(ngModel)]="model.cron" name="cron" placeholder="0 0 2 * * ?">
        <help-example title="Cron examples">
          <div *ngFor="let e of examples"><code>{{e.expr}}</code> — {{e.label}}</div>
        </help-example></div>
      <div class="form-check mb-3"><input class="form-check-input" type="checkbox" id="sen"
        [(ngModel)]="model.enabled" name="en"><label class="form-check-label" for="sen">Enabled</label></div>
      <div class="d-flex gap-2">
        <button type="submit" class="btn btn-success btn-sm">Save</button>
        <button type="button" class="btn btn-outline-secondary btn-sm" (click)="cancel.emit()">Cancel</button>
      </div>
    </form>`,
})
export class ScheduleEditorComponent implements OnInit {
  @Input() existing?: ScheduledJob;
  @Output() save = new EventEmitter<ScheduleRequest>();
  @Output() cancel = new EventEmitter<void>();
  private api = inject(ApiService);
  examples = CRON_EXAMPLES;
  all: Connection[] = [];
  sources: Connection[] = [];
  targets: Connection[] = [];
  model: ScheduleRequest = { name: '', type: 'KEYCLOAK', sourceConnId: 0, targetConnId: 0,
    mode: 'CREATE_UPDATE', includeRoles: false, cron: '0 0 2 * * ?', enabled: true };

  ngOnInit() {
    if (this.existing) {
      const e = this.existing;
      this.model = { name: e.name, type: e.type, sourceConnId: e.sourceConnId, targetConnId: e.targetConnId,
        mode: e.mode, includeRoles: e.includeRoles, cron: e.cron, enabled: e.enabled };
    }
    this.api.connections().subscribe(cs => { this.all = cs; this.refilter(); });
  }
  refilter() {
    const st = this.model.type === 'KEYCLOAK' ? 'KEYCLOAK' : 'LDAP';
    this.sources = this.all.filter(c => c.type === st);
    this.targets = this.all.filter(c => c.type === 'KEYCLOAK');
  }
  modeHelp(m: any) { return MODE_HELP[m as keyof typeof MODE_HELP]; }
  rolesHelp = INCLUDE_ROLES_HELP;
  emit() { this.save.emit(this.model); }
  buildRequest(): ScheduleRequest { return this.model; }
}
