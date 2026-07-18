package com.orga.usersync.secret;

public interface SecretStore {
    void put(String name, String field, String value);
    String get(String name, String field);
    void deleteAll(String name);
}
