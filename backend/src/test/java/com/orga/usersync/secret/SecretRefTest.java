package com.orga.usersync.secret;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecretRefTest {
    @Test void round_trips() {
        SecretRef r = SecretRef.parse("vault://usersync/ubs#client-secret");
        assertEquals("ubs", r.name());
        assertEquals("client-secret", r.field());
        assertEquals("vault://usersync/ubs#client-secret", r.toRef());
    }
    @Test void rejects_bad_ref() {
        assertThrows(IllegalArgumentException.class, () -> SecretRef.parse("nope"));
    }
}
