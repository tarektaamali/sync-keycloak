package com.orga.usersync.watch;

import java.util.List;

public interface WatchMemberSink {
    WatchMember save(WatchMember m);
    List<WatchMember> findByWatchId(Long watchId);
    void deleteByWatchId(Long watchId);
}
