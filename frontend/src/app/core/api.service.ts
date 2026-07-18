import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { UserDto, SyncRequest, SyncResult } from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  private base = 'http://localhost:9090/api';
  keycloakUsers(): Observable<UserDto[]> { return this.http.get<UserDto[]>(`${this.base}/keycloak/users`); }
  keycloakSync(r: SyncRequest): Observable<SyncResult> { return this.http.post<SyncResult>(`${this.base}/keycloak/sync`, r); }
  sambaUsers(): Observable<UserDto[]> { return this.http.get<UserDto[]>(`${this.base}/samba/users`); }
  sambaSync(r: SyncRequest): Observable<SyncResult> { return this.http.post<SyncResult>(`${this.base}/samba/sync`, r); }
}
