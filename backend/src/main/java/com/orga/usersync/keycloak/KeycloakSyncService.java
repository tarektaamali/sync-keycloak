package com.orga.usersync.keycloak;

import com.orga.usersync.audit.AuditService;
import com.orga.usersync.connection.Connection;
import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.ActionType;
import com.orga.usersync.sync.PlannedAction;
import com.orga.usersync.sync.SyncPlan;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class KeycloakSyncService {
    private final ConnectionService connections;
    private final ServiceAccountKeycloakFactory factory;
    private final AuditService audit;

    public KeycloakSyncService(ConnectionService connections, ServiceAccountKeycloakFactory factory,
                               AuditService audit) {
        this.connections = connections; this.factory = factory; this.audit = audit;
    }

    public SyncPlan plan(Long sourceConnId, Long targetConnId, SyncMode mode) {
        Connection src = connections.getEntity(sourceConnId);
        Connection dst = connections.getEntity(targetConnId);
        try (Keycloak s = factory.clientFor(src); Keycloak d = factory.clientFor(dst)) {
            List<UserDto> source = readAll(s.realm(src.getRealm()));
            Set<String> existing = usernames(d.realm(dst.getRealm()));
            return computePlan(source, existing, mode);
        }
    }

    public SyncResult sync(Long sourceConnId, Long targetConnId, SyncMode mode, boolean includeRoles, String actor) {
        Connection src = connections.getEntity(sourceConnId);
        Connection dst = connections.getEntity(targetConnId);
        try (Keycloak s = factory.clientFor(src); Keycloak d = factory.clientFor(dst)) {
            List<UserDto> source = readAll(s.realm(src.getRealm()));
            RealmResource target = d.realm(dst.getRealm());
            SyncPlan plan = computePlan(source, usernames(target), mode);
            SyncResult result = execute(source, plan, target, includeRoles);
            audit.record(actor, src.getName(), dst.getName(), mode, includeRoles, result);
            return result;
        }
    }

    /** Pure decision logic — unit tested. */
    public SyncPlan computePlan(List<UserDto> source, Set<String> existing, SyncMode mode) {
        List<PlannedAction> actions = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (UserDto u : source) {
            names.add(u.username());
            if (!existing.contains(u.username())) actions.add(new PlannedAction(u.username(), ActionType.CREATE));
            else if (mode == SyncMode.CREATE_ONLY) actions.add(new PlannedAction(u.username(), ActionType.SKIP));
            else actions.add(new PlannedAction(u.username(), ActionType.UPDATE));
        }
        if (mode == SyncMode.MIRROR)
            for (String n : existing) if (!names.contains(n)) actions.add(new PlannedAction(n, ActionType.DELETE));
        return new SyncPlan(actions);
    }

    private SyncResult execute(List<UserDto> source, SyncPlan plan, RealmResource target, boolean includeRoles) {
        Map<String, UserDto> byName = new HashMap<>();
        for (UserDto u : source) byName.put(u.username(), u);
        int created = 0, updated = 0, skipped = 0, deleted = 0;
        List<String> errors = new ArrayList<>();
        for (PlannedAction a : plan.actions()) {
            try {
                switch (a.action()) {
                    case CREATE -> { createUser(target, byName.get(a.username()), includeRoles); created++; }
                    case UPDATE -> { updateUser(target, byName.get(a.username()), includeRoles); updated++; }
                    case DELETE -> { deleteUser(target, a.username()); deleted++; }
                    case SKIP -> skipped++;
                }
            } catch (RuntimeException e) { errors.add(a.username() + ": " + e.getMessage()); }
        }
        return new SyncResult(created, updated, skipped, deleted, 0, errors);
    }

    private List<UserDto> readAll(RealmResource realm) {
        List<UserDto> out = new ArrayList<>();
        for (UserRepresentation u : realm.users().list(0, 1000)) {
            List<String> roles = realm.users().get(u.getId()).roles().realmLevel().listEffective()
                .stream().map(RoleRepresentation::getName).toList();
            out.add(new UserDto(u.getUsername(), u.getEmail(), u.getFirstName(),
                u.getLastName(), u.isEnabled() != null && u.isEnabled(), roles));
        }
        return out;
    }
    private Set<String> usernames(RealmResource realm) {
        Set<String> s = new HashSet<>();
        for (UserRepresentation u : realm.users().list(0, 1000)) s.add(u.getUsername());
        return s;
    }
    private void createUser(RealmResource realm, UserDto u, boolean roles) {
        realm.users().create(toRep(u)).close();
        if (roles) assignRoles(realm, u);
    }
    private void updateUser(RealmResource realm, UserDto u, boolean roles) {
        String id = realm.users().search(u.username()).get(0).getId();
        realm.users().get(id).update(toRep(u));
        if (roles) assignRoles(realm, u);
    }
    private void deleteUser(RealmResource realm, String username) {
        String id = realm.users().search(username).get(0).getId();
        realm.users().get(id).remove();
    }
    private void assignRoles(RealmResource realm, UserDto u) {
        String id = realm.users().search(u.username()).get(0).getId();
        List<RoleRepresentation> reps = new ArrayList<>();
        for (String name : u.roles()) {
            try { reps.add(realm.roles().get(name).toRepresentation()); }
            catch (RuntimeException notFound) {
                RoleRepresentation nr = new RoleRepresentation(); nr.setName(name);
                realm.roles().create(nr); reps.add(realm.roles().get(name).toRepresentation());
            }
        }
        realm.users().get(id).roles().realmLevel().add(reps);
    }
    private static UserRepresentation toRep(UserDto u) {
        UserRepresentation r = new UserRepresentation();
        r.setUsername(u.username()); r.setEmail(u.email());
        r.setFirstName(u.firstName()); r.setLastName(u.lastName()); r.setEnabled(u.enabled());
        return r;
    }
}
