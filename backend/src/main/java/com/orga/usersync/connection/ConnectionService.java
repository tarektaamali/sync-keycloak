package com.orga.usersync.connection;

import com.orga.usersync.secret.SecretRef;
import com.orga.usersync.secret.SecretStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ConnectionService {
    private final ConnectionRepositoryLike repo;
    private final SecretStore secrets;

    public ConnectionService(ConnectionRepositoryLike repo, SecretStore secrets) {
        this.repo = repo; this.secrets = secrets;
    }

    private static String secretField(ConnectionType t) {
        return t == ConnectionType.KEYCLOAK ? "client-secret" : "bind-password";
    }

    public ConnectionView create(ConnectionRequest r) {
        Connection c = new Connection();
        c.setCreatedAt(Instant.EPOCH);
        apply(c, r);
        return ConnectionView.of(repo.save(c));
    }

    public ConnectionView update(Long id, ConnectionRequest r) {
        Connection c = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("no connection " + id));
        apply(c, r);
        return ConnectionView.of(repo.save(c));
    }

    private void apply(Connection c, ConnectionRequest r) {
        c.setName(r.name()); c.setType(r.type()); c.setServerUrl(r.serverUrl());
        c.setRealm(r.realm()); c.setBaseDn(r.baseDn()); c.setClientId(r.clientId());
        c.setBindDn(r.bindDn()); c.setUserSearchBase(r.userSearchBase());
        String field = secretField(r.type());
        if (r.secret() != null && !r.secret().isBlank()) {
            secrets.put(r.name(), field, r.secret());
        }
        c.setSecretRef(new SecretRef(r.name(), field).toRef());
    }

    public List<ConnectionView> list() { return repo.findAll().stream().map(ConnectionView::of).toList(); }

    public Connection getEntity(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("no connection " + id));
    }

    public void delete(Long id) {
        Connection c = getEntity(id);
        secrets.deleteAll(c.getName());
        repo.deleteById(id);
    }

    /** Resolve the stored secret for a connection via its secretRef. */
    public String resolveSecret(Connection c) {
        SecretRef ref = SecretRef.parse(c.getSecretRef());
        return secrets.get(ref.name(), ref.field());
    }
}
