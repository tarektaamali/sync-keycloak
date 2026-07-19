package com.orga.usersync.watch;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class WatchBootstrap implements ApplicationRunner {
    private final WatchService svc;
    private final UserWatchRepository repo;
    public WatchBootstrap(WatchService svc, UserWatchRepository repo) { this.svc = svc; this.repo = repo; }

    @Override public void run(ApplicationArguments args) {
        repo.findAll().stream().filter(UserWatch::isEnabled).forEach(svc::register);
    }
}
