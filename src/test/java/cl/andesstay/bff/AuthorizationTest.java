package cl.andesstay.bff;

// Pruebas de integración de la seguridad del BFF. Levantan la aplicación completa en memoria
// (@SpringBootTest) y le hacen peticiones HTTP simuladas con MockMvc, sin red ni microservicios reales.
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Propiedades de prueba: reemplazan las variables de entorno reales (tenant, token interno, URLs).
@SpringBootTest(properties = {"internal.token=test-secret", "security.issuer=https://example.test/issuer",
    "security.audience=api-client", "services.reservations=http://localhost:8081", "services.catalog=http://localhost:8082"})
@AutoConfigureMockMvc
class AuthorizationTest {
    // MockMvc permite enviar peticiones al BFF como si vinieran del navegador.
    @Autowired MockMvc mvc;
    // @MockBean reemplaza beans reales por "dobles" controlados por la prueba:
    // - JwtDecoder: así no se contacta a Microsoft ni a Cognito.
    // - Downstream: así no se llama a los microservicios; cada prueba define qué responden.
    @MockBean JwtDecoder decoder;
    @MockBean Downstream downstream;
    // Simula un usuario de Entra ID con un rol: claims oid, roles y scp, y sus permisos ya convertidos.
    private RequestPostProcessor actor(String role) {
        return jwt().jwt(token -> token.subject("user-1").claim("oid", "user-1")
            .claim("roles", List.of(role)).claim("scp", "access_as_user"))
            .authorities(new SimpleGrantedAuthority("ROLE_" + role), new SimpleGrantedAuthority("SCOPE_access_as_user"));
    }
    // Simula un usuario de Cognito: claims sub, token_use, username, cognito:groups y scope.
    private RequestPostProcessor cognitoActor(String sub, String group) {
        return jwt().jwt(token -> token.subject(sub).claim("token_use", "access").claim("username", "user-" + sub)
            .claim("cognito:groups", List.of(group)).claim("scope", "andesstay-api/access_as_user"))
            .authorities(new SimpleGrantedAuthority("ROLE_" + group), new SimpleGrantedAuthority("SCOPE_access_as_user"));
    }
    // Un usuario de Cognito obtiene su perfil: el id sale de "sub", el nombre de "username" y el rol del grupo.
    @Test void cognitoUserProfileUsesSubUsernameAndGroups() throws Exception {
        mvc.perform(get("/api/me").with(cognitoActor("c-1", "RECEPCIONISTA"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("c-1")).andExpect(jsonPath("$.name").value("user-c-1"))
            .andExpect(jsonPath("$.roles[0]").value("RECEPCIONISTA"));
    }
    // Regla de pertenencia con Cognito: el huésped dueño ve su reserva (200); otro huésped recibe 403.
    @Test void cognitoGuestOnlySeesOwnReservation() throws Exception {
        when(downstream.reservation("r-3")).thenReturn(new Models.Reservation("r-3", "c-guest", "u-1",
            java.time.LocalDate.parse("2030-01-01"), java.time.LocalDate.parse("2030-01-03"), "CREADA", java.time.Instant.now()));
        mvc.perform(get("/api/reservations/r-3").with(cognitoActor("c-guest", "HUESPED"))).andExpect(status().isOk());
        mvc.perform(get("/api/reservations/r-3").with(cognitoActor("c-other", "HUESPED"))).andExpect(status().isForbidden());
    }
    // Prueba obligatoria 1: sin token -> 401 Unauthorized.
    @Test void withoutTokenIsRejected() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }
    // Prueba obligatoria 2: token inválido -> 401. El decoder simulado lanza BadJwtException.
    @Test void invalidTokenIsRejected() throws Exception {
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("invalid"));
        mvc.perform(get("/api/me").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
    }
    // Prueba obligatoria 4: un HUESPED no puede crear unidades (403),
    // pero sí puede consultar la disponibilidad (200).
    @Test void guestCannotManageCatalogButCanSeeAvailability() throws Exception {
        mvc.perform(post("/api/catalog/units").with(actor("HUESPED"))
            .contentType("application/json").content("{\"code\":\"C1\",\"type\":\"CABANA\",\"description\":\"Nueva\",\"nightlyRate\":30000}"))
            .andExpect(status().isForbidden());
        when(downstream.available(java.time.LocalDate.parse("2030-01-01"), java.time.LocalDate.parse("2030-01-03"))).thenReturn(List.of());
        mvc.perform(get("/api/catalog/available?from=2030-01-01&to=2030-01-03").with(actor("HUESPED")))
            .andExpect(status().isOk());
    }
    // Un huésped no puede leer la reserva de otro huésped (guestId distinto) -> 403.
    @Test void guestCannotReadAnotherGuestsReservation() throws Exception {
        when(downstream.reservation("r-2")).thenReturn(new Models.Reservation("r-2", "other-user", "u-1",
            java.time.LocalDate.parse("2030-01-01"), java.time.LocalDate.parse("2030-01-03"), "CREADA", java.time.Instant.now()));
        mvc.perform(get("/api/reservations/r-2").with(actor("HUESPED"))).andExpect(status().isForbidden());
    }
    // Prueba obligatoria 3: un ADMIN crea una unidad -> 201 Created, y el BFF llama a catalog.
    @Test void adminCanCreateUnit() throws Exception {
        when(downstream.createUnit(any())).thenReturn(new Models.Unit("u-1", "C1", "CABANA", "Nueva",
            new java.math.BigDecimal("30000"), true));
        mvc.perform(post("/api/catalog/units").with(actor("ADMIN")).contentType("application/json")
            .content("{\"code\":\"C1\",\"type\":\"CABANA\",\"description\":\"Nueva\",\"nightlyRate\":30000}"))
            .andExpect(status().isCreated());
        verify(downstream).createUnit(any());
    }

    @Test void onlyAdminCanReadRabbitManagementResources() throws Exception {
        when(downstream.rabbitResources("queues")).thenReturn(List.of());
        mvc.perform(get("/api/rabbit/queues").with(actor("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/rabbit/queues").with(actor("RECEPCIONISTA"))).andExpect(status().isForbidden());
        verify(downstream, times(1)).rabbitResources("queues");
    }

    @Test void rabbitManagementPayloadIsValidatedBeforeForwarding() throws Exception {
        mvc.perform(post("/api/rabbit/exchanges").with(actor("ADMIN")).contentType("application/json")
            .content("{\"name\":\"bad exchange\",\"type\":\"fanout\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(downstream);
    }
}
