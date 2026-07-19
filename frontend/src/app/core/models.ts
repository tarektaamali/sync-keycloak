export type SyncMode = 'CREATE_ONLY' | 'CREATE_UPDATE' | 'MIRROR';
export type ConnectionType = 'KEYCLOAK' | 'LDAP';
export type ActionType = 'CREATE' | 'UPDATE' | 'DELETE' | 'SKIP';

export interface Connection {
  id: number; name: string; type: ConnectionType; serverUrl: string;
  realm?: string; baseDn?: string; clientId?: string; bindDn?: string;
  userSearchBase?: string; secretRef: string;
}
export interface ConnectionRequest {
  name: string; type: ConnectionType; serverUrl: string;
  realm?: string; baseDn?: string; clientId?: string; bindDn?: string;
  userSearchBase?: string; secret?: string;
}
export interface SyncRunRequest { sourceConnId: number; targetConnId: number; mode: SyncMode; includeRoles: boolean; }
export interface PlannedAction { username: string; action: ActionType; }
export interface SyncPlan { actions: PlannedAction[]; }
export interface SyncResult { created: number; updated: number; skipped: number; deleted: number; disabled: number; errors: string[]; }
export interface TestResult { ok: boolean; message: string; }
export interface SyncRun {
  id: number; timestamp: string; actor: string; sourceConn: string; targetConn: string;
  mode: string; includeRoles: boolean; created: number; updated: number; deleted: number;
  skipped: number; disabled: number; errorCount: number; status: string;
}

export type ScheduleType = 'KEYCLOAK' | 'SAMBA';
export interface ScheduledJob {
  id: number; name: string; type: ScheduleType; sourceConnId: number; targetConnId: number;
  mode: SyncMode; includeRoles: boolean; cron: string; enabled: boolean;
}
export interface ScheduleRequest {
  name: string; type: ScheduleType; sourceConnId: number; targetConnId: number;
  mode: SyncMode; includeRoles: boolean; cron: string; enabled: boolean;
}

export type WatchType = 'KEYCLOAK' | 'SAMBA';
export type SelectionMode = 'LIST' | 'FILTER';
export type OnDeletePolicy = 'DISABLE' | 'DELETE' | 'IGNORE';
export type RunMode = 'REPORT_ONLY' | 'ENFORCE';
export type WatchMemberState = 'PRESENT' | 'DISABLED' | 'REMOVED';

export interface UserWatch {
  id: number; name: string; type: WatchType; sourceConnId: number; targetConnId: number;
  selectionMode: SelectionMode; selectionPayload: string; includeRoles: boolean;
  onDelete: OnDeletePolicy; runMode: RunMode; cron: string; enabled: boolean;
}
export interface UserWatchRequest {
  name: string; type: WatchType; sourceConnId: number; targetConnId: number;
  selectionMode: SelectionMode; selectionPayload: string; includeRoles: boolean;
  onDelete: OnDeletePolicy; runMode: RunMode; cron: string; enabled: boolean;
}
export interface WatchMember {
  id: number; watchId: number; username: string; lastState: WatchMemberState;
  firstSeen: string; lastSeen: string;
}
