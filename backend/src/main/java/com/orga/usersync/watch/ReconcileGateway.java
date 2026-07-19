package com.orga.usersync.watch;

import com.orga.usersync.model.UserDto;

import java.util.List;
import java.util.Set;

/** The single side-effect seam for reconciliation: read the source, and mutate the target. */
public interface ReconcileGateway {
    List<UserDto> readSource(UserWatch w);
    Set<String> targetUsernames(UserWatch w);
    void create(UserWatch w, UserDto u);
    void update(UserWatch w, UserDto u);
    void disable(UserWatch w, String username);
    void delete(UserWatch w, String username);
}
