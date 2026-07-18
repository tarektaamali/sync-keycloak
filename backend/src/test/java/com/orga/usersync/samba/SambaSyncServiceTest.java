package com.orga.usersync.samba;

import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.SyncPlan;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SambaSyncServiceTest {
    static UserDto u(String n) { return new UserDto(n, n + "@orga", "F", "L", true, List.of()); }
    private final SambaSyncService svc = new SambaSyncService(null, null, null, null);

    @Test void computePlan_create_only_skips_existing() {
        SyncPlan p = svc.computePlan(List.of(u("dmiller"), u("newbie")),
            new HashSet<>(List.of("dmiller")), SyncMode.CREATE_ONLY);
        assertEquals(1, p.created());   // newbie
        assertEquals(1, p.skipped());   // dmiller
    }
}
