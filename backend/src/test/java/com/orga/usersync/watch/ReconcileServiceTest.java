package com.orga.usersync.watch;

import com.orga.usersync.audit.AuditService;
import com.orga.usersync.audit.SyncRun;
import com.orga.usersync.audit.SyncRunSink;
import com.orga.usersync.connection.Connection;
import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconcileServiceTest {

    /** Records gateway mutations so tests can assert what was applied. */
    static class FakeGateway implements ReconcileGateway {
        List<UserDto> source = new ArrayList<>();
        Set<String> target = new HashSet<>();
        List<String> created = new ArrayList<>(), updated = new ArrayList<>(),
                     disabled = new ArrayList<>(), deleted = new ArrayList<>();
        public List<UserDto> readSource(UserWatch w) { return source; }
        public Set<String> targetUsernames(UserWatch w) { return target; }
        public void create(UserWatch w, UserDto u) { created.add(u.username()); }
        public void update(UserWatch w, UserDto u) { updated.add(u.username()); }
        public void disable(UserWatch w, String username) { disabled.add(username); }
        public void delete(UserWatch w, String username) { deleted.add(username); }
    }

    static class FakeMembers implements WatchMemberSink {
        Map<String, WatchMember> byName = new LinkedHashMap<>();
        public WatchMember save(WatchMember m) { byName.put(m.getUsername(), m); return m; }
        public List<WatchMember> findByWatchId(Long id) { return new ArrayList<>(byName.values()); }
        public void deleteByWatchId(Long id) { byName.clear(); }
    }

    static class FakeRunSink implements SyncRunSink {
        List<SyncRun> saved = new ArrayList<>();
        public SyncRun save(SyncRun r) { saved.add(r); return r; }
        public List<SyncRun> findAllByOrderByTimestampDesc() { return saved; }
    }

    static UserDto user(String n, boolean enabled) { return new UserDto(n, n + "@x", "F", "L", enabled, List.of()); }

    static UserWatch watch(RunMode runMode, OnDeletePolicy onDelete) {
        UserWatch w = new UserWatch();
        w.setId(7L); w.setName("tellers"); w.setType(WatchType.KEYCLOAK);
        w.setSourceConnId(1L); w.setTargetConnId(2L);
        w.setSelectionMode(SelectionMode.LIST); w.setSelectionPayload("alice");
        w.setOnDelete(onDelete); w.setRunMode(runMode);
        return w;
    }

    private ConnectionService connections() {
        ConnectionService cs = mock(ConnectionService.class);
        Connection src = new Connection(); src.setName("UBS");
        Connection dst = new Connection(); dst.setName("CS");
        when(cs.getEntity(1L)).thenReturn(src);
        when(cs.getEntity(2L)).thenReturn(dst);
        return cs;
    }

    @Test void enforce_creates_missing_user_and_records_run() {
        FakeGateway gw = new FakeGateway();
        gw.source.add(user("alice", true));           // present, enabled
        FakeMembers members = new FakeMembers();
        FakeRunSink runs = new FakeRunSink();
        ReconcileService svc = new ReconcileService(gw, members, new AuditService(runs), connections());

        SyncResult r = svc.reconcile(watch(RunMode.ENFORCE, OnDeletePolicy.DISABLE), "watch:tellers");

        assertEquals(List.of("alice"), gw.created);
        assertEquals(1, r.created());
        assertEquals(WatchMemberState.PRESENT, members.byName.get("alice").getLastState());
        assertEquals("ENFORCE", runs.saved.get(0).getMode());
        assertEquals("OK", runs.saved.get(0).getStatus());
    }

    @Test void report_only_mutates_nothing_but_records_plan() {
        FakeGateway gw = new FakeGateway();
        gw.source.add(user("alice", true));
        FakeMembers members = new FakeMembers();
        FakeRunSink runs = new FakeRunSink();
        ReconcileService svc = new ReconcileService(gw, members, new AuditService(runs), connections());

        SyncResult r = svc.reconcile(watch(RunMode.REPORT_ONLY, OnDeletePolicy.DISABLE), "watch:tellers");

        assertTrue(gw.created.isEmpty(), "report-only must not create");
        assertEquals(1, r.created(), "but the plan still reports the would-be create");
        assertEquals("REPORT", runs.saved.get(0).getStatus());
    }

    @Test void removed_member_is_disabled_under_disable_policy() {
        FakeGateway gw = new FakeGateway();
        gw.target.add("alice");                        // on target, absent from source
        FakeMembers members = new FakeMembers();
        WatchMember prior = new WatchMember();
        prior.setWatchId(7L); prior.setUsername("alice"); prior.setLastState(WatchMemberState.PRESENT);
        members.byName.put("alice", prior);
        FakeRunSink runs = new FakeRunSink();
        ReconcileService svc = new ReconcileService(gw, members, new AuditService(runs), connections());

        SyncResult r = svc.reconcile(watch(RunMode.ENFORCE, OnDeletePolicy.DISABLE), "watch:tellers");

        assertEquals(List.of("alice"), gw.disabled);
        assertEquals(1, r.disabled());
        assertEquals(WatchMemberState.REMOVED, members.byName.get("alice").getLastState());
    }

    @Test void plan_is_readonly_preview() {
        FakeGateway gw = new FakeGateway();
        gw.source.add(user("alice", true));
        ReconcileService svc = new ReconcileService(gw, new FakeMembers(),
            new AuditService(new FakeRunSink()), connections());

        var plan = svc.plan(watch(RunMode.ENFORCE, OnDeletePolicy.DISABLE));

        assertEquals(1, plan.created());
        assertTrue(gw.created.isEmpty(), "preview must not mutate");
    }
}
