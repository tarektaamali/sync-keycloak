import { Component, inject, OnInit } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule],
  template: `
    <ng-container *ngIf="authenticated; else loginTpl">
      <header><span>Keycloak User Sync</span><button (click)="logout()">Logout</button></header>
      <main><!-- tabs added in Task 10 --></main>
    </ng-container>
    <ng-template #loginTpl><button (click)="login()">Login</button></ng-template>
  `,
})
export class AppComponent implements OnInit {
  private oidc = inject(OidcSecurityService);
  authenticated = false;
  ngOnInit() { this.oidc.checkAuth().subscribe(r => (this.authenticated = r.isAuthenticated)); }
  login() { this.oidc.authorize(); }
  logout() { this.oidc.logoff().subscribe(); }
}
