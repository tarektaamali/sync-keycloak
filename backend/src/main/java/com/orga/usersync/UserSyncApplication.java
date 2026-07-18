package com.orga.usersync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration;

@SpringBootApplication(exclude = LdapAutoConfiguration.class)
public class UserSyncApplication {
    public static void main(String[] args) { SpringApplication.run(UserSyncApplication.class, args); }
}
