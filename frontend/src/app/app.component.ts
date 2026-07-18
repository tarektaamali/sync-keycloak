import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterOutlet],
  template: `
    <ng-container *ngIf="authenticated; else loginTpl">
      <router-outlet></router-outlet>
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
