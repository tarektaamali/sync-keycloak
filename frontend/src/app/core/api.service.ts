import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Connection, ConnectionRequest, SyncRunRequest, SyncPlan, SyncResult, SyncRun, TestResult } from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  private base = 'http://localhost:9090/api';

  connections(): Observable<Connection[]> { return this.http.get<Connection[]>(`${this.base}/connections`); }
  createConnection(r: ConnectionRequest): Observable<Connection> { return this.http.post<Connection>(`${this.base}/connections`, r); }
  updateConnection(id: number, r: ConnectionRequest): Observable<Connection> { return this.http.put<Connection>(`${this.base}/connections/${id}`, r); }
  deleteConnection(id: number): Observable<void> { return this.http.delete<void>(`${this.base}/connections/${id}`); }
  testConnection(id: number): Observable<TestResult> { return this.http.post<TestResult>(`${this.base}/connections/${id}/test`, {}); }

  keycloakPlan(r: SyncRunRequest): Observable<SyncPlan> { return this.http.post<SyncPlan>(`${this.base}/keycloak/plan`, r); }
  keycloakSync(r: SyncRunRequest): Observable<SyncResult> { return this.http.post<SyncResult>(`${this.base}/keycloak/sync`, r); }
  sambaPlan(r: SyncRunRequest): Observable<SyncPlan> { return this.http.post<SyncPlan>(`${this.base}/samba/plan`, r); }
  sambaSync(r: SyncRunRequest): Observable<SyncResult> { return this.http.post<SyncResult>(`${this.base}/samba/sync`, r); }
  audit(): Observable<SyncRun[]> { return this.http.get<SyncRun[]>(`${this.base}/audit`); }
}
