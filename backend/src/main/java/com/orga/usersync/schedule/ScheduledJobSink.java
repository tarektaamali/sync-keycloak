package com.orga.usersync.schedule;

import java.util.List;
import java.util.Optional;

public interface ScheduledJobSink {
    ScheduledJob save(ScheduledJob j);
    Optional<ScheduledJob> findById(Long id);
    List<ScheduledJob> findAll();
    void deleteById(Long id);
}
