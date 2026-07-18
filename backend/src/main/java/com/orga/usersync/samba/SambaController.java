package com.orga.usersync.samba;

import com.orga.usersync.model.SyncRequest;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/samba")
public class SambaController {
    private final SambaSyncService svc;
    public SambaController(SambaSyncService svc) { this.svc = svc; }

    @GetMapping("/users")
    public List<UserDto> users() { return svc.listUsers(); }

    @PostMapping("/sync")
    public SyncResult sync(@RequestBody SyncRequest req) { return svc.sync(req); }
}
