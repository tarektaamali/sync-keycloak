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
