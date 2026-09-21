package cl.andesstay.bff;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final Downstream downstream;
    public ApiController(Downstream downstream) { this.downstream = downstream; }
    private String id(Jwt jwt) {
        String oid = jwt.getClaimAsString("oid");
        if (oid == null || oid.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Falta identificador de usuario");
        return oid;
    }
    private boolean staff(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && (roles.contains("ADMIN") || roles.contains("RECEPCIONISTA"));
    }
    private Models.Reservation allowedReservation(Jwt jwt, String reservationId) {
        Models.Reservation r = downstream.reservation(reservationId);
        if (!staff(jwt) && !id(jwt).equals(r.guestId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Reserva de otro huésped");
        return r;
    }
    @GetMapping("/me")
    public Models.Profile me(@AuthenticationPrincipal Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new Models.Profile(id(jwt), jwt.getClaimAsString("name"), roles == null ? List.of() : roles);
    }
    @GetMapping("/dashboard")
    public Models.Dashboard dashboard(@AuthenticationPrincipal Jwt jwt) {
        List<Models.Reservation> reservations = downstream.reservations(staff(jwt) ? null : id(jwt));
        LocalDate today = LocalDate.now();
        long occupied = reservations.stream().filter(r -> "EN_ESTADIA".equals(r.status())).count();
        long arrivals = reservations.stream().filter(r -> today.equals(r.checkIn()) &&
            ("CONFIRMADA".equals(r.status()) || "CHECKIN_PENDIENTE".equals(r.status()))).count();
        long departures = reservations.stream().filter(r -> today.equals(r.checkOut()) && "EN_ESTADIA".equals(r.status())).count();
        return new Models.Dashboard(reservations.size(), occupied, arrivals, departures, reservations);
    }
    @GetMapping("/reservations")
    public List<Models.Reservation> reservations(@AuthenticationPrincipal Jwt jwt) {
        return downstream.reservations(staff(jwt) ? null : id(jwt));
    }
    @GetMapping("/reservations/{id}")
    public Models.Reservation reservation(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return allowedReservation(jwt, id); }
    @PostMapping("/reservations") @ResponseStatus(HttpStatus.CREATED)
    public Models.Reservation create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Models.CreateReservation data) {
        String guestId = staff(jwt) && data.guestId() != null && !data.guestId().isBlank() ? data.guestId() : id(jwt);
        return downstream.create(Map.of("guestId", guestId, "unitId", data.unitId(),
            "checkIn", data.checkIn().toString(), "checkOut", data.checkOut().toString()));
    }
    @PutMapping("/reservations/{id}")
    public Models.Reservation update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody Models.UpdateReservation data) {
        allowedReservation(jwt, id); return downstream.update(id, data);
    }
    @DeleteMapping("/reservations/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        allowedReservation(jwt, id); downstream.delete(id);
    }
    @PatchMapping("/reservations/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCIONISTA')")
    public Models.Reservation changeStatus(@PathVariable String id, @Valid @RequestBody Models.StatusUpdate data) {
        return downstream.transition(id, data);
    }
    @GetMapping("/catalog/available")
    public List<Models.Unit> available(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return downstream.available(from, to);
    }
    @GetMapping("/catalog/units") @PreAuthorize("hasAnyRole('ADMIN','RECEPCIONISTA')")
    public List<Models.Unit> units() { return downstream.units(); }
    @GetMapping("/catalog/units/{id}") @PreAuthorize("hasAnyRole('ADMIN','RECEPCIONISTA')")
    public Models.Unit unit(@PathVariable String id) { return downstream.unit(id); }
    @GetMapping("/catalog/units/{id}/availability") @PreAuthorize("hasAnyRole('ADMIN','RECEPCIONISTA')")
    public Map<String, Boolean> availability(@PathVariable String id, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return downstream.availability(id, from, to);
    }
    @PostMapping("/catalog/units") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')")
    public Models.Unit createUnit(@Valid @RequestBody Models.UnitRequest data) { return downstream.createUnit(data); }
    @PutMapping("/catalog/units/{id}") @PreAuthorize("hasRole('ADMIN')")
    public Models.Unit updateUnit(@PathVariable String id, @Valid @RequestBody Models.UnitRequest data) { return downstream.updateUnit(id, data); }
    @DeleteMapping("/catalog/units/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')")
    public void deleteUnit(@PathVariable String id) { downstream.deleteUnit(id); }
}
