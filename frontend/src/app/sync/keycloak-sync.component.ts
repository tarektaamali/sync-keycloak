import { Component } from '@angular/core';
import { SyncFlowComponent } from './sync-flow.component';
@Component({
  selector: 'keycloak-sync', standalone: true, imports: [SyncFlowComponent],
  template: `<sync-flow mode="keycloak"></sync-flow>`,
})
export class KeycloakSyncComponent {}
