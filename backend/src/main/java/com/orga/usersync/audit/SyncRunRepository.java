package com.orga.usersync.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncRunRepository extends JpaRepository<SyncRun, Long>, SyncRunSink {
}
