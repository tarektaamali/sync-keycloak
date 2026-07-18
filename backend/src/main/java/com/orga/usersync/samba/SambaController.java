package com.orga.usersync.samba;

import com.orga.usersync.model.SyncResult;
import com.orga.usersync.sync.SyncPlan;
import com.orga.usersync.sync.SyncRequest2;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/samba")
public class SambaController {
    private final SambaSyncService svc;
    public SambaController(SambaSyncService svc) { this.svc = svc; }

    @PostMapping("/plan")
    public SyncPlan plan(@RequestBody SyncRequest2 r) {
        return svc.plan(r.sourceConnId(), r.targetConnId(), r.mode());
    }

    @PostMapping("/sync")
    public SyncResult sync(@RequestBody SyncRequest2 r, @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt != null ? jwt.getClaimAsString("preferred_username") : "unknown";
        return svc.sync(r.sourceConnId(), r.targetConnId(), r.mode(), r.includeRoles(),
            actor != null ? actor : "unknown");
    }
}
