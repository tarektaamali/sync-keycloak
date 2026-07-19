package com.orga.usersync.watch;

public record UserWatchRequest(String name, WatchType type, Long sourceConnId, Long targetConnId,
                               SelectionMode selectionMode, String selectionPayload, boolean includeRoles,
                               OnDeletePolicy onDelete, RunMode runMode, String cron, boolean enabled) {}
