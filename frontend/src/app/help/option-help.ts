import { SyncMode } from '../core/models';

export const MODE_HELP: Record<SyncMode, { summary: string; example: string }> = {
  CREATE_ONLY: {
    summary: 'Adds users missing in the target; never touches existing ones.',
    example: 'Target has alice. Source has alice, bruno → bruno created, alice skipped.',
  },
  CREATE_UPDATE: {
    summary: 'Upsert: creates missing users and refreshes details of existing ones. Never deletes.',
    example: "alice's email changed at source → alice updated; bruno new → created; nothing deleted.",
  },
  MIRROR: {
    summary: 'Makes the target match the source exactly, including DELETING target users not in the source.',
    example: 'Target has alice, stale. Source has alice → alice updated, stale DELETED.',
  },
};

export const INCLUDE_ROLES_HELP = {
  summary: 'Also copy each user’s realm roles, creating any missing roles in the target.',
  example: 'carla has auditor+teller; target lacks auditor → auditor role auto-created, then assigned.',
};
