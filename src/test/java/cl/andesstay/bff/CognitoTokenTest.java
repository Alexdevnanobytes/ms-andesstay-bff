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
    static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_TEST";

    private Jwt.Builder cognitoToken() {
        return Jwt.withTokenValue("t").header("alg", "RS256").issuer(ISSUER).subject("cognito-sub-1")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
            .claim("token_use", "access").claim("client_id", "app-client").claim("username", "huesped")
            .claim("scope", "openid andesstay-api/access_as_user").claim("cognito:groups", List.of("HUESPED", "OTRO"));
    }

    private List<String> authorities(Jwt jwt) {
        return new SecurityConfig().jwtConverter().convert(jwt).getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).toList();
    }

    @Test void cognitoTokenMapsToSameAuthoritiesAsEntra() {
        assertThat(authorities(cognitoToken().build()))
            .contains("SCOPE_access_as_user", "ROLE_HUESPED").doesNotContain("ROLE_OTRO");
    }

    @Test void entraTokenKeepsItsMapping() {
        Jwt entra = Jwt.withTokenValue("t").header("alg", "RS256").claim("oid", "entra-oid")
            .claim("name", "Admin").claim("scp", "access_as_user").claim("roles", List.of("ADMIN")).build();
        assertThat(authorities(entra)).containsExactlyInAnyOrder("SCOPE_access_as_user", "ROLE_ADMIN");
        assertThat(UserClaims.id(entra)).isEqualTo("entra-oid");
        assertThat(UserClaims.name(entra)).isEqualTo("Admin");
    }

    @Test void cognitoIdentityComesFromSubAndUsername() {
        Jwt jwt = cognitoToken().build();
        assertThat(UserClaims.id(jwt)).isEqualTo("cognito-sub-1");
        assertThat(UserClaims.name(jwt)).isEqualTo("huesped");
        assertThat(UserClaims.roles(jwt)).containsExactly("HUESPED");
    }

    @Test void cognitoValidatorAcceptsOnlyAccessTokensForOurClient() {
        var validator = SecurityConfig.cognitoValidator(ISSUER, "app-client");
        assertThat(validator.validate(cognitoToken().build()).hasErrors()).isFalse();
        assertThat(validator.validate(cognitoToken().claim("token_use", "id").build()).hasErrors()).isTrue();
        assertThat(validator.validate(cognitoToken().claim("client_id", "otro-client").build()).hasErrors()).isTrue();
        assertThat(validator.validate(cognitoToken().issuer("https://otro.issuer").build()).hasErrors()).isTrue();
    }

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
