package com.orga.usersync.audit;

import java.util.List;

public interface SyncRunSink {
    SyncRun save(SyncRun r);
    List<SyncRun> findAllByOrderByTimestampDesc();
}
