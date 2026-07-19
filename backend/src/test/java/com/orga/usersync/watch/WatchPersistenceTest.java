package com.orga.usersync.watch;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Boots JPA + H2 so the watch entity schema (incl. the watch_member unique constraint) is exercised. */
@DataJpaTest
class WatchPersistenceTest {
    @Autowired UserWatchRepository watches;
    @Autowired WatchMemberRepository members;

    private WatchMember member(Long watchId, String username) {
        WatchMember m = new WatchMember();
        m.setWatchId(watchId); m.setUsername(username);
        m.setLastState(WatchMemberState.PRESENT);
        m.setFirstSeen(Instant.now()); m.setLastSeen(Instant.now());
        return m;
    }

    @Test void persists_watch_and_members_then_deletes_by_watch() {
        UserWatch w = new UserWatch();
        w.setName("tellers"); w.setType(WatchType.SAMBA);
        w.setSelectionMode(SelectionMode.LIST); w.setSelectionPayload("alice,bruno");
        w.setOnDelete(OnDeletePolicy.DISABLE); w.setRunMode(RunMode.REPORT_ONLY);
        w.setCron("0 0 2 * * ?"); w.setEnabled(true);
        Long id = watches.saveAndFlush(w).getId();
        assertNotNull(id);

        members.saveAndFlush(member(id, "alice"));
        members.saveAndFlush(member(id, "bruno"));
        List<WatchMember> found = members.findByWatchId(id);
        assertEquals(2, found.size());

        members.deleteByWatchId(id);
        assertEquals(0, members.findByWatchId(id).size());
    }

    @Test void enforces_unique_username_per_watch() {
        members.saveAndFlush(member(5L, "alice"));
        // the (watch_id, username) unique constraint must reject a duplicate
        assertThrows(DataIntegrityViolationException.class,
            () -> members.saveAndFlush(member(5L, "alice")));
    }
}
