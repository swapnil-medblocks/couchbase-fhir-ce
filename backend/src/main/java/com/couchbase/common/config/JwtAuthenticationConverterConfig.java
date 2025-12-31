package com.couchbase.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Provide a default JwtAuthenticationConverter when one is not defined.
 * This avoids startup failures when switching between embedded auth and Keycloak.
 */
@Configuration
public class JwtAuthenticationConverterConfig {

    @Bean
    // Only create this bean when Keycloak IS in use (external auth) and no other bean
    // with the same name exists. When embedded Authorization Server is enabled
    // (app.security.use-keycloak=false), the embedded config supplies a converter
    // and we must not register a second bean with the same name.
    @ConditionalOnProperty(name = "app.security.use-keycloak", havingValue = "true")
    @ConditionalOnMissingBean(name = "jwtAuthenticationConverter")
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        // keep default authority prefix ("SCOPE_"), adjust if needed
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }
}
