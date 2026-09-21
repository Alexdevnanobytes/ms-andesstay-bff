package cl.andesstay.bff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(@Value("${security.issuer}") String issuer, @Value("${security.audience}") String audience) {
        if (issuer.contains("SET_TENANT_ID") || audience.startsWith("SET_"))
            throw new IllegalStateException("Configure ENTRA_TENANT_ID y ENTRA_API_CLIENT_ID");
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience));
        OAuth2TokenValidator<Jwt> versionValidator = new JwtClaimValidator<String>("ver", "2.0"::equals);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, versionValidator));
        return decoder;
    }
    @Bean
    JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            String scopes = jwt.getClaimAsString("scp");
            if (scopes != null) Arrays.stream(scopes.split("\\s+")).filter(s -> !s.isBlank())
                .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s)).forEach(authorities::add);
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) roles.stream().filter(r -> List.of("ADMIN", "RECEPCIONISTA", "HUESPED").contains(r))
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).forEach(authorities::add);
            return authorities;
        });
        return converter;
    }
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        return http.csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().access((authentication, context) -> {
                    var rights = authentication.get().getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
                    boolean ok = rights.contains("SCOPE_access_as_user") &&
                        (rights.contains("ROLE_ADMIN") || rights.contains("ROLE_RECEPCIONISTA") || rights.contains("ROLE_HUESPED"));
                    return new org.springframework.security.authorization.AuthorizationDecision(ok);
                }))
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .build();
    }
    @Bean
    CorsConfigurationSource corsSource(@Value("${security.frontend-origin}") String origin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
