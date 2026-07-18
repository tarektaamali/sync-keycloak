import { Routes } from '@angular/router';
import { ConnectionsComponent } from './connections/connections.component';
import { KeycloakSyncComponent } from './sync/keycloak-sync.component';
import { SambaSyncComponent } from './sync/samba-sync.component';
import { HistoryComponent } from './history/history.component';

export const routes: Routes = [
  { path: '', redirectTo: 'connections', pathMatch: 'full' },
  { path: 'connections', component: ConnectionsComponent },
  { path: 'sync/keycloak', component: KeycloakSyncComponent },
  { path: 'sync/samba', component: SambaSyncComponent },
  { path: 'history', component: HistoryComponent },
];
