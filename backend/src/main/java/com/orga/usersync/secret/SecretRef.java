package com.orga.usersync.secret;

public record SecretRef(String name, String field) {
    public static SecretRef parse(String ref) {
        if (ref == null || !ref.startsWith("vault://usersync/") || !ref.contains("#"))
            throw new IllegalArgumentException("bad secret ref: " + ref);
        String rest = ref.substring("vault://usersync/".length());
        String[] parts = rest.split("#", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
            throw new IllegalArgumentException("bad secret ref: " + ref);
        return new SecretRef(parts[0], parts[1]);
    }
    public String toRef() { return "vault://usersync/" + name + "#" + field; }
}
