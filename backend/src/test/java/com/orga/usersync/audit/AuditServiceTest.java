package com.orga.usersync.audit;

import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AuditServiceTest {
    static class FakeSink implements SyncRunSink {
        final List<SyncRun> saved = new ArrayList<>();
        public SyncRun save(SyncRun r) { saved.add(r); return r; }
        public List<SyncRun> findAllByOrderByTimestampDesc() { return saved; }
    }

    @Test void records_status_partial_when_errors() {
        FakeSink sink = new FakeSink();
        AuditService svc = new AuditService(sink);
        svc.record("admin", "UBS", "CS", SyncMode.CREATE_UPDATE, true,
            new SyncResult(2, 1, 0, 0, List.of("carla: boom")));
        assertEquals(1, sink.saved.size());
        SyncRun run = sink.saved.get(0);
        assertEquals("PARTIAL", run.getStatus());
        assertEquals(1, run.getErrorCount());
        assertEquals("UBS", run.getSourceConn());
    }
}
