package com.orga.usersync.watch;

import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.SyncPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WatchService {
    private static final Logger log = LoggerFactory.getLogger(WatchService.class);

    private final UserWatchSink repo;
    private final WatchMemberSink members;
    private final ReconcileService reconcile;
    private final KeycloakReconcileGateway gateway;
    private final ConnectionService connections;
    private final TaskScheduler scheduler;
    private final Map<Long, ScheduledFuture<?>> registrations = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();

    public WatchService(UserWatchSink repo, WatchMemberSink members, ReconcileService reconcile,
                        KeycloakReconcileGateway gateway, ConnectionService connections, TaskScheduler scheduler) {
        this.repo = repo; this.members = members; this.reconcile = reconcile;
        this.gateway = gateway; this.connections = connections; this.scheduler = scheduler;
    }

    public List<UserWatch> list() { return repo.findAll(); }

    public UserWatch create(UserWatchRequest r) { return saveAndRegister(new UserWatch(), r); }

    public UserWatch update(Long id, UserWatchRequest r) {
        return saveAndRegister(get(id), r);
    }

    public void delete(Long id) {
        unregister(id);
        members.deleteByWatchId(id);
        repo.deleteById(id);
    }

    public SyncResult runNow(Long id) {
        UserWatch w = get(id);
        return reconcile.reconcile(w, "watch:" + w.getName());
    }

    public SyncPlan preview(Long id) { return reconcile.plan(get(id)); }

    public List<WatchMember> members(Long id) { return members.findByWatchId(id); }

    public List<String> sourceUsers(Long connId) {
        return gateway.readSourceConnection(connections.getEntity(connId))
            .stream().map(UserDto::username).sorted().toList();
    }

    private UserWatch get(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("no watch " + id));
    }

    private UserWatch saveAndRegister(UserWatch w, UserWatchRequest r) {
        w.setName(r.name()); w.setType(r.type());
        w.setSourceConnId(r.sourceConnId()); w.setTargetConnId(r.targetConnId());
        w.setSelectionMode(r.selectionMode()); w.setSelectionPayload(r.selectionPayload());
        w.setIncludeRoles(r.includeRoles()); w.setOnDelete(r.onDelete());
        w.setRunMode(r.runMode()); w.setCron(r.cron()); w.setEnabled(r.enabled());
        UserWatch saved = repo.save(w);
        unregister(saved.getId());
        register(saved);
        return saved;
    }

    public void register(UserWatch w) {
        if (!w.isEnabled()) return;
        ScheduledFuture<?> f = scheduler.schedule(() -> executeGuarded(w), new CronTrigger(w.getCron()));
        if (f != null) registrations.put(w.getId(), f);
    }

    public void unregister(Long id) {
        ScheduledFuture<?> f = registrations.remove(id);
        if (f != null) f.cancel(false);
    }

    /** Runnable body with a per-watch overlap guard. */
    public void executeGuarded(UserWatch w) {
        AtomicBoolean lock = running.computeIfAbsent(w.getId(), k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            log.warn("watch {} still running; skipping this tick", w.getId());
            return;
        }
        try { reconcile.reconcile(w, "watch:" + w.getName()); }
        catch (RuntimeException e) { log.error("watch {} failed: {}", w.getId(), e.getMessage()); }
        finally { lock.set(false); }
    }
}
