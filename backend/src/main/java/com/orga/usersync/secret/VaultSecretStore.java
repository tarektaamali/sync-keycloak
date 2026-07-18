package com.orga.usersync.secret;

import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultKeyValueOperations;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class VaultSecretStore implements SecretStore {
    private final VaultKeyValueOperations kv;

    public VaultSecretStore(VaultTemplate vault) {
        this.kv = vault.opsForKeyValue("secret", KeyValueBackend.KV_2);
    }

    private String path(String name) { return "usersync/" + name; }

    @Override public void put(String name, String field, String value) {
        Map<String, Object> data = new HashMap<>();
        var existing = kv.get(path(name));
        if (existing != null && existing.getData() != null) data.putAll(existing.getData());
        data.put(field, value);
        kv.put(path(name), data);
    }

    @Override public String get(String name, String field) {
        var resp = kv.get(path(name));
        if (resp == null || resp.getData() == null || !resp.getData().containsKey(field))
            throw new IllegalStateException("secret not found: usersync/" + name + "#" + field);
        return String.valueOf(resp.getData().get(field));
    }

    @Override public void deleteAll(String name) { kv.delete(path(name)); }
}
