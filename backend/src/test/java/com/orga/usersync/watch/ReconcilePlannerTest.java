package com.orga.usersync.watch;

import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.SyncPlan;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReconcilePlannerTest {
    static UserDto user(String name, boolean enabled) {
        return new UserDto(name, name + "@x", "F", "L", enabled, List.of());
    }
    static UserWatch watch(SelectionMode mode, String payload, OnDeletePolicy onDelete) {
        UserWatch w = new UserWatch();
        w.setSelectionMode(mode); w.setSelectionPayload(payload); w.setOnDelete(onDelete);
        return w;
    }
    static Set<String> set(String... s) { return new LinkedHashSet<>(Arrays.asList(s)); }

    @Test void list_covers_exactly_named_users() {
        UserWatch w = watch(SelectionMode.LIST, "alice, bruno", OnDeletePolicy.DISABLE);
        var covered = ReconcilePlanner.coveredUsernames(w, List.of(user("alice", true), user("zoe", true)));
        assertEquals(set("alice", "bruno"), covered);
    }

    @Test void filter_covers_source_users_matching_term() {
        UserWatch w = watch(SelectionMode.FILTER, "adm", OnDeletePolicy.DISABLE);
        var covered = ReconcilePlanner.coveredUsernames(w,
            List.of(user("admin", true), user("adminka", true), user("teller", true)));
        assertEquals(set("admin", "adminka"), covered);
    }

    @Test void present_enabled_missing_on_target_is_create() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.DISABLE);
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(user("alice", true)), set(), set());
        assertEquals(1, p.created());
    }

    @Test void present_enabled_on_target_is_update() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.DISABLE);
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(user("alice", true)), set("alice"), set());
        assertEquals(1, p.updated());
    }

    @Test void source_disabled_yields_disable() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.DELETE);
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(user("alice", false)), set("alice"), set());
        assertEquals(1, p.disabled());   // disable always wins over onDelete for a still-present user
    }

    @Test void removed_member_disable_policy() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.DISABLE);
        // alice is a prior member, absent from source, present on target
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(), set("alice"), set("alice"));
        assertEquals(1, p.disabled());
    }

    @Test void removed_member_delete_policy() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.DELETE);
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(), set("alice"), set("alice"));
        assertEquals(1, p.deleted());
    }

    @Test void removed_member_ignore_policy() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.IGNORE);
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(), set("alice"), set("alice"));
        assertEquals(1, p.skipped());
    }

    @Test void removed_member_absent_from_target_is_skip_idempotent() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.DELETE);
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(), set(), set("alice"));
        assertEquals(1, p.skipped());   // already gone from target -> nothing to do
    }

    @Test void filter_member_dropping_out_is_a_removal() {
        UserWatch w = watch(SelectionMode.FILTER, "adm", OnDeletePolicy.DISABLE);
        // bob was a prior member; no longer matches the filter and no longer in source
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(user("admin", true)),
            set("admin", "bob"), set("bob"));
        assertEquals(1, p.updated());    // admin
        assertEquals(1, p.disabled());   // bob removed -> disable
    }

    @Test void member_states_reflect_source_reality() {
        var governed = set("alice", "bob", "carla");
        var states = ReconcilePlanner.memberStates(governed,
            List.of(user("alice", true), user("bob", false)));
        assertEquals(WatchMemberState.PRESENT, states.get("alice"));
        assertEquals(WatchMemberState.DISABLED, states.get("bob"));
        assertEquals(WatchMemberState.REMOVED, states.get("carla"));
    }
}
