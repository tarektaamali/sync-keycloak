import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Connection, ConnectionRequest, SyncRunRequest, SyncPlan, SyncResult, SyncRun, TestResult, ScheduledJob, ScheduleRequest, UserWatch, UserWatchRequest, WatchMember } from './models';

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

  schedules(): Observable<ScheduledJob[]> { return this.http.get<ScheduledJob[]>(`${this.base}/schedules`); }
  createSchedule(r: ScheduleRequest): Observable<ScheduledJob> { return this.http.post<ScheduledJob>(`${this.base}/schedules`, r); }
  updateSchedule(id: number, r: ScheduleRequest): Observable<ScheduledJob> { return this.http.put<ScheduledJob>(`${this.base}/schedules/${id}`, r); }
  deleteSchedule(id: number): Observable<void> { return this.http.delete<void>(`${this.base}/schedules/${id}`); }
  runSchedule(id: number): Observable<SyncResult> { return this.http.post<SyncResult>(`${this.base}/schedules/${id}/run`, {}); }

  watches(): Observable<UserWatch[]> { return this.http.get<UserWatch[]>(`${this.base}/watches`); }
  createWatch(r: UserWatchRequest): Observable<UserWatch> { return this.http.post<UserWatch>(`${this.base}/watches`, r); }
  updateWatch(id: number, r: UserWatchRequest): Observable<UserWatch> { return this.http.put<UserWatch>(`${this.base}/watches/${id}`, r); }
  deleteWatch(id: number): Observable<void> { return this.http.delete<void>(`${this.base}/watches/${id}`); }
  runWatch(id: number): Observable<SyncResult> { return this.http.post<SyncResult>(`${this.base}/watches/${id}/run`, {}); }
  previewWatch(id: number): Observable<SyncPlan> { return this.http.get<SyncPlan>(`${this.base}/watches/${id}/preview`); }
  watchMembers(id: number): Observable<WatchMember[]> { return this.http.get<WatchMember[]>(`${this.base}/watches/${id}/members`); }
  watchSourceUsers(connId: number): Observable<string[]> { return this.http.get<string[]>(`${this.base}/watches/source-users/${connId}`); }
}
