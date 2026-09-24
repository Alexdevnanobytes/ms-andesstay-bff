package cl.andesstay.bff;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Lee identidad y roles desde tokens de Microsoft Entra ID o de Amazon Cognito.
 * Entra: oid, name, roles. Cognito (access token): sub, username, cognito:groups.
 */
final class UserClaims {
    static final List<String> ROLES = List.of("ADMIN", "RECEPCIONISTA", "HUESPED");

    private UserClaims() { }

    static boolean isCognito(Jwt jwt) {
        return "access".equals(jwt.getClaimAsString("token_use"));
    }

    static String id(Jwt jwt) {
        String id = isCognito(jwt) ? jwt.getSubject() : jwt.getClaimAsString("oid");
        return id == null || id.isBlank() ? null : id;
    }

    static String name(Jwt jwt) {
        return jwt.getClaimAsString(isCognito(jwt) ? "username" : "name");
    }

    static List<String> roles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(isCognito(jwt) ? "cognito:groups" : "roles");
        return roles == null ? List.of() : roles.stream().filter(ROLES::contains).toList();
    }

    /** Entra entrega "access_as_user"; Cognito, "andesstay-api/access_as_user". */
    static List<String> scopes(Jwt jwt) {
        String scopes = jwt.getClaimAsString(isCognito(jwt) ? "scope" : "scp");
        if (scopes == null) return List.of();
        return java.util.Arrays.stream(scopes.split("\\s+")).filter(s -> !s.isBlank())
            .map(s -> s.substring(s.lastIndexOf('/') + 1)).toList();
    }
}
