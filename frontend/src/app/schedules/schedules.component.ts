import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../core/api.service';
import { Connection, ScheduledJob, ScheduleRequest } from '../core/models';
import { ScheduleEditorComponent } from './schedule-editor.component';

@Component({
  selector: 'schedules-page', standalone: true,
  imports: [CommonModule, ScheduleEditorComponent],
  template: `
    <h4 class="mb-3">Schedules</h4>
    <button class="btn btn-primary btn-sm mb-3" (click)="startNew()" *ngIf="!editing">+ New schedule</button>
    <schedule-editor *ngIf="editing" [existing]="editTarget"
      (save)="onSave($event)" (cancel)="editing=false" class="d-block mb-3"></schedule-editor>

    <table class="table table-sm bg-white shadow-sm">
      <thead><tr><th>Name</th><th>Source → Target</th><th>Cron</th><th>Mode</th><th>Enabled</th><th></th></tr></thead>
      <tbody>
        <tr *ngFor="let j of jobs">
          <td>{{j.name}}</td>
          <td class="small">{{ name(j.sourceConnId) }} → {{ name(j.targetConnId) }}</td>
          <td><code class="small">{{j.cron}}</code></td>
          <td class="small">{{j.mode}}<span *ngIf="j.includeRoles"> +roles</span></td>
          <td><span class="badge" [ngClass]="j.enabled ? 'bg-success' : 'bg-secondary'">{{ j.enabled ? 'on' : 'off' }}</span></td>
          <td class="text-nowrap">
            <button class="btn btn-outline-success btn-sm" (click)="run(j)">Run now</button>
            <button class="btn btn-outline-secondary btn-sm" (click)="edit(j)">Edit</button>
            <button class="btn btn-outline-danger btn-sm" (click)="remove(j)">Delete</button>
            <span *ngIf="ran[j.id]" class="small text-success ms-1">✓ ran</span>
          </td>
        </tr>
        <tr *ngIf="jobs.length === 0"><td colspan="6" class="text-muted small">No schedules yet.</td></tr>
      </tbody>
    </table>`,
})
export class SchedulesComponent implements OnInit {
  private api = inject(ApiService);
  jobs: ScheduledJob[] = [];
  conns: Record<number, Connection> = {};
  ran: Record<number, boolean> = {};
  editing = false;
  editTarget?: ScheduledJob;

  ngOnInit() {
    this.api.connections().subscribe(cs => cs.forEach(c => (this.conns[c.id] = c)));
    this.load();
  }
  load() { this.api.schedules().subscribe(j => (this.jobs = j)); }
  name(id: number) { return this.conns[id]?.name ?? id; }
  startNew() { this.editTarget = undefined; this.editing = true; }
  edit(j: ScheduledJob) { this.editTarget = j; this.editing = true; }
  onSave(r: ScheduleRequest) {
    const done = () => { this.editing = false; this.load(); };
    if (this.editTarget) this.api.updateSchedule(this.editTarget.id, r).subscribe(done);
    else this.api.createSchedule(r).subscribe(done);
  }
  run(j: ScheduledJob) { this.api.runSchedule(j.id).subscribe(() => (this.ran[j.id] = true)); }
  remove(j: ScheduledJob) { this.api.deleteSchedule(j.id).subscribe(() => this.load()); }
}
