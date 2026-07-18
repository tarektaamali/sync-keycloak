import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SyncMode, SyncRequest } from '../core/models';

@Component({
  selector: 'sync-panel', standalone: true, imports: [CommonModule, FormsModule],
  template: `
    <div class="panel">
      <span>{{source}} → {{target || 'target'}}</span>
      <select [(ngModel)]="mode">
        <option value="CREATE_ONLY">Create only</option>
        <option value="CREATE_UPDATE">Create + update</option>
        <option value="MIRROR">Mirror</option>
      </select>
      <label><input type="checkbox" [(ngModel)]="includeRoles"> Include roles</label>
      <button (click)="emit()">Sync</button>
    </div>`,
})
export class SyncPanelComponent {
  @Input() source = '';
  @Input() target?: string;
  @Output() sync = new EventEmitter<SyncRequest>();
  mode: SyncMode = 'CREATE_UPDATE';
  includeRoles = false;
  emit() { this.sync.emit({ mode: this.mode, includeRoles: this.includeRoles, target: this.target }); }
}
