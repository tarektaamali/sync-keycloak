package com.orga.usersync.sync;

import com.orga.usersync.model.SyncMode;

public record SyncRequest2(Long sourceConnId, Long targetConnId, SyncMode mode, boolean includeRoles) {}
