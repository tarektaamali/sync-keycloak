package com.orga.usersync.connection;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {
    private final ConnectionService svc;
    private final ConnectionTestService tester;

    public ConnectionController(ConnectionService svc, ConnectionTestService tester) {
        this.svc = svc; this.tester = tester;
    }

    @GetMapping public List<ConnectionView> list() { return svc.list(); }

    @PostMapping public ConnectionView create(@RequestBody ConnectionRequest r) { return svc.create(r); }

    @PutMapping("/{id}") public ConnectionView update(@PathVariable Long id, @RequestBody ConnectionRequest r) {
        return svc.update(id, r);
    }

    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id) {
        svc.delete(id); return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ConnectionTestService.TestResult test(@PathVariable Long id) { return tester.test(id); }
}
