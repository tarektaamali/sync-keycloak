package com.orga.usersync.connection;

public record ConnectionView(Long id, String name, ConnectionType type, String serverUrl,
                             String realm, String baseDn, String clientId, String bindDn,
                             String userSearchBase, String secretRef) {
    static ConnectionView of(Connection c) {
        return new ConnectionView(c.getId(), c.getName(), c.getType(), c.getServerUrl(),
            c.getRealm(), c.getBaseDn(), c.getClientId(), c.getBindDn(), c.getUserSearchBase(), c.getSecretRef());
    }
}
