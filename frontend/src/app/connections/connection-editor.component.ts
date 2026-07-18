import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Connection, ConnectionRequest } from '../core/models';
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
