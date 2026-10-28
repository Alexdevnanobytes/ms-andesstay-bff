package cl.andesstay.bff;

// Controlador REST del BFF: define todas las rutas /api/... que consume el frontend.
// Antes de llegar aquí, SecurityConfig ya validó el token (401 si es inválido) y la regla general
// (scope + algún rol; 403 si falta). Aquí se aplican las reglas más finas: rol por endpoint
// con @PreAuthorize y "dueño de la reserva" para los huéspedes.
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

// @RestController: cada método devuelve datos que Spring convierte a JSON.
// @RequestMapping("/api"): prefijo común de todas las rutas de esta clase.
@RestController
@RequestMapping("/api")
public class ApiController {
    // Cliente hacia los microservicios (reservations y catalog). Spring lo inyecta por el constructor.
    private final Downstream downstream;
    public ApiController(Downstream downstream) { this.downstream = downstream; }
    // Id del usuario autenticado (oid en Entra, sub en Cognito). Si el token no lo trae, 403.
    private String id(Jwt jwt) {
        String id = UserClaims.id(jwt);
        if (id == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Falta identificador de usuario");
        return id;
    }
    // ¿Es personal del hotel? ADMIN y RECEPCIONISTA ven y gestionan todas las reservas;
    // un HUESPED solo las suyas.
    private boolean staff(Jwt jwt) {
        List<String> roles = UserClaims.roles(jwt);
        return roles.contains("ADMIN") || roles.contains("RECEPCIONISTA");
    }
    // Trae una reserva y verifica que el usuario pueda verla o modificarla.
    // El personal puede con cualquiera; un huésped solo si guestId es su propio id.
    // Si no, 403 "Reserva de otro huésped". Lo usan GET, PUT y DELETE de /reservations/{id}.
    private Models.Reservation allowedReservation(Jwt jwt, String reservationId) {
        Models.Reservation r = downstream.reservation(reservationId);
        if (!staff(jwt) && !id(jwt).equals(r.guestId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Reserva de otro huésped");
        return r;
    }
    // GET /api/me: perfil del usuario sacado directamente del token (no llama a ningún microservicio).
    // @AuthenticationPrincipal entrega el Jwt ya validado.
    @GetMapping("/me")
    public Models.Profile me(@AuthenticationPrincipal Jwt jwt) {
        return new Models.Profile(id(jwt), UserClaims.name(jwt), UserClaims.roles(jwt));
    }
    // GET /api/dashboard: resumen para la pantalla de inicio. El personal ve todas las reservas
    // (guestId null); el huésped solo las suyas. Los contadores los calcula el BFF.
    @GetMapping("/dashboard")
    public Models.Dashboard dashboard(@AuthenticationPrincipal Jwt jwt) {
        List<Models.Reservation> reservations = downstream.reservations(staff(jwt) ? null : id(jwt));
        LocalDate today = LocalDate.now();
        // Ocupadas: reservas con huésped alojado ahora mismo.
        long occupied = reservations.stream().filter(r -> "EN_ESTADIA".equals(r.status())).count();
        // Llegadas de hoy: reservas confirmadas (o con check-in pendiente) cuya entrada es hoy.
        long arrivals = reservations.stream().filter(r -> today.equals(r.checkIn()) &&
            ("CONFIRMADA".equals(r.status()) || "CHECKIN_PENDIENTE".equals(r.status()))).count();
        // Salidas de hoy: huéspedes alojados cuya salida es hoy.
        long departures = reservations.stream().filter(r -> today.equals(r.checkOut()) && "EN_ESTADIA".equals(r.status())).count();
        return new Models.Dashboard(reservations.size(), occupied, arrivals, departures, reservations);
    }
    // GET /api/reservations: todas (personal) o solo las propias (huésped).
    @GetMapping("/reservations")
    public List<Models.Reservation> reservations(@AuthenticationPrincipal Jwt jwt) {
        return downstream.reservations(staff(jwt) ? null : id(jwt));
    }
    // GET /api/reservations/{id}: una reserva, respetando la regla de pertenencia.
    @GetMapping("/reservations/{id}")
    public Models.Reservation reservation(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return allowedReservation(jwt, id); }
    // POST /api/reservations: crea una reserva y responde 201 Created.
    // @Valid revisa las anotaciones de CreateReservation (400 si faltan datos).
    @PostMapping("/reservations") @ResponseStatus(HttpStatus.CREATED)
    public Models.Reservation create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Models.CreateReservation data) {
        // Solo el personal puede crear a nombre de otro huésped; en cualquier otro caso
        // la reserva queda a nombre de quien la registra.
        String guestId = staff(jwt) && data.guestId() != null && !data.guestId().isBlank() ? data.guestId() : id(jwt);
        // Se arma el cuerpo para ms-andesstay-reservations con el guestId ya decidido por el BFF.
        return downstream.create(Map.of("guestId", guestId, "unitId", data.unitId(),
            "checkIn", data.checkIn().toString(), "checkOut", data.checkOut().toString()));
    }
    // PUT /api/reservations/{id}: edita unidad y fechas. Primero verifica pertenencia;
    // la regla "solo en estado CREADA" la aplica reservations (409 si no).
    @PutMapping("/reservations/{id}")
    public Models.Reservation update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody Models.UpdateReservation data) {
        allowedReservation(jwt, id); return downstream.update(id, data);
    }
    // DELETE /api/reservations/{id}: elimina (204 No Content). reservations solo permite
    // borrar reservas CREADA o CANCELADA (409 en otro caso).
    @DeleteMapping("/reservations/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        allowedReservation(jwt, id); downstream.delete(id);
    }
    // PATCH /api/reservations/{id}/status: cambia el estado (confirmar, check-in, check-out, cancelar).
    // Solo ADMIN o RECEPCIONISTA; un huésped recibe 403. Las transiciones válidas las decide reservations.
    @PatchMapping("/reservations/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCIONISTA')")
    public Models.Reservation changeStatus(@PathVariable String id, @Valid @RequestBody Models.StatusUpdate data) {
        return downstream.transition(id, data);
    }
    // GET /api/catalog/available?from=&to=: unidades libres en ese período. Cualquier rol
    // (el huésped lo necesita para elegir unidad al reservar).
    @GetMapping("/catalog/available")
    public List<Models.Unit> available(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return downstream.available(from, to);
    }
    // GET /api/catalog/units: lista de unidades. Solo personal (ADMIN o RECEPCIONISTA).
    @GetMapping("/catalog/units") @PreAuthorize("hasAnyRole('ADMIN','RECEPCIONISTA')")
    public List<Models.Unit> units() { return downstream.units(); }
    // GET /api/catalog/units/{id}: una unidad. Solo personal.
    @GetMapping("/catalog/units/{id}") @PreAuthorize("hasAnyRole('ADMIN','RECEPCIONISTA')")
    public Models.Unit unit(@PathVariable String id) { return downstream.unit(id); }
    // GET /api/catalog/units/{id}/availability: ¿está libre esa unidad en esas fechas? Solo personal.
    @GetMapping("/catalog/units/{id}/availability") @PreAuthorize("hasAnyRole('ADMIN','RECEPCIONISTA')")
    public Map<String, Boolean> availability(@PathVariable String id, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return downstream.availability(id, from, to);
    }
    // POST /api/catalog/units: crea una unidad (201). Solo ADMIN.
    @PostMapping("/catalog/units") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')")
    public Models.Unit createUnit(@Valid @RequestBody Models.UnitRequest data) { return downstream.createUnit(data); }
    // PUT /api/catalog/units/{id}: edita una unidad. Solo ADMIN.
    @PutMapping("/catalog/units/{id}") @PreAuthorize("hasRole('ADMIN')")
    public Models.Unit updateUnit(@PathVariable String id, @Valid @RequestBody Models.UnitRequest data) { return downstream.updateUnit(id, data); }
    // DELETE /api/catalog/units/{id}: desactiva una unidad (204). Solo ADMIN.
    // catalog responde 409 si la unidad tiene reservas activas.
    @DeleteMapping("/catalog/units/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')")
    public void deleteUnit(@PathVariable String id) { downstream.deleteUnit(id); }

    // RabbitMQ admin endpoints are available only to ADMIN; the downstream service also
    // requires X-Internal-Token and is not published outside the Docker network.
    @GetMapping("/rabbit/queues") @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> rabbitQueues() { return downstream.rabbitResources("queues"); }
    @PostMapping("/rabbit/queues") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')")
    public void createRabbitQueue(@Valid @RequestBody Models.RabbitQueueRequest data) { downstream.createRabbitQueue(data); }
    @DeleteMapping("/rabbit/queues/{name}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')")
    public void deleteRabbitQueue(@PathVariable String name) { downstream.deleteRabbitQueue(name); }

    @GetMapping("/rabbit/exchanges") @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> rabbitExchanges() { return downstream.rabbitResources("exchanges"); }
    @PostMapping("/rabbit/exchanges") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')")
    public void createRabbitExchange(@Valid @RequestBody Models.RabbitExchangeRequest data) { downstream.createRabbitExchange(data); }
    @DeleteMapping("/rabbit/exchanges/{name}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')")
    public void deleteRabbitExchange(@PathVariable String name) { downstream.deleteRabbitExchange(name); }

    @GetMapping("/rabbit/bindings") @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> rabbitBindings() { return downstream.rabbitResources("bindings"); }
    @PostMapping("/rabbit/bindings") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')")
    public void createRabbitBinding(@Valid @RequestBody Models.RabbitBindingRequest data) { downstream.createRabbitBinding(data); }
    @DeleteMapping("/rabbit/bindings") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')")
    public void deleteRabbitBinding(@RequestParam String queue, @RequestParam String exchange, @RequestParam String routingKey) {
        downstream.deleteRabbitBinding(queue, exchange, routingKey);
    }
}
