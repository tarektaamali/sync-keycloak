package com.orga.usersync.model;

import java.util.List;

public record SyncResult(int created, int updated, int skipped, int deleted, List<String> errors) {}
