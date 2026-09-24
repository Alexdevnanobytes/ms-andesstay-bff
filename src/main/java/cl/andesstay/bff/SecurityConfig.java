package cl.andesstay.bff;

import java.util.ArrayList;
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
    JwtDecoder jwtDecoder(@Value("${security.issuer}") String issuer, @Value("${security.audience}") String audience,
                          @Value("${security.cognito.region}") String cognitoRegion,
                          @Value("${security.cognito.user-pool-id}") String cognitoPoolId,
                          @Value("${security.cognito.client-id}") String cognitoClientId) {
        if (issuer.contains("SET_TENANT_ID") || audience.startsWith("SET_"))
            throw new IllegalStateException("Configure ENTRA_TENANT_ID y ENTRA_API_CLIENT_ID");
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience));
        OAuth2TokenValidator<Jwt> versionValidator = new JwtClaimValidator<String>("ver", "2.0"::equals);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, versionValidator));
        if (cognitoPoolId.isBlank() || cognitoClientId.isBlank()) return decoder;
        String cognitoIssuer = "https://cognito-idp." + cognitoRegion + ".amazonaws.com/" + cognitoPoolId;
        return byIssuer(cognitoIssuer, cognitoDecoder(cognitoIssuer, cognitoClientId), decoder);
    }
    /** Cognito: firma del User Pool, emisor, access token (no id token) y emitido para nuestro app client. */
    static JwtDecoder cognitoDecoder(String issuer, String clientId) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/.well-known/jwks.json").build();
        decoder.setJwtValidator(cognitoValidator(issuer, clientId));
        return decoder;
    }
    static OAuth2TokenValidator<Jwt> cognitoValidator(String issuer, String clientId) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
            new JwtClaimValidator<String>("token_use", "access"::equals),
            new JwtClaimValidator<String>("client_id", clientId::equals));
    }
    /** Elige el validador según el emisor declarado; la firma y el emisor se verifican después en ese validador. */
    static JwtDecoder byIssuer(String cognitoIssuer, JwtDecoder cognito, JwtDecoder entra) {
        return token -> cognitoIssuer.equals(declaredIssuer(token)) ? cognito.decode(token) : entra.decode(token);
    }
    private static String declaredIssuer(String token) {
        try {
            return com.nimbusds.jwt.JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (java.text.ParseException e) {
            return null;
        }
    }
    @Bean
    JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            UserClaims.scopes(jwt).forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)));
            UserClaims.roles(jwt).forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
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
    CorsConfigurationSource corsConfigurationSource(@Value("${security.frontend-origin}") String origin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
