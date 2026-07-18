package com.orga.usersync.samba;

import com.orga.usersync.keycloak.KeycloakAdminClientFactory;
import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncRequest;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SambaSyncService {

    /** Independent target seam (Approach 2 — not shared with KeycloakSyncService). */
    interface Target {
        Set<String> usernames();
        void create(UserDto u, boolean roles);
        void update(UserDto u, boolean roles);
        void delete(String username);
    }

    private final SambaUserRepository repo;
    private final KeycloakAdminClientFactory factory;
    private final Map<String, String> targetUrls = Map.of("ubs", "http://localhost:8080", "cs", "http://localhost:8081");

    public SambaSyncService(SambaUserRepository repo, KeycloakAdminClientFactory factory) {
        this.repo = repo; this.factory = factory;
    }

    public List<UserDto> listUsers() { return repo.findAll(); }

    public SyncResult sync(SyncRequest req) {
        String realm = req.target();
        String url = targetUrls.getOrDefault(realm, "http://localhost:8081");
        try (Keycloak kc = factory.forRealm(url, realm)) {
            return apply(repo.findAll(), new RealmTarget(kc.realm(realm)), req.mode(), req.includeRoles());
        }
    }

    SyncResult apply(List<UserDto> source, Target target, SyncMode mode, boolean includeRoles) {
        Set<String> existing = new HashSet<>(target.usernames());
        int created = 0, updated = 0, skipped = 0, deleted = 0;
        List<String> errors = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (UserDto u : source) {
            names.add(u.username());
            try {
                if (!existing.contains(u.username())) { target.create(u, includeRoles); created++; }
                else if (mode == SyncMode.CREATE_ONLY) { skipped++; }
                else { target.update(u, includeRoles); updated++; }
            } catch (RuntimeException e) { errors.add(u.username() + ": " + e.getMessage()); }
        }
        if (mode == SyncMode.MIRROR) {
            for (String n : existing) if (!names.contains(n)) {
                try { target.delete(n); deleted++; } catch (RuntimeException e) { errors.add(n + ": " + e.getMessage()); }
            }
        }
        return new SyncResult(created, updated, skipped, deleted, errors);
    }

    static final class RealmTarget implements Target {
        private final RealmResource realm;
        RealmTarget(RealmResource realm) { this.realm = realm; }
        public Set<String> usernames() {
            Set<String> s = new HashSet<>();
            for (UserRepresentation u : realm.users().list(0, 1000)) s.add(u.getUsername());
            return s;
        }
        public void create(UserDto u, boolean roles) { realm.users().create(toRep(u)).close(); }
        public void update(UserDto u, boolean roles) {
            String id = realm.users().search(u.username()).get(0).getId();
            realm.users().get(id).update(toRep(u));
        }
        public void delete(String username) {
            String id = realm.users().search(username).get(0).getId();
            realm.users().get(id).remove();
        }
        private static UserRepresentation toRep(UserDto u) {
            UserRepresentation r = new UserRepresentation();
            r.setUsername(u.username()); r.setEmail(u.email());
            r.setFirstName(u.firstName()); r.setLastName(u.lastName()); r.setEnabled(u.enabled());
            return r;
        }
    }
}
