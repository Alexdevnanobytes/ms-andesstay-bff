package cl.andesstay.bff;

// Configuración de seguridad del BFF (Spring Security).
// Define: cómo se valida cada JWT (firma, emisor, audiencia, vigencia), cómo se traduce a permisos,
// qué reglas debe cumplir cada petición y qué orígenes pueden llamar desde el navegador (CORS).
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

// @Configuration: esta clase declara beans (objetos que Spring crea y reutiliza).
// @EnableMethodSecurity: habilita @PreAuthorize en los métodos del controlador (reglas por endpoint).
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    // Decodificador de JWT: recibe el texto del token del header Authorization y devuelve el Jwt ya validado.
    // Si la validación falla, Spring Security responde 401 Unauthorized.
    // Los valores llegan desde application.yml, que a su vez los lee de variables de entorno.
    @Bean
    JwtDecoder jwtDecoder(@Value("${security.issuer}") String issuer, @Value("${security.audience}") String audience,
                          @Value("${security.cognito.region}") String cognitoRegion,
                          @Value("${security.cognito.user-pool-id}") String cognitoPoolId,
                          @Value("${security.cognito.client-id}") String cognitoClientId) {
        // Protección: si faltan los datos de Entra ID, el BFF no arranca (mejor fallar al inicio que en producción).
        if (issuer.contains("SET_TENANT_ID") || audience.startsWith("SET_"))
            throw new IllegalStateException("Configure ENTRA_TENANT_ID y ENTRA_API_CLIENT_ID");
        // Validador de Microsoft Entra ID. fromIssuerLocation descarga la metadata OIDC del emisor
        // (https://login.microsoftonline.com/<tenant>/v2.0/.well-known/openid-configuration)
        // y de ahí obtiene las llaves públicas (JWKS) con las que se verifica la FIRMA del token.
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
        // Audiencia: el claim "aud" debe contener el client ID de la app andesstay-api.
        // Evita aceptar tokens que Microsoft emitió para otras aplicaciones.
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience));
        // Versión: solo se aceptan tokens v2.0 (configurado en el manifiesto de la app en Entra ID).
        OAuth2TokenValidator<Jwt> versionValidator = new JwtClaimValidator<String>("ver", "2.0"::equals);
        // Se combinan los validadores: emisor + vigencia (exp/nbf) por defecto, más audiencia y versión.
        // El token es válido solo si pasa TODOS.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, versionValidator));
        // Si Cognito no está configurado (variables vacías), el BFF funciona solo con Entra ID.
        if (cognitoPoolId.isBlank() || cognitoClientId.isBlank()) return decoder;
        // Emisor esperado de los tokens de Cognito, por ejemplo https://cognito-idp.us-east-1.amazonaws.com/us-east-1_xxx
        String cognitoIssuer = "https://cognito-idp." + cognitoRegion + ".amazonaws.com/" + cognitoPoolId;
        // Decodificador compuesto: los tokens de Cognito van a su validador; todo lo demás, al de Entra ID.
        return byIssuer(cognitoIssuer, cognitoDecoder(cognitoIssuer, cognitoClientId), decoder);
    }
    /** Cognito: firma del User Pool, emisor, access token (no id token) y emitido para nuestro app client. */
    // Arma el validador de Cognito. withJwkSetUri apunta a las llaves públicas del User Pool.
    // Las llaves se descargan recién al validar el primer token (no al arrancar).
    static JwtDecoder cognitoDecoder(String issuer, String clientId) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/.well-known/jwks.json").build();
        decoder.setJwtValidator(cognitoValidator(issuer, clientId));
        return decoder;
    }
    // Reglas que debe cumplir un token de Cognito, además de la firma:
    // - createDefaultWithIssuer: emisor correcto y no vencido.
    // - token_use = "access": rechaza ID tokens (solo el access token sirve para llamar a la API).
    // - client_id = nuestro app client: rechaza tokens emitidos para otras aplicaciones del pool.
    static OAuth2TokenValidator<Jwt> cognitoValidator(String issuer, String clientId) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
            new JwtClaimValidator<String>("token_use", "access"::equals),
            new JwtClaimValidator<String>("client_id", clientId::equals));
    }
    /** Elige el validador según el emisor declarado; la firma y el emisor se verifican después en ese validador. */
    static JwtDecoder byIssuer(String cognitoIssuer, JwtDecoder cognito, JwtDecoder entra) {
        // Mira el emisor que DICE traer el token (sin confiar todavía) y elige el validador.
        // No es un riesgo: el validador elegido verifica después la firma y el emisor de verdad.
        return token -> cognitoIssuer.equals(declaredIssuer(token)) ? cognito.decode(token) : entra.decode(token);
    }
    // Lee el claim "iss" del token sin verificar la firma, solo para decidir el camino.
    // Si el texto no es un JWT, devuelve null y el token se envía al validador de Entra, que lo rechaza (401).
    private static String declaredIssuer(String token) {
        try {
            return com.nimbusds.jwt.JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (java.text.ParseException e) {
            return null;
        }
    }
    // Conversor de Jwt a permisos ("authorities") de Spring Security.
    // Cada scope pasa a ser "SCOPE_<scope>" y cada rol "ROLE_<rol>".
    // Ejemplo: scope access_as_user + rol ADMIN -> [SCOPE_access_as_user, ROLE_ADMIN].
    // hasRole('ADMIN') en @PreAuthorize busca justamente "ROLE_ADMIN".
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





    // Cadena de filtros de seguridad: se aplica a TODAS las peticiones HTTP que llegan al BFF.
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        // cors: aplica la configuración del bean corsConfigurationSource (más abajo).
        return http.csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Las peticiones OPTIONS (preflight CORS del navegador) pasan sin token.
            .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // (Sin token, o con token inválido, la respuesta es 401 antes de llegar aquí.)
                .anyRequest().access((authentication, context) -> {
                    var rights = authentication.get().getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
                    boolean ok = rights.contains("SCOPE_access_as_user") &&
                        (rights.contains("ROLE_ADMIN") || rights.contains("ROLE_RECEPCIONISTA") || rights.contains("ROLE_HUESPED"));
                    return new org.springframework.security.authorization.AuthorizationDecision(ok);
                }))
            // Activa el modo "Resource Server": busca el header Authorization: Bearer <token>,
            // lo valida con jwtDecoder y lo convierte a permisos con el conversor definido arriba.
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .build();
    }
    // CORS: le dice al navegador qué página puede llamar al BFF desde otro origen.
    // Solo se permite FRONTEND_ORIGIN (http://localhost:4200), con los métodos y headers que usa el frontend.
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${security.frontend-origin}") String origin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // "/**" aplica esta configuración a todas las rutas.
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
