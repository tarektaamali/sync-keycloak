import { Component, inject, OnInit } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { CommonModule } from '@angular/common';
import { KeycloakTabComponent } from './keycloak-tab.component';
import { SambaTabComponent } from './samba-tab.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, KeycloakTabComponent, SambaTabComponent],
  template: `
    <ng-container *ngIf="authenticated; else loginTpl">
      <header><span>Keycloak User Sync</span><button (click)="logout()">Logout</button></header>
      <main>
        <nav>
          <button (click)="tab='keycloak'">Keycloak UBS→CS</button>
          <button (click)="tab='samba'">Samba</button>
        </nav>
        <keycloak-tab *ngIf="tab==='keycloak'"></keycloak-tab>
        <samba-tab *ngIf="tab==='samba'"></samba-tab>
      </main>
    </ng-container>
    <ng-template #loginTpl><button (click)="login()">Login</button></ng-template>
  `,
})
export class AppComponent implements OnInit {
  private oidc = inject(OidcSecurityService);
  authenticated = false;
  tab: 'keycloak' | 'samba' = 'keycloak';
  ngOnInit() { this.oidc.checkAuth().subscribe(r => (this.authenticated = r.isAuthenticated)); }
  login() { this.oidc.authorize(); }
  logout() { this.oidc.logoff().subscribe(); }
}
