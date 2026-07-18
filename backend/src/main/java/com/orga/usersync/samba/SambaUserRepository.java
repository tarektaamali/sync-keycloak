package com.orga.usersync.samba;

import com.orga.usersync.model.UserDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Repository;

import javax.naming.directory.Attributes;
import java.util.List;

@Repository
public class SambaUserRepository {

    public static final AttributesMapper<UserDto> USER_MAPPER = SambaUserRepository::map;

    private final LdapTemplate ldap;
    private final String userSearchBase;

    public SambaUserRepository(
            @Value("${samba.url}") String url,
            @Value("${samba.base}") String base,
            @Value("${samba.username}") String user,
            @Value("${samba.password}") String pass,
            @Value("${samba.user-search-base}") String userSearchBase) {
        LdapContextSource cs = new LdapContextSource();
        cs.setUrl(url); cs.setBase(base); cs.setUserDn(user); cs.setPassword(pass);
        cs.afterPropertiesSet();
        this.ldap = new LdapTemplate(cs);
        this.userSearchBase = userSearchBase;
    }

    public List<UserDto> findAll() {
        return ldap.search(userSearchBase, "(objectClass=user)", USER_MAPPER);
    }

    static UserDto map(Attributes a) throws javax.naming.NamingException {
        String username = str(a, "sAMAccountName");
        String email = str(a, "mail");
        String first = str(a, "givenName");
        String last = str(a, "sn");
        String uac = str(a, "userAccountControl");
        boolean enabled = uac == null || (Integer.parseInt(uac) & 0x2) == 0;
        return new UserDto(username, email, first, last, enabled, List.of());
    }

    private static String str(Attributes a, String id) throws javax.naming.NamingException {
        return a.get(id) == null ? null : String.valueOf(a.get(id).get());
    }
}
