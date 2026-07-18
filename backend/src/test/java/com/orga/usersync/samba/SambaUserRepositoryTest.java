package com.orga.usersync.samba;

import org.junit.jupiter.api.Test;
import javax.naming.directory.BasicAttributes;
import com.orga.usersync.model.UserDto;

import static org.junit.jupiter.api.Assertions.*;

class SambaUserRepositoryTest {

    @Test void maps_ad_attributes_to_userdto() throws Exception {
        BasicAttributes attrs = new BasicAttributes();
        attrs.put("sAMAccountName", "dmiller");
        attrs.put("mail", "dmiller@orga.local");
        attrs.put("givenName", "Dana");
        attrs.put("sn", "Miller");
        attrs.put("userAccountControl", "512"); // normal, enabled

        UserDto u = SambaUserRepository.USER_MAPPER.mapFromAttributes(attrs);

        assertEquals("dmiller", u.username());
        assertEquals("dmiller@orga.local", u.email());
        assertEquals("Dana", u.firstName());
        assertTrue(u.enabled());
    }

    @Test void account_disabled_bit_maps_to_disabled() throws Exception {
        BasicAttributes attrs = new BasicAttributes();
        attrs.put("sAMAccountName", "x");
        attrs.put("userAccountControl", "514"); // 0x2 ACCOUNTDISABLE set
        assertFalse(SambaUserRepository.USER_MAPPER.mapFromAttributes(attrs).enabled());
    }
}
