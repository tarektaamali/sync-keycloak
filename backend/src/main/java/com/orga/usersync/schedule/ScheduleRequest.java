package com.orga.usersync.schedule;

import com.orga.usersync.model.SyncMode;

public record ScheduleRequest(String name, ScheduleType type, Long sourceConnId, Long targetConnId,
                              SyncMode mode, boolean includeRoles, String cron, boolean enabled) {}
