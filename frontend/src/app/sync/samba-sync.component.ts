import { Component } from '@angular/core';
import { SyncFlowComponent } from './sync-flow.component';
@Component({
  selector: 'samba-sync', standalone: true, imports: [SyncFlowComponent],
  template: `<sync-flow mode="samba"></sync-flow>`,
})
export class SambaSyncComponent {}
