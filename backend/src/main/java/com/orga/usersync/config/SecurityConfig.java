package com.orga.usersync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(c -> c.disable())
            .cors(c -> c.configurationSource(req -> {
                CorsConfiguration cfg = new CorsConfiguration();
                cfg.setAllowedOrigins(List.of("http://localhost:4200"));
                cfg.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
                cfg.setAllowedHeaders(List.of("*"));
                return cfg;
            }))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> {}));
        return http.build();
    }
}
