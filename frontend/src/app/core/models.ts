export type SyncMode = 'CREATE_ONLY' | 'CREATE_UPDATE' | 'MIRROR';

export interface UserDto {
  username: string; email: string; firstName: string; lastName: string;
  enabled: boolean; roles: string[];
}
export interface SyncRequest { mode: SyncMode; includeRoles: boolean; target?: string; }
export interface SyncResult { created: number; updated: number; skipped: number; deleted: number; errors: string[]; }
