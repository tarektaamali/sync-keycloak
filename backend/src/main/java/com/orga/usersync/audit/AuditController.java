package com.orga.usersync.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditService svc;
    public AuditController(AuditService svc) { this.svc = svc; }

    @GetMapping public List<SyncRun> list() { return svc.list(); }
}
