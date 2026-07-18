package com.orga.usersync.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;

import java.net.URI;

@Configuration
public class VaultConfig {
    @Bean
    VaultTemplate vaultTemplate(@Value("${vault.uri}") String uri, @Value("${vault.token}") String token) {
        return new VaultTemplate(VaultEndpoint.from(URI.create(uri)), new TokenAuthentication(token));
    }
}
