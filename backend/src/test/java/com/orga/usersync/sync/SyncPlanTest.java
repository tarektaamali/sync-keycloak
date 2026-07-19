package com.orga.usersync.sync;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncPlanTest {
    @Test void counts_disabled_actions() {
        SyncPlan plan = new SyncPlan(List.of(
            new PlannedAction("alice", ActionType.DISABLE),
            new PlannedAction("bruno", ActionType.DISABLE),
            new PlannedAction("carla", ActionType.CREATE)));
        assertEquals(2, plan.disabled());
        assertEquals(1, plan.created());
    }
}
