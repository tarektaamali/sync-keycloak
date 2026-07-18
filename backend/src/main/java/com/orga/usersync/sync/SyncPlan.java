package com.orga.usersync.sync;

import java.util.List;

public record SyncPlan(List<PlannedAction> actions) {
    private long count(ActionType t) { return actions.stream().filter(a -> a.action() == t).count(); }
    public int created() { return (int) count(ActionType.CREATE); }
    public int updated() { return (int) count(ActionType.UPDATE); }
    public int deleted() { return (int) count(ActionType.DELETE); }
    public int skipped() { return (int) count(ActionType.SKIP); }
}
