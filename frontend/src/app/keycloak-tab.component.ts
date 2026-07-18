import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from './core/api.service';
import { UserDto, SyncRequest, SyncResult } from './core/models';
import { UserTableComponent } from './sync/user-table.component';
import { SyncPanelComponent } from './sync/sync-panel.component';
import { ResultSummaryComponent } from './sync/result-summary.component';

@Component({
  selector: 'keycloak-tab', standalone: true,
  imports: [CommonModule, UserTableComponent, SyncPanelComponent, ResultSummaryComponent],
  template: `
    <sync-panel source="Keycloak UBS" target="cs" (sync)="run($event)"></sync-panel>
    <result-summary [result]="result"></result-summary>
    <user-table [users]="users"></user-table>`,
})
export class KeycloakTabComponent implements OnInit {
  private api = inject(ApiService);
  users: UserDto[] = [];
  result: SyncResult | null = null;
  ngOnInit() { this.load(); }
  load() { this.api.keycloakUsers().subscribe(u => (this.users = u)); }
  run(req: SyncRequest) { this.api.keycloakSync(req).subscribe(r => { this.result = r; this.load(); }); }
}
