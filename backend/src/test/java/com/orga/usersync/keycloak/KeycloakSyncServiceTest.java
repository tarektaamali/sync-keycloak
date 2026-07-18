package com.orga.usersync.keycloak;

import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.SyncPlan;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class KeycloakSyncServiceTest {
    static UserDto user(String name) { return new UserDto(name, name + "@x", "F", "L", true, List.of("teller")); }

    private final KeycloakSyncService svc = new KeycloakSyncService(null, null, null);

    @Test void computePlan_marks_actions_by_mode() {
        var source = List.of(user("alice"), user("bruno"));
        var existing = new HashSet<>(List.of("alice", "stale"));
        SyncPlan plan = svc.computePlan(source, existing, SyncMode.MIRROR);
        assertEquals(1, plan.created());   // bruno
        assertEquals(1, plan.updated());   // alice
        assertEquals(1, plan.deleted());   // stale
    }

    @Test void createOnly_skips_existing() {
        SyncPlan plan = svc.computePlan(List.of(user("alice"), user("bruno")),
            new HashSet<>(List.of("alice")), SyncMode.CREATE_ONLY);
        assertEquals(1, plan.created());   // bruno
        assertEquals(1, plan.skipped());   // alice
    }
}
