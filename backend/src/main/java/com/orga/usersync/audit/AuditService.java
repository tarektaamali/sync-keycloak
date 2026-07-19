package com.orga.usersync.audit;

import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class AuditService {
    private final SyncRunSink sink;
    public AuditService(SyncRunSink sink) { this.sink = sink; }

    public void record(String actor, String src, String dst, SyncMode mode, boolean includeRoles, SyncResult r) {
        SyncRun run = new SyncRun();
        run.setTimestamp(Instant.now());
        run.setActor(actor); run.setSourceConn(src); run.setTargetConn(dst);
        run.setMode(mode.name()); run.setIncludeRoles(includeRoles);
        run.setCreated(r.created()); run.setUpdated(r.updated());
        run.setDeleted(r.deleted()); run.setSkipped(r.skipped());
        run.setDisabled(r.disabled());
        run.setErrorCount(r.errors().size());
        run.setStatus(r.errors().isEmpty() ? "OK" : "PARTIAL");
        sink.save(run);
    }

    public void recordWatch(String actor, String src, String dst, String modeLabel,
                            boolean includeRoles, SyncResult r, boolean reportOnly) {
        SyncRun run = new SyncRun();
        run.setTimestamp(Instant.now());
        run.setActor(actor); run.setSourceConn(src); run.setTargetConn(dst);
        run.setMode(modeLabel); run.setIncludeRoles(includeRoles);
        run.setCreated(r.created()); run.setUpdated(r.updated());
        run.setDeleted(r.deleted()); run.setSkipped(r.skipped()); run.setDisabled(r.disabled());
        run.setErrorCount(r.errors().size());
        run.setStatus(reportOnly ? "REPORT" : (r.errors().isEmpty() ? "OK" : "PARTIAL"));
        sink.save(run);
    }

    public List<SyncRun> list() { return sink.findAllByOrderByTimestampDesc(); }
}
