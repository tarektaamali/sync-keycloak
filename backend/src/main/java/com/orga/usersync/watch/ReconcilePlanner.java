package com.orga.usersync.watch;

import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.ActionType;
import com.orga.usersync.sync.PlannedAction;
import com.orga.usersync.sync.SyncPlan;

import java.util.*;

/** Pure reconciliation decision logic — no Keycloak, LDAP, or DB. */
public final class ReconcilePlanner {
    private ReconcilePlanner() {}

    /** Usernames this watch covers, resolved against the current source snapshot. */
    public static Set<String> coveredUsernames(UserWatch w, List<UserDto> source) {
        Set<String> out = new LinkedHashSet<>();
        if (w.getSelectionMode() == SelectionMode.LIST) {
            String payload = w.getSelectionPayload() == null ? "" : w.getSelectionPayload();
            for (String s : payload.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        }
        String term = w.getSelectionPayload() == null ? "" : w.getSelectionPayload().trim().toLowerCase();
        for (UserDto u : source) {
            if (term.isEmpty() || u.username().toLowerCase().contains(term)) out.add(u.username());
        }
        return out;
    }

    /** Everything this watch is responsible for this run: current coverage plus still-live prior members. */
    public static Set<String> governed(UserWatch w, List<UserDto> source, Set<String> priorLive) {
        Set<String> g = new LinkedHashSet<>(coveredUsernames(w, source));
        g.addAll(priorLive);
        return g;
    }

    /** The plan of actions, scoped strictly to the governed set. */
    public static SyncPlan computePlan(UserWatch w, List<UserDto> source,
                                       Set<String> targetUsernames, Set<String> priorLive) {
        Map<String, UserDto> src = new HashMap<>();
        for (UserDto u : source) src.put(u.username(), u);
        List<PlannedAction> actions = new ArrayList<>();
        for (String name : governed(w, source, priorLive)) {
            UserDto u = src.get(name);
            if (u != null) {
                if (!u.enabled()) actions.add(new PlannedAction(name, ActionType.DISABLE));
                else if (!targetUsernames.contains(name)) actions.add(new PlannedAction(name, ActionType.CREATE));
                else actions.add(new PlannedAction(name, ActionType.UPDATE));
            } else if (!targetUsernames.contains(name)) {
                actions.add(new PlannedAction(name, ActionType.SKIP)); // gone from source and target: idempotent no-op
            } else {
                switch (w.getOnDelete()) {
                    case DISABLE -> actions.add(new PlannedAction(name, ActionType.DISABLE));
                    case DELETE -> actions.add(new PlannedAction(name, ActionType.DELETE));
                    case IGNORE -> actions.add(new PlannedAction(name, ActionType.SKIP));
                }
            }
        }
        return new SyncPlan(actions);
    }

    /** Member state per governed user, reflecting source reality (for the audit snapshot). */
    public static Map<String, WatchMemberState> memberStates(Set<String> governed, List<UserDto> source) {
        Map<String, UserDto> src = new HashMap<>();
        for (UserDto u : source) src.put(u.username(), u);
        Map<String, WatchMemberState> out = new LinkedHashMap<>();
        for (String n : governed) {
            UserDto u = src.get(n);
            out.put(n, u == null ? WatchMemberState.REMOVED
                : (u.enabled() ? WatchMemberState.PRESENT : WatchMemberState.DISABLED));
        }
        return out;
    }
}
