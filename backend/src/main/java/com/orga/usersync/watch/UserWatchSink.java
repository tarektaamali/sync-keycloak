package com.orga.usersync.watch;

import java.util.List;
import java.util.Optional;

public interface UserWatchSink {
    UserWatch save(UserWatch w);
    Optional<UserWatch> findById(Long id);
    List<UserWatch> findAll();
    void deleteById(Long id);
}
