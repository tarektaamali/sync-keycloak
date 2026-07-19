package com.orga.usersync.watch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface WatchMemberRepository extends JpaRepository<WatchMember, Long>, WatchMemberSink {
    @Transactional
    void deleteByWatchId(Long watchId);
}
