package cl.andesstay.bff;

// Cliente HTTP del BFF hacia los microservicios internos.
// Cada llamada lleva el header X-Internal-Token (no el JWT del usuario): los microservicios
// confían en el BFF, que ya validó al usuario. También traduce los errores de los microservicios.
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

// @Service: Spring crea una sola instancia y la inyecta en ApiController.
@Service
public class Downstream {

    // Un RestClient por microservicio, cada uno con su URL base.
    private final RestClient reservations;
    private final RestClient catalog;

    // Las URLs y el token llegan de application.yml (variables RESERVATIONS_URL, CATALOG_URL e INTERNAL_TOKEN).
    // En Docker Compose las URLs son http://reservations:8081 y http://catalog:8082 (red interna).

    public Downstream(RestClient.Builder builder, @Value("${services.reservations}") String reservationUrl,
                      @Value("${services.catalog}") String catalogUrl, @Value("${internal.token}") String token) {
        // Sin token interno el BFF no arranca: los microservicios rechazarían todas las llamadas.
        if (token == null || token.isBlank()) throw new IllegalStateException("INTERNAL_TOKEN es obligatorio");
        var requestFactory = new JdkClientHttpRequestFactory(); // PATCH compatible con Java 17.
        // defaultHeader agrega X-Internal-Token a TODAS las peticiones de cada cliente.
        reservations = builder.clone().requestFactory(requestFactory).baseUrl(reservationUrl).defaultHeader("X-Internal-Token", token).build();
        catalog = builder.clone().requestFactory(requestFactory).baseUrl(catalogUrl).defaultHeader("X-Internal-Token", token).build();
    }

    // Envoltorio común para todas las llamadas:
    // - Si el microservicio responde con error (400, 404, 409...), el BFF devuelve el mismo código al frontend.
    // - Si el microservicio no responde (caído o inalcanzable), el BFF responde 503 Service Unavailable.
    private <T> T call(java.util.function.Supplier<T> work) {
        try { return work.get(); }
        catch (RestClientResponseException ex) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(ex.getStatusCode().value()), "Servicio interno: " + ex.getStatusText());
        } catch (ResourceAccessException ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Servicio interno no disponible"); }
    }

    // GET /api/reservations en reservations. Si guestId no es null, agrega ?guestId=... para filtrar
    // las reservas de un solo huésped.
    public List<Models.Reservation> reservations(String guestId) {
        return call(() -> {
            Models.Reservation[] body = reservations.get().uri(uri -> {
                var b = uri.path("/api/reservations");
                if (guestId != null) b.queryParam("guestId", guestId);
                return b.build();
            }).retrieve().body(Models.Reservation[].class);
            // Si el cuerpo viene vacío se devuelve una lista vacía en vez de null.
            return body == null ? List.of() : Arrays.asList(body);
        });
    }

    // Operaciones sobre reservas: cada método llama a la ruta equivalente de ms-andesstay-reservations.
    public Models.Reservation reservation(String id) { return call(() -> reservations.get().uri("/api/reservations/{id}", id).retrieve().body(Models.Reservation.class)); }
    public Models.Reservation create(Object data) { return call(() -> reservations.post().uri("/api/reservations").body(data).retrieve().body(Models.Reservation.class)); }
    public Models.Reservation update(String id, Models.UpdateReservation data) { return call(() -> reservations.put().uri("/api/reservations/{id}", id).body(data).retrieve().body(Models.Reservation.class)); }
    public void delete(String id) { call(() -> reservations.delete().uri("/api/reservations/{id}", id).retrieve().toBodilessEntity()); }

    // Cambio de estado: PATCH /api/reservations/{id}/status.
    public Models.Reservation transition(String id, Models.StatusUpdate data) { return call(() -> reservations.patch().uri("/api/reservations/{id}/status", id).body(data).retrieve().body(Models.Reservation.class)); }

    // Operaciones sobre el catálogo: cada método llama a la ruta equivalente de ms-andesstay-catalog.
    public List<Models.Unit> units() { return call(() -> { Models.Unit[] body = catalog.get().uri("/api/catalog/units").retrieve().body(Models.Unit[].class); return body == null ? List.of() : Arrays.asList(body); }); }

    // Unidades disponibles entre dos fechas (?from=...&to=...).
    public List<Models.Unit> available(LocalDate from, LocalDate to) {
        return call(() -> { Models.Unit[] body = catalog.get().uri(uri -> uri.path("/api/catalog/available")
            .queryParam("from", from).queryParam("to", to).build()).retrieve().body(Models.Unit[].class);
            return body == null ? List.of() : Arrays.asList(body); });
    }

    public Models.Unit unit(String id) { return call(() -> catalog.get().uri("/api/catalog/units/{id}", id).retrieve().body(Models.Unit.class)); }
    public Models.Unit createUnit(Models.UnitRequest data) { return call(() -> catalog.post().uri("/api/catalog/units").body(data).retrieve().body(Models.Unit.class)); }
    public Models.Unit updateUnit(String id, Models.UnitRequest data) { return call(() -> catalog.put().uri("/api/catalog/units/{id}", id).body(data).retrieve().body(Models.Unit.class)); }
    public void deleteUnit(String id) { call(() -> catalog.delete().uri("/api/catalog/units/{id}", id).retrieve().toBodilessEntity()); }

    public List<Map<String, Object>> rabbitResources(String resource) {
        return call(() -> {
            List<Map<String, Object>> body = reservations.get().uri("/api/rabbit/{resource}", resource)
                .retrieve().body(new org.springframework.core.ParameterizedTypeReference<>() { });
            return body == null ? List.of() : body;
        });
    }
    public void createRabbitQueue(Models.RabbitQueueRequest data) {
        call(() -> reservations.post().uri("/api/rabbit/queues").body(data).retrieve().toBodilessEntity());
    }
    public void deleteRabbitQueue(String name) {
        call(() -> reservations.delete().uri("/api/rabbit/queues/{name}", name).retrieve().toBodilessEntity());
    }
    public void createRabbitExchange(Models.RabbitExchangeRequest data) {
        call(() -> reservations.post().uri("/api/rabbit/exchanges").body(data).retrieve().toBodilessEntity());
    }
    public void deleteRabbitExchange(String name) {
        call(() -> reservations.delete().uri("/api/rabbit/exchanges/{name}", name).retrieve().toBodilessEntity());
    }
    public void createRabbitBinding(Models.RabbitBindingRequest data) {
        call(() -> reservations.post().uri("/api/rabbit/bindings").body(data).retrieve().toBodilessEntity());
    }
    public void deleteRabbitBinding(String queue, String exchange, String routingKey) {
        call(() -> reservations.delete().uri(uri -> uri.path("/api/rabbit/bindings")
            .queryParam("queue", queue).queryParam("exchange", exchange).queryParam("routingKey", routingKey).build())
            .retrieve().toBodilessEntity());
    }
    // Disponibilidad de una unidad: responde {"available": true} o {"available": false}.
    public Map<String, Boolean> availability(String id, LocalDate from, LocalDate to) {
        return call(() -> catalog.get().uri(uri -> uri.path("/api/catalog/units/{id}/availability")
            .queryParam("from", from).queryParam("to", to).build(id)).retrieve().body(new org.springframework.core.ParameterizedTypeReference<>() { }));
    }
}
