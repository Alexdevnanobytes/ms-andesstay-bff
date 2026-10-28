package cl.andesstay.bff;

// Esta clase "traduce" los tokens de los dos proveedores de identidad a un mismo formato.
// Así el resto del BFF (reglas por rol, dueño de la reserva) no necesita saber si el usuario
// entró con Microsoft Entra ID o con Amazon Cognito.
import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Lee identidad y roles desde tokens de Microsoft Entra ID o de Amazon Cognito.
 * Entra: oid, name, roles. Cognito (access token): sub, username, cognito:groups.
 */
final class UserClaims {
    // Únicos roles de negocio válidos. Cualquier otro valor que venga en el token se ignora,
    // por ejemplo un grupo de Cognito que no pertenezca a AndesStay.
    static final List<String> ROLES = List.of("ADMIN", "RECEPCIONISTA", "HUESPED");

    // Constructor privado: es una clase de utilidades (solo métodos estáticos), nunca se instancia.
    private UserClaims() { }

    // ¿El token viene de Cognito? Los access tokens de Cognito traen el claim token_use = "access";
    // los de Entra ID no lo traen. Se llama solo después de validar la firma, así que no se puede falsificar.
    static boolean isCognito(Jwt jwt) {
        return "access".equals(jwt.getClaimAsString("token_use"));
    }

    // Identificador único del usuario. Se guarda como guestId en las reservas.
    // - Entra ID: claim "oid" (object id del usuario en el tenant).
    // - Cognito: claim "sub" (subject, id del usuario en el User Pool).
    // Devuelve null si no viene: el controlador responde 403 en ese caso.
    static String id(Jwt jwt) {
        String id = isCognito(jwt) ? jwt.getSubject() : jwt.getClaimAsString("oid");
        return id == null || id.isBlank() ? null : id;
    }

    // Nombre para mostrar: "name" en Entra ID; "username" en Cognito
    // (en un pool que usa el correo como login, username es un id interno; el frontend muestra el correo).
    static String name(Jwt jwt) {
        return jwt.getClaimAsString(isCognito(jwt) ? "username" : "name");
    }

    // Roles del usuario: claim "roles" (app roles de Entra ID) o "cognito:groups" (grupos de Cognito).
    // Se filtran para dejar solo ADMIN, RECEPCIONISTA y HUESPED. Si no hay, devuelve lista vacía.
    static List<String> roles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(isCognito(jwt) ? "cognito:groups" : "roles");
        return roles == null ? List.of() : roles.stream().filter(ROLES::contains).toList();
    }

    /** Entra entrega "access_as_user"; Cognito, "andesstay-api/access_as_user". */
    static List<String> scopes(Jwt jwt) {
        // Entra usa el claim "scp" y Cognito el claim "scope"; ambos son textos separados por espacios.
        String scopes = jwt.getClaimAsString(isCognito(jwt) ? "scope" : "scp");
        if (scopes == null) return List.of();
        // Divide los scopes por espacios y, a cada uno, le quita todo lo anterior a la última "/":
        // "andesstay-api/access_as_user" -> "access_as_user". Así ambos proveedores quedan iguales.
        return java.util.Arrays.stream(scopes.split("\\s+")).filter(s -> !s.isBlank())
            .map(s -> s.substring(s.lastIndexOf('/') + 1)).toList();
    }
}
