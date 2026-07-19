package com.orga.usersync.watch;

import com.orga.usersync.model.SyncResult;
import com.orga.usersync.sync.SyncPlan;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/watches")
public class WatchController {
    private final WatchService svc;
    public WatchController(WatchService svc) { this.svc = svc; }

    @GetMapping public List<UserWatch> list() { return svc.list(); }
    @PostMapping public UserWatch create(@RequestBody UserWatchRequest r) { return svc.create(r); }
    @PutMapping("/{id}") public UserWatch update(@PathVariable Long id, @RequestBody UserWatchRequest r) { return svc.update(id, r); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id) { svc.delete(id); return ResponseEntity.noContent().build(); }
    @PostMapping("/{id}/run") public SyncResult runNow(@PathVariable Long id) { return svc.runNow(id); }
    @GetMapping("/{id}/preview") public SyncPlan preview(@PathVariable Long id) { return svc.preview(id); }
    @GetMapping("/{id}/members") public List<WatchMember> members(@PathVariable Long id) { return svc.members(id); }
    @GetMapping("/source-users/{connId}") public List<String> sourceUsers(@PathVariable Long connId) { return svc.sourceUsers(connId); }
}
