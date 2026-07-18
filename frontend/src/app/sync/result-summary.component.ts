import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SyncResult } from '../core/models';

@Component({
  selector: 'result-summary', standalone: true, imports: [CommonModule],
  template: `
    <div *ngIf="result" class="summary">
      Created: {{result.created}} · Updated: {{result.updated}} ·
      Skipped: {{result.skipped}} · Deleted: {{result.deleted}}
      <ul><li *ngFor="let e of result.errors" class="err">{{e}}</li></ul>
    </div>`,
})
export class ResultSummaryComponent { @Input() result: SyncResult | null = null; }
