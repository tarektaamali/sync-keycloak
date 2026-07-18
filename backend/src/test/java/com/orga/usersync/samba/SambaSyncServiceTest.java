package com.orga.usersync.samba;

import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.samba.SambaSyncService.Target;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SambaSyncServiceTest {

    static class FakeTarget implements Target {
        final Set<String> existing;
        final List<String> created = new ArrayList<>(), updated = new ArrayList<>();
        FakeTarget(String... e) { existing = new LinkedHashSet<>(Arrays.asList(e)); }
        public Set<String> usernames() { return existing; }
        public void create(UserDto u, boolean r) { created.add(u.username()); }
        public void update(UserDto u, boolean r) { updated.add(u.username()); }
        public void delete(String u) { }
    }

    static UserDto u(String n) { return new UserDto(n, n + "@orga", "F", "L", true, List.of()); }

    private final SambaSyncService svc = new SambaSyncService(null, null);

    @Test void create_only_skips_existing() {
        FakeTarget t = new FakeTarget("dmiller");
        SyncResult r = svc.apply(List.of(u("dmiller"), u("newbie")), t, SyncMode.CREATE_ONLY, false);
        assertEquals(List.of("newbie"), t.created);
        assertEquals(1, r.skipped());
    }
}
