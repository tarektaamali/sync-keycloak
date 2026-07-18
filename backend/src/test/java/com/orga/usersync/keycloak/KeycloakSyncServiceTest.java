package com.orga.usersync.keycloak;

import com.orga.usersync.keycloak.KeycloakSyncService.KeycloakTarget;
import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class KeycloakSyncServiceTest {

    /** In-memory target recording operations. */
    static class FakeTarget implements KeycloakTarget {
        final Map<String, UserDto> store = new LinkedHashMap<>();
        final List<String> created = new ArrayList<>(), updated = new ArrayList<>(), deleted = new ArrayList<>();
        FakeTarget(String... existing) { for (String u : existing) store.put(u, user(u)); }
        public List<String> usernames() { return new ArrayList<>(store.keySet()); }
        public void create(UserDto u, boolean r) { store.put(u.username(), u); created.add(u.username()); }
        public void update(UserDto u, boolean r) { store.put(u.username(), u); updated.add(u.username()); }
        public void delete(String username) { store.remove(username); deleted.add(username); }
    }

    static UserDto user(String name) { return new UserDto(name, name + "@x", "F", "L", true, List.of("teller")); }

    private final KeycloakSyncService svc = new KeycloakSyncService(null, null);

    @Test void createOnly_skips_existing() {
        FakeTarget t = new FakeTarget("alice");
        SyncResult r = svc.apply(List.of(user("alice"), user("bruno")), t, SyncMode.CREATE_ONLY, false);
        assertEquals(List.of("bruno"), t.created);
        assertEquals(1, r.created());
        assertEquals(1, r.skipped());
        assertEquals(0, r.updated());
    }

    @Test void createUpdate_upserts() {
        FakeTarget t = new FakeTarget("alice");
        SyncResult r = svc.apply(List.of(user("alice"), user("bruno")), t, SyncMode.CREATE_UPDATE, false);
        assertEquals(List.of("bruno"), t.created);
        assertEquals(List.of("alice"), t.updated);
        assertEquals(1, r.created());
        assertEquals(1, r.updated());
    }

    @Test void mirror_deletes_users_not_in_source() {
        FakeTarget t = new FakeTarget("alice", "stale");
        SyncResult r = svc.apply(List.of(user("alice")), t, SyncMode.MIRROR, false);
        assertEquals(List.of("stale"), t.deleted);
        assertEquals(1, r.deleted());
        assertEquals(1, r.updated());
    }
}
