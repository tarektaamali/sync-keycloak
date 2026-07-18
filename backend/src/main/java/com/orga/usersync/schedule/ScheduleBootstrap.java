package com.orga.usersync.schedule;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ScheduleBootstrap implements ApplicationRunner {
    private final ScheduleService svc;
    private final ScheduledJobRepository repo;
    public ScheduleBootstrap(ScheduleService svc, ScheduledJobRepository repo) { this.svc = svc; this.repo = repo; }

    @Override public void run(ApplicationArguments args) {
        repo.findAll().stream().filter(ScheduledJob::isEnabled).forEach(svc::register);
    }
}
