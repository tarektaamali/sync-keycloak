package com.orga.usersync.watch;

import com.orga.usersync.connection.Connection;
import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.connection.ConnectionType;
import com.orga.usersync.keycloak.ServiceAccountKeycloakFactory;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.samba.SambaUserRepository;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Consumer;

@Component
public class KeycloakReconcileGateway implements ReconcileGateway {
    private final ServiceAccountKeycloakFactory factory;
    private final ConnectionService connections;
    private final SambaUserRepository samba;

    public KeycloakReconcileGateway(ServiceAccountKeycloakFactory factory, ConnectionService connections,
                                    SambaUserRepository samba) {
        this.factory = factory; this.connections = connections; this.samba = samba;
    }

    @Override public List<UserDto> readSource(UserWatch w) {
        return readSourceConnection(connections.getEntity(w.getSourceConnId()));
    }

    /** Read all users from any connection (Keycloak or LDAP), used by the watch editor's user-picker too. */
    public List<UserDto> readSourceConnection(Connection src) {
        if (src.getType() == ConnectionType.LDAP) {
            return samba.findAll(src, connections.resolveSecret(src));
        }
        try (Keycloak s = factory.clientFor(src)) {
            return readKeycloak(s.realm(src.getRealm()));
        }
    }

    @Override public Set<String> targetUsernames(UserWatch w) {
        Connection dst = connections.getEntity(w.getTargetConnId());
        try (Keycloak d = factory.clientFor(dst)) {
            Set<String> out = new HashSet<>();
            for (UserRepresentation u : d.realm(dst.getRealm()).users().list(0, 1000)) out.add(u.getUsername());
            return out;
        }
    }

    @Override public void create(UserWatch w, UserDto u) {
        withTarget(w, realm -> {
            realm.users().create(toRep(u)).close();
            if (w.isIncludeRoles()) assignRoles(realm, u);
        });
    }

    @Override public void update(UserWatch w, UserDto u) {
        withTarget(w, realm -> {
            realm.users().get(idOf(realm, u.username())).update(toRep(u));
            if (w.isIncludeRoles()) assignRoles(realm, u);
        });
    }

    @Override public void disable(UserWatch w, String username) {
        withTarget(w, realm -> {
            String id = idOf(realm, username);
            UserRepresentation r = realm.users().get(id).toRepresentation();
            r.setEnabled(false);
            realm.users().get(id).update(r);
        });
    }

    @Override public void delete(UserWatch w, String username) {
        withTarget(w, realm -> realm.users().get(idOf(realm, username)).remove());
    }

    private void withTarget(UserWatch w, Consumer<RealmResource> body) {
        Connection dst = connections.getEntity(w.getTargetConnId());
        try (Keycloak d = factory.clientFor(dst)) {
            body.accept(d.realm(dst.getRealm()));
        }
    }

    private static String idOf(RealmResource realm, String username) {
        List<UserRepresentation> found = realm.users().search(username);
        if (found.isEmpty()) throw new IllegalStateException("user not found on target: " + username);
        return found.get(0).getId();
    }

    private static List<UserDto> readKeycloak(RealmResource realm) {
        List<UserDto> out = new ArrayList<>();
        for (UserRepresentation u : realm.users().list(0, 1000)) {
            List<String> roles = realm.users().get(u.getId()).roles().realmLevel().listEffective()
                .stream().map(RoleRepresentation::getName).toList();
            out.add(new UserDto(u.getUsername(), u.getEmail(), u.getFirstName(),
                u.getLastName(), u.isEnabled() != null && u.isEnabled(), roles));
        }
        return out;
    }

    private static void assignRoles(RealmResource realm, UserDto u) {
        String id = idOf(realm, u.username());
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
