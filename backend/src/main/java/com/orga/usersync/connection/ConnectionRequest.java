package com.orga.usersync.connection;

public record ConnectionRequest(String name, ConnectionType type, String serverUrl,
                                String realm, String baseDn, String clientId,
                                String bindDn, String userSearchBase, String secret) {}
