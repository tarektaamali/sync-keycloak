package com.orga.usersync.samba;

import com.orga.usersync.audit.AuditService;
import com.orga.usersync.connection.Connection;
import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.keycloak.ServiceAccountKeycloakFactory;
import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.ActionType;
import com.orga.usersync.sync.PlannedAction;
import com.orga.usersync.sync.SyncPlan;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SambaSyncService {
    private final SambaUserRepository repo;
    private final ConnectionService connections;
    private final ServiceAccountKeycloakFactory kcFactory;
    private final AuditService audit;

    public SambaSyncService(SambaUserRepository repo, ConnectionService connections,
                            ServiceAccountKeycloakFactory kcFactory, AuditService audit) {
        this.repo = repo; this.connections = connections; this.kcFactory = kcFactory; this.audit = audit;
    }

    public SyncPlan plan(Long sourceLdapConnId, Long targetKcConnId, SyncMode mode) {
        Connection ldap = connections.getEntity(sourceLdapConnId);
        Connection kc = connections.getEntity(targetKcConnId);
        List<UserDto> source = repo.findAll(ldap, connections.resolveSecret(ldap));
        try (Keycloak d = kcFactory.clientFor(kc)) {
            return computePlan(source, usernames(d.realm(kc.getRealm())), mode);
        }
    }

    public SyncResult sync(Long sourceLdapConnId, Long targetKcConnId, SyncMode mode, boolean includeRoles, String actor) {
        Connection ldap = connections.getEntity(sourceLdapConnId);
        Connection kc = connections.getEntity(targetKcConnId);
        List<UserDto> source = repo.findAll(ldap, connections.resolveSecret(ldap));
        try (Keycloak d = kcFactory.clientFor(kc)) {
            RealmResource target = d.realm(kc.getRealm());
            SyncPlan plan = computePlan(source, usernames(target), mode);
            SyncResult result = execute(source, plan, target);
            audit.record(actor, ldap.getName(), kc.getName(), mode, includeRoles, result);
            return result;
        }
    }

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

    private SyncResult execute(List<UserDto> source, SyncPlan plan, RealmResource target) {
        Map<String, UserDto> byName = new HashMap<>();
        for (UserDto u : source) byName.put(u.username(), u);
        int created = 0, updated = 0, skipped = 0, deleted = 0;
        List<String> errors = new ArrayList<>();
        for (PlannedAction a : plan.actions()) {
            try {
                switch (a.action()) {
                    case CREATE -> { target.users().create(toRep(byName.get(a.username()))).close(); created++; }
                    case UPDATE -> {
                        String id = target.users().search(a.username()).get(0).getId();
                        target.users().get(id).update(toRep(byName.get(a.username()))); updated++;
                    }
                    case DELETE -> {
                        String id = target.users().search(a.username()).get(0).getId();
                        target.users().get(id).remove(); deleted++;
                    }
                    case SKIP -> skipped++;
                }
            } catch (RuntimeException e) { errors.add(a.username() + ": " + e.getMessage()); }
        }
        return new SyncResult(created, updated, skipped, deleted, 0, errors);
    }

    private Set<String> usernames(RealmResource realm) {
        Set<String> s = new HashSet<>();
        for (UserRepresentation u : realm.users().list(0, 1000)) s.add(u.getUsername());
        return s;
    }
    private static UserRepresentation toRep(UserDto u) {
        UserRepresentation r = new UserRepresentation();
        r.setUsername(u.username()); r.setEmail(u.email());
        r.setFirstName(u.firstName()); r.setLastName(u.lastName()); r.setEnabled(u.enabled());
        return r;
    }
}
