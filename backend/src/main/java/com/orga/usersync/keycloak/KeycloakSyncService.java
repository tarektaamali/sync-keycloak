package com.orga.usersync.keycloak;

import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class KeycloakSyncService {

    /** Seam the unit test fakes; the Keycloak-backed impl lives in this file. */
    interface KeycloakTarget {
        List<String> usernames();
        void create(UserDto u, boolean roles);
        void update(UserDto u, boolean roles);
        void delete(String username);
    }

    private final KeycloakAdminClientFactory factory;
    private String ubsUrl = "http://localhost:8080", ubsRealm = "ubs";
    private String csUrl = "http://localhost:8081", csRealm = "cs";

    public KeycloakSyncService(KeycloakAdminClientFactory factory,
                               @Value("${keycloak.cs.server-url:http://localhost:8081}") String csUrl) {
        this.factory = factory;
        if (csUrl != null) this.csUrl = csUrl;
    }

    public List<UserDto> listSourceUsers() {
        try (Keycloak kc = factory.forRealm(ubsUrl, ubsRealm)) {
            return readAll(kc.realm(ubsRealm));
        }
    }

    public SyncResult sync(SyncMode mode, boolean includeRoles) {
        try (Keycloak src = factory.forRealm(ubsUrl, ubsRealm);
             Keycloak dst = factory.forRealm(csUrl, csRealm)) {
            List<UserDto> source = readAll(src.realm(ubsRealm));
            return apply(source, new RealmTarget(dst.realm(csRealm)), mode, includeRoles);
        }
    }

    /** Pure sync logic — unit tested. */
    SyncResult apply(List<UserDto> source, KeycloakTarget target, SyncMode mode, boolean includeRoles) {
        Set<String> existing = new HashSet<>(target.usernames());
        int created = 0, updated = 0, skipped = 0, deleted = 0;
        List<String> errors = new ArrayList<>();
        Set<String> sourceNames = new HashSet<>();

        for (UserDto u : source) {
            sourceNames.add(u.username());
            try {
                boolean present = existing.contains(u.username());
                if (!present) { target.create(u, includeRoles); created++; }
                else if (mode == SyncMode.CREATE_ONLY) { skipped++; }
                else { target.update(u, includeRoles); updated++; }
            } catch (RuntimeException e) {
                errors.add(u.username() + ": " + e.getMessage());
            }
        }
        if (mode == SyncMode.MIRROR) {
            for (String name : existing) {
                if (!sourceNames.contains(name)) {
                    try { target.delete(name); deleted++; }
                    catch (RuntimeException e) { errors.add(name + ": " + e.getMessage()); }
                }
            }
        }
        return new SyncResult(created, updated, skipped, deleted, errors);
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

    /** Real Keycloak-backed target. */
    static final class RealmTarget implements KeycloakTarget {
        private final RealmResource realm;
        RealmTarget(RealmResource realm) { this.realm = realm; }

        public List<String> usernames() {
            return realm.users().list(0, 1000).stream().map(UserRepresentation::getUsername).toList();
        }
        public void create(UserDto u, boolean roles) {
            UserRepresentation rep = toRep(u);
            realm.users().create(rep).close();
            if (roles) assignRoles(u);
        }
        public void update(UserDto u, boolean roles) {
            String id = realm.users().search(u.username()).get(0).getId();
            realm.users().get(id).update(toRep(u));
            if (roles) assignRoles(u);
        }
        public void delete(String username) {
            String id = realm.users().search(username).get(0).getId();
            realm.users().get(id).remove();
        }
        private void assignRoles(UserDto u) {
            String id = realm.users().search(u.username()).get(0).getId();
            List<RoleRepresentation> reps = new ArrayList<>();
            for (String name : u.roles()) {
                try { reps.add(realm.roles().get(name).toRepresentation()); }
                catch (RuntimeException notFound) {
                    RoleRepresentation nr = new RoleRepresentation(); nr.setName(name);
                    realm.roles().create(nr);
                    reps.add(realm.roles().get(name).toRepresentation());
                }
            }
            realm.users().get(id).roles().realmLevel().add(reps);
        }
        private static UserRepresentation toRep(UserDto u) {
            UserRepresentation r = new UserRepresentation();
            r.setUsername(u.username()); r.setEmail(u.email());
            r.setFirstName(u.firstName()); r.setLastName(u.lastName());
            r.setEnabled(u.enabled());
            return r;
        }
    }
}
