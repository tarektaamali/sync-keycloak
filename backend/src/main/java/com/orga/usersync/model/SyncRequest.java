package com.orga.usersync.model;

public record SyncRequest(SyncMode mode, boolean includeRoles, String target) {}
