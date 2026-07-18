package com.orga.usersync.schedule;

import com.orga.usersync.model.SyncResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final ScheduleService svc;
    public ScheduleController(ScheduleService svc) { this.svc = svc; }

    @GetMapping public List<ScheduledJob> list() { return svc.list(); }
    @PostMapping public ScheduledJob create(@RequestBody ScheduleRequest r) { return svc.create(r); }
    @PutMapping("/{id}") public ScheduledJob update(@PathVariable Long id, @RequestBody ScheduleRequest r) { return svc.update(id, r); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id) { svc.delete(id); return ResponseEntity.noContent().build(); }
    @PostMapping("/{id}/run") public SyncResult runNow(@PathVariable Long id) { return svc.runNow(id); }
}
