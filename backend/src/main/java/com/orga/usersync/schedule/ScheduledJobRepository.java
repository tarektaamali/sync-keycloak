package com.orga.usersync.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledJobRepository extends JpaRepository<ScheduledJob, Long>, ScheduledJobSink {
}
