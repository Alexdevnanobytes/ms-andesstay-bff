// Pruebas unitarias del soporte de Cognito (sin levantar Spring): conversión de claims a permisos,
// identidad del usuario, reglas del validador y elección del validador según el emisor.
package cl.andesstay.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class CognitoTokenTest {
    // Emisor ficticio de un User Pool de prueba.
    static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_TEST";

    // Construye un access token de Cognito de ejemplo, con sus claims típicos.
    // Incluye un grupo "OTRO" que no es un rol de AndesStay, para comprobar que se ignora.
    private Jwt.Builder cognitoToken() {
        return Jwt.withTokenValue("t").header("alg", "RS256").issuer(ISSUER).subject("cognito-sub-1")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
            .claim("token_use", "access").claim("client_id", "app-client").claim("username", "huesped")
            .claim("scope", "openid andesstay-api/access_as_user").claim("cognito:groups", List.of("HUESPED", "OTRO"));
    }

    // Aplica el conversor real del BFF y devuelve los permisos como texto.
    private List<String> authorities(Jwt jwt) {
        return new SecurityConfig().jwtConverter().convert(jwt).getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).toList();
    }

    // Un token de Cognito produce los mismos permisos que uno de Entra (SCOPE_access_as_user y ROLE_*).
    @Test void cognitoTokenMapsToSameAuthoritiesAsEntra() {
        assertThat(authorities(cognitoToken().build()))
            .contains("SCOPE_access_as_user", "ROLE_HUESPED").doesNotContain("ROLE_OTRO");
    }

    // El token de Entra ID sigue funcionando igual que antes (oid, name, roles, scp).
    @Test void entraTokenKeepsItsMapping() {
        Jwt entra = Jwt.withTokenValue("t").header("alg", "RS256").claim("oid", "entra-oid")
            .claim("name", "Admin").claim("scp", "access_as_user").claim("roles", List.of("ADMIN")).build();
        assertThat(authorities(entra)).containsExactlyInAnyOrder("SCOPE_access_as_user", "ROLE_ADMIN");
        assertThat(UserClaims.id(entra)).isEqualTo("entra-oid");
        assertThat(UserClaims.name(entra)).isEqualTo("Admin");
    }

    // En Cognito, el id sale de "sub", el nombre de "username" y los roles de "cognito:groups".
    @Test void cognitoIdentityComesFromSubAndUsername() {
        Jwt jwt = cognitoToken().build();
        assertThat(UserClaims.id(jwt)).isEqualTo("cognito-sub-1");
        assertThat(UserClaims.name(jwt)).isEqualTo("huesped");
        assertThat(UserClaims.roles(jwt)).containsExactly("HUESPED");
    }

    // El validador de Cognito acepta solo access tokens de nuestro app client y de nuestro emisor;
    // rechaza ID tokens, tokens de otro client y tokens de otro emisor.
    @Test void cognitoValidatorAcceptsOnlyAccessTokensForOurClient() {
        var validator = SecurityConfig.cognitoValidator(ISSUER, "app-client");
        assertThat(validator.validate(cognitoToken().build()).hasErrors()).isFalse();
        assertThat(validator.validate(cognitoToken().claim("token_use", "id").build()).hasErrors()).isTrue();
        assertThat(validator.validate(cognitoToken().claim("client_id", "otro-client").build()).hasErrors()).isTrue();
        assertThat(validator.validate(cognitoToken().issuer("https://otro.issuer").build()).hasErrors()).isTrue();
    }

    // El decoder compuesto envía cada token al validador correcto según su emisor.
    // Un texto que no es JWT va al validador de Entra (que lo rechazará).
    @Test void decoderRoutesByIssuer() {
        JwtDecoder cognito = mock(JwtDecoder.class), entra = mock(JwtDecoder.class);
        JwtDecoder decoder = SecurityConfig.byIssuer(ISSUER, cognito, entra);
        String cognitoJwt = new PlainJWT(new JWTClaimsSet.Builder().issuer(ISSUER).build()).serialize();
        String entraJwt = new PlainJWT(new JWTClaimsSet.Builder().issuer("https://login.microsoftonline.com/x/v2.0").build()).serialize();

        decoder.decode(cognitoJwt);
        decoder.decode(entraJwt);
        decoder.decode("no-es-un-jwt");

        verify(cognito).decode(cognitoJwt);
        verify(entra).decode(entraJwt);
        verify(entra).decode("no-es-un-jwt");
        verifyNoMoreInteractions(cognito);
    }
}
