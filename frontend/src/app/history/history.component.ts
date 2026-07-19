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
          <td class="small">+{{r.created}} ~{{r.updated}} ⊘{{r.disabled}} -{{r.deleted}} ={{r.skipped}}
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
