import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../core/api.service';
import { Connection, UserWatch, UserWatchRequest, WatchMember } from '../core/models';
import { WatchEditorComponent } from './watch-editor.component';

@Component({
  selector: 'watches-page', standalone: true,
  imports: [CommonModule, WatchEditorComponent],
  template: `
    <h4 class="mb-3">Watches</h4>
    <p class="text-muted small">Watch specific users and keep them reconciled on the target Keycloak.
       Defaults are safe: report-only, and disable (not delete) on removal.</p>
    <button class="btn btn-primary btn-sm mb-3" (click)="startNew()" *ngIf="!editing">+ New watch</button>
    <watch-editor *ngIf="editing" [existing]="editTarget"
      (save)="onSave($event)" (cancel)="editing=false" class="d-block mb-3"></watch-editor>

    <table class="table table-sm bg-white shadow-sm">
      <thead><tr><th>Name</th><th>Source → Target</th><th>Selection</th><th>On removal</th>
        <th>Mode</th><th>Cron</th><th>Enabled</th><th></th></tr></thead>
      <tbody>
        <tr *ngFor="let w of watches">
          <td>{{w.name}}</td>
          <td class="small">{{ name(w.sourceConnId) }} → {{ name(w.targetConnId) }}</td>
          <td class="small">{{ selectionSummary(w) }}</td>
          <td><span class="badge" [ngClass]="w.onDelete === 'DELETE' ? 'bg-danger' : 'bg-secondary'">{{ w.onDelete }}</span></td>
          <td><span class="badge" [ngClass]="w.runMode === 'ENFORCE' ? 'bg-warning text-dark' : 'bg-info text-dark'">{{ w.runMode === 'ENFORCE' ? 'ENFORCE' : 'REPORT' }}</span></td>
          <td><code class="small">{{w.cron}}</code></td>
          <td><span class="badge" [ngClass]="w.enabled ? 'bg-success' : 'bg-secondary'">{{ w.enabled ? 'on' : 'off' }}</span></td>
          <td class="text-nowrap">
            <button class="btn btn-outline-success btn-sm" (click)="run(w)">Run now</button>
            <button class="btn btn-outline-secondary btn-sm" (click)="showMembers(w)">Members</button>
            <button class="btn btn-outline-secondary btn-sm" (click)="edit(w)">Edit</button>
            <button class="btn btn-outline-danger btn-sm" (click)="remove(w)">Delete</button>
            <span *ngIf="ran[w.id]" class="small text-success ms-1">✓ ran</span>
          </td>
        </tr>
        <tr *ngIf="watches.length === 0"><td colspan="8" class="text-muted small">No watches yet.</td></tr>
      </tbody>
    </table>

    <div *ngIf="membersOf" class="card p-3 bg-white shadow-sm">
      <div class="d-flex justify-content-between"><h6>Members — {{ membersOf.name }}</h6>
        <button class="btn btn-sm btn-outline-secondary" (click)="membersOf=undefined">Close</button></div>
      <table class="table table-sm mb-0">
        <thead><tr><th>Username</th><th>Last state</th><th>First seen</th><th>Last seen</th></tr></thead>
        <tbody>
          <tr *ngFor="let m of members">
            <td class="small">{{m.username}}</td>
            <td><span class="badge bg-secondary">{{m.lastState}}</span></td>
            <td class="small">{{m.firstSeen}}</td><td class="small">{{m.lastSeen}}</td>
          </tr>
          <tr *ngIf="members.length === 0"><td colspan="4" class="text-muted small">No members recorded yet.</td></tr>
        </tbody>
      </table>
    </div>`,
})
export class WatchesComponent implements OnInit {
  private api = inject(ApiService);
  watches: UserWatch[] = [];
  conns: Record<number, Connection> = {};
  ran: Record<number, boolean> = {};
  editing = false;
  editTarget?: UserWatch;
  membersOf?: UserWatch;
  members: WatchMember[] = [];

  ngOnInit() {
    this.api.connections().subscribe(cs => cs.forEach(c => (this.conns[c.id] = c)));
    this.load();
  }
  load() { this.api.watches().subscribe(w => (this.watches = w)); }
  name(id: number) { return this.conns[id]?.name ?? id; }
  selectionSummary(w: UserWatch): string {
    if (w.selectionMode === 'FILTER') return `filter: ${w.selectionPayload || '(all)'}`;
    const n = w.selectionPayload.split(',').map(s => s.trim()).filter(Boolean).length;
    return `${n} user${n === 1 ? '' : 's'}`;
  }
  startNew() { this.editTarget = undefined; this.editing = true; }
  edit(w: UserWatch) { this.editTarget = w; this.editing = true; }
  onSave(r: UserWatchRequest) {
    const done = () => { this.editing = false; this.load(); };
    if (this.editTarget) this.api.updateWatch(this.editTarget.id, r).subscribe(done);
    else this.api.createWatch(r).subscribe(done);
  }
  run(w: UserWatch) { this.api.runWatch(w.id).subscribe(() => (this.ran[w.id] = true)); }
  showMembers(w: UserWatch) { this.membersOf = w; this.api.watchMembers(w.id).subscribe(m => (this.members = m)); }
  remove(w: UserWatch) { this.api.deleteWatch(w.id).subscribe(() => this.load()); }
}
