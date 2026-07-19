import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <ng-container *ngIf="authenticated; else loginTpl">
      <div class="d-flex vh-100">
        <nav class="text-white p-3 d-flex flex-column" style="width:230px;background:#0d3b66">
          <h6 class="mb-4">🔐 KC User Sync</h6>
          <a class="nav-link text-white-50 mb-1" routerLink="/connections" routerLinkActive="text-white fw-bold">🔌 Connections</a>
          <a class="nav-link text-white-50 mb-1" routerLink="/sync/keycloak" routerLinkActive="text-white fw-bold">↔ Keycloak → KC</a>
          <a class="nav-link text-white-50 mb-1" routerLink="/sync/samba" routerLinkActive="text-white fw-bold">↔ Samba → KC</a>
          <a class="nav-link text-white-50 mb-1" routerLink="/schedules" routerLinkActive="text-white fw-bold">⏰ Schedules</a>
          <a class="nav-link text-white-50 mb-1" routerLink="/watches" routerLinkActive="text-white fw-bold">👁 Watches</a>
          <a class="nav-link text-white-50 mb-1" routerLink="/history" routerLinkActive="text-white fw-bold">📘 History</a>
          <div class="mt-auto small">
            <div class="text-white-50 mb-1">{{ username }}</div>
            <button class="btn btn-sm btn-outline-light" (click)="logout()">Logout</button>
          </div>
        </nav>
        <main class="flex-grow-1 p-4 overflow-auto bg-light"><router-outlet></router-outlet></main>
      </div>
    </ng-container>
    <ng-template #loginTpl>
      <div class="d-flex vh-100 align-items-center justify-content-center bg-light">
        <div class="card shadow-sm p-4 text-center">
          <h5 class="mb-3">🔐 Keycloak User Sync</h5>
          <button class="btn btn-primary" (click)="login()">Login</button>
        </div>
      </div>
    </ng-template>
  `,
})
export class AppComponent implements OnInit {
  private oidc = inject(OidcSecurityService);
  authenticated = false;
  username = '';
  ngOnInit() {
    this.oidc.checkAuth().subscribe(r => {
      this.authenticated = r.isAuthenticated;
      this.username = (r.userData?.preferred_username as string) ?? '';
    });
  }
  login() { this.oidc.authorize(); }
  logout() { this.oidc.logoff().subscribe(); }
}
