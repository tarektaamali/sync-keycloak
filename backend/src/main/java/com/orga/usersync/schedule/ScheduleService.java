package com.orga.usersync.schedule;

import com.orga.usersync.model.SyncResult;
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
public class ScheduleService {
    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final ScheduledJobSink repo;
    private final SyncDispatcher dispatcher;
    private final TaskScheduler scheduler;
    private final Map<Long, ScheduledFuture<?>> registrations = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();

    public ScheduleService(ScheduledJobSink repo, SyncDispatcher dispatcher, TaskScheduler scheduler) {
        this.repo = repo; this.dispatcher = dispatcher; this.scheduler = scheduler;
    }

    public List<ScheduledJob> list() { return repo.findAll(); }

    public ScheduledJob create(ScheduleRequest r) { return saveAndRegister(new ScheduledJob(), r); }

    public ScheduledJob update(Long id, ScheduleRequest r) {
        ScheduledJob j = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("no schedule " + id));
        return saveAndRegister(j, r);
    }

    public void delete(Long id) { unregister(id); repo.deleteById(id); }

    public SyncResult runNow(Long id) {
        return dispatcher.run(repo.findById(id).orElseThrow(() -> new IllegalArgumentException("no schedule " + id)));
    }

    private ScheduledJob saveAndRegister(ScheduledJob j, ScheduleRequest r) {
        j.setName(r.name()); j.setType(r.type()); j.setSourceConnId(r.sourceConnId());
        j.setTargetConnId(r.targetConnId()); j.setMode(r.mode()); j.setIncludeRoles(r.includeRoles());
        j.setCron(r.cron()); j.setEnabled(r.enabled());
        ScheduledJob saved = repo.save(j);
        unregister(saved.getId());
        register(saved);
        return saved;
    }

    public void register(ScheduledJob job) {
        if (!job.isEnabled()) return;
        ScheduledFuture<?> f = scheduler.schedule(() -> executeGuarded(job), new CronTrigger(job.getCron()));
        if (f != null) registrations.put(job.getId(), f);
    }

    public void unregister(Long id) {
        ScheduledFuture<?> f = registrations.remove(id);
        if (f != null) f.cancel(false);
    }

    /** Runnable body with a per-job overlap guard. */
    public void executeGuarded(ScheduledJob job) {
        AtomicBoolean lock = running.computeIfAbsent(job.getId(), k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            log.warn("schedule {} still running; skipping this tick", job.getId());
            return;
        }
        try { dispatcher.run(job); }
        catch (RuntimeException e) { log.error("scheduled sync {} failed: {}", job.getId(), e.getMessage()); }
        finally { lock.set(false); }
    }
}
