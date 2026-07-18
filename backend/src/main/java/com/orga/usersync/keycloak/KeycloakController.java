package com.orga.usersync.keycloak;

import com.orga.usersync.model.SyncRequest;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/keycloak")
public class KeycloakController {
    private final KeycloakSyncService svc;
    public KeycloakController(KeycloakSyncService svc) { this.svc = svc; }

    @GetMapping("/users")
    public List<UserDto> users() { return svc.listSourceUsers(); }

    @PostMapping("/sync")
    public SyncResult sync(@RequestBody SyncRequest req) {
        return svc.sync(req.mode(), req.includeRoles());
    }
}
