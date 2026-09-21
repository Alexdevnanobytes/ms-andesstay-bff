package cl.andesstay.bff;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
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

@SpringBootTest(properties = {"internal.token=test-secret", "security.issuer=https://example.test/issuer",
    "security.audience=api-client", "services.reservations=http://localhost:8081", "services.catalog=http://localhost:8082"})
@AutoConfigureMockMvc
class AuthorizationTest {
    @Autowired MockMvc mvc;
    @MockBean JwtDecoder decoder;
    @MockBean Downstream downstream;
    private RequestPostProcessor actor(String role) {
        return jwt().jwt(token -> token.subject("user-1").claim("oid", "user-1")
            .claim("roles", List.of(role)).claim("scp", "access_as_user"))
            .authorities(new SimpleGrantedAuthority("ROLE_" + role), new SimpleGrantedAuthority("SCOPE_access_as_user"));
    }
    @Test void withoutTokenIsRejected() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }
    @Test void invalidTokenIsRejected() throws Exception {
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("invalid"));
        mvc.perform(get("/api/me").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
    }
    @Test void guestCannotManageCatalogButCanSeeAvailability() throws Exception {
        mvc.perform(post("/api/catalog/units").with(actor("HUESPED"))
            .contentType("application/json").content("{\"code\":\"C1\",\"type\":\"CABANA\",\"description\":\"Nueva\",\"nightlyRate\":30000}"))
            .andExpect(status().isForbidden());
        when(downstream.available(java.time.LocalDate.parse("2030-01-01"), java.time.LocalDate.parse("2030-01-03"))).thenReturn(List.of());
        mvc.perform(get("/api/catalog/available?from=2030-01-01&to=2030-01-03").with(actor("HUESPED")))
            .andExpect(status().isOk());
    }
    @Test void guestCannotReadAnotherGuestsReservation() throws Exception {
        when(downstream.reservation("r-2")).thenReturn(new Models.Reservation("r-2", "other-user", "u-1",
            java.time.LocalDate.parse("2030-01-01"), java.time.LocalDate.parse("2030-01-03"), "CREADA", java.time.Instant.now()));
        mvc.perform(get("/api/reservations/r-2").with(actor("HUESPED"))).andExpect(status().isForbidden());
    }
    @Test void adminCanCreateUnit() throws Exception {
        when(downstream.createUnit(any())).thenReturn(new Models.Unit("u-1", "C1", "CABANA", "Nueva",
            new java.math.BigDecimal("30000"), true));
        mvc.perform(post("/api/catalog/units").with(actor("ADMIN")).contentType("application/json")
            .content("{\"code\":\"C1\",\"type\":\"CABANA\",\"description\":\"Nueva\",\"nightlyRate\":30000}"))
            .andExpect(status().isCreated());
        verify(downstream).createUnit(any());
    }
}
