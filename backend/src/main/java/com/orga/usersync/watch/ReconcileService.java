package com.orga.usersync.watch;

import com.orga.usersync.audit.AuditService;
import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.PlannedAction;
import com.orga.usersync.sync.SyncPlan;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class ReconcileService {
    private final ReconcileGateway gateway;
    private final WatchMemberSink members;
    private final AuditService audit;
    private final ConnectionService connections;

    public ReconcileService(ReconcileGateway gateway, WatchMemberSink members,
                            AuditService audit, ConnectionService connections) {
        this.gateway = gateway; this.members = members; this.audit = audit; this.connections = connections;
    }

    /** Read-only preview: the plan this watch would produce right now. */
    public SyncPlan plan(UserWatch w) {
        List<UserDto> source = gateway.readSource(w);
        Set<String> target = gateway.targetUsernames(w);
        return ReconcilePlanner.computePlan(w, source, target, priorLive(w));
    }

    /** Run the watch: apply (ENFORCE) or just measure (REPORT_ONLY), update members, and audit. */
    public SyncResult reconcile(UserWatch w, String actor) {
        List<UserDto> source = gateway.readSource(w);
        Set<String> target = gateway.targetUsernames(w);
        Set<String> priorLive = priorLive(w);
        SyncPlan plan = ReconcilePlanner.computePlan(w, source, target, priorLive);

        boolean reportOnly = w.getRunMode() == RunMode.REPORT_ONLY;
        SyncResult result = reportOnly ? measure(plan) : apply(w, plan, source);

        Set<String> governed = ReconcilePlanner.governed(w, source, priorLive);
        upsertMembers(w, ReconcilePlanner.memberStates(governed, source));

        audit.recordWatch(actor, connections.getEntity(w.getSourceConnId()).getName(),
            connections.getEntity(w.getTargetConnId()).getName(),
            w.getRunMode().name(), w.isIncludeRoles(), result, reportOnly);
        return result;
    }

    private Set<String> priorLive(UserWatch w) {
        Set<String> live = new LinkedHashSet<>();
        for (WatchMember m : members.findByWatchId(w.getId()))
            if (m.getLastState() != WatchMemberState.REMOVED) live.add(m.getUsername());
        return live;
    }

    private SyncResult measure(SyncPlan p) {
        return new SyncResult(p.created(), p.updated(), p.skipped(), p.deleted(), p.disabled(), List.of());
    }

    private SyncResult apply(UserWatch w, SyncPlan plan, List<UserDto> source) {
        Map<String, UserDto> byName = new HashMap<>();
        for (UserDto u : source) byName.put(u.username(), u);
        int created = 0, updated = 0, disabled = 0, deleted = 0, skipped = 0;
        List<String> errors = new ArrayList<>();
        for (PlannedAction a : plan.actions()) {
            try {
                switch (a.action()) {
                    case CREATE -> { gateway.create(w, byName.get(a.username())); created++; }
                    case UPDATE -> { gateway.update(w, byName.get(a.username())); updated++; }
                    case DISABLE -> { gateway.disable(w, a.username()); disabled++; }
                    case DELETE -> { gateway.delete(w, a.username()); deleted++; }
                    case SKIP -> skipped++;
                }
            } catch (RuntimeException e) { errors.add(a.username() + ": " + e.getMessage()); }
        }
        return new SyncResult(created, updated, skipped, deleted, disabled, errors);
    }

    private void upsertMembers(UserWatch w, Map<String, WatchMemberState> states) {
        Map<String, WatchMember> existing = new HashMap<>();
        for (WatchMember m : members.findByWatchId(w.getId())) existing.put(m.getUsername(), m);
        Instant now = Instant.now();
        for (Map.Entry<String, WatchMemberState> e : states.entrySet()) {
            WatchMember m = existing.get(e.getKey());
            if (m == null) {
                m = new WatchMember();
                m.setWatchId(w.getId()); m.setUsername(e.getKey()); m.setFirstSeen(now);
            }
            m.setLastState(e.getValue()); m.setLastSeen(now);
            members.save(m);
        }
    }
}
