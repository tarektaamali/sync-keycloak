package com.orga.usersync.schedule;

import com.orga.usersync.keycloak.KeycloakSyncService;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.samba.SambaSyncService;
import org.springframework.stereotype.Component;

@Component
public class SyncDispatcher {
    private final KeycloakSyncService keycloak;
    private final SambaSyncService samba;

    public SyncDispatcher(KeycloakSyncService keycloak, SambaSyncService samba) {
        this.keycloak = keycloak; this.samba = samba;
    }

    public SyncResult run(ScheduledJob job) {
        if (job.getType() == ScheduleType.KEYCLOAK)
            return keycloak.sync(job.getSourceConnId(), job.getTargetConnId(), job.getMode(), job.isIncludeRoles(), "scheduler");
        return samba.sync(job.getSourceConnId(), job.getTargetConnId(), job.getMode(), job.isIncludeRoles(), "scheduler");
    }
}
