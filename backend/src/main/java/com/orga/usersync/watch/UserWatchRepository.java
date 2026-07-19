package com.orga.usersync.watch;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserWatchRepository extends JpaRepository<UserWatch, Long>, UserWatchSink {
}
