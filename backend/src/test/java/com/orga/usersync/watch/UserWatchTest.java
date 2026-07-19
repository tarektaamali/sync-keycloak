package com.orga.usersync.watch;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserWatchTest {
    @Test void defaults_and_setters_round_trip() {
        UserWatch w = new UserWatch();
        w.setName("tellers");
        w.setType(WatchType.SAMBA);
        w.setSelectionMode(SelectionMode.LIST);
        w.setSelectionPayload("alice,bruno");
        w.setOnDelete(OnDeletePolicy.DISABLE);
        w.setRunMode(RunMode.REPORT_ONLY);
        assertEquals("tellers", w.getName());
        assertEquals(WatchType.SAMBA, w.getType());
        assertEquals(OnDeletePolicy.DISABLE, w.getOnDelete());
        assertEquals(RunMode.REPORT_ONLY, w.getRunMode());
    }
}
