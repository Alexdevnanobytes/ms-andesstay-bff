package cl.andesstay.bff;

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

@Service
public class Downstream {
    private final RestClient reservations;
    private final RestClient catalog;
    public Downstream(RestClient.Builder builder, @Value("${services.reservations}") String reservationUrl,
                      @Value("${services.catalog}") String catalogUrl, @Value("${internal.token}") String token) {
        if (token == null || token.isBlank()) throw new IllegalStateException("INTERNAL_TOKEN es obligatorio");
        var requestFactory = new JdkClientHttpRequestFactory(); // PATCH compatible con Java 17.
        reservations = builder.clone().requestFactory(requestFactory).baseUrl(reservationUrl).defaultHeader("X-Internal-Token", token).build();
        catalog = builder.clone().requestFactory(requestFactory).baseUrl(catalogUrl).defaultHeader("X-Internal-Token", token).build();
    }
    private <T> T call(java.util.function.Supplier<T> work) {
        try { return work.get(); }
        catch (RestClientResponseException ex) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(ex.getStatusCode().value()), "Servicio interno: " + ex.getStatusText());
        } catch (ResourceAccessException ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Servicio interno no disponible"); }
    }
    public List<Models.Reservation> reservations(String guestId) {
        return call(() -> {
            Models.Reservation[] body = reservations.get().uri(uri -> {
                var b = uri.path("/api/reservations");
                if (guestId != null) b.queryParam("guestId", guestId);
                return b.build();
            }).retrieve().body(Models.Reservation[].class);
            return body == null ? List.of() : Arrays.asList(body);
        });
    }
    public Models.Reservation reservation(String id) { return call(() -> reservations.get().uri("/api/reservations/{id}", id).retrieve().body(Models.Reservation.class)); }
    public Models.Reservation create(Object data) { return call(() -> reservations.post().uri("/api/reservations").body(data).retrieve().body(Models.Reservation.class)); }
    public Models.Reservation update(String id, Models.UpdateReservation data) { return call(() -> reservations.put().uri("/api/reservations/{id}", id).body(data).retrieve().body(Models.Reservation.class)); }
    public void delete(String id) { call(() -> reservations.delete().uri("/api/reservations/{id}", id).retrieve().toBodilessEntity()); }
    public Models.Reservation transition(String id, Models.StatusUpdate data) { return call(() -> reservations.patch().uri("/api/reservations/{id}/status", id).body(data).retrieve().body(Models.Reservation.class)); }
    public List<Models.Unit> units() { return call(() -> { Models.Unit[] body = catalog.get().uri("/api/catalog/units").retrieve().body(Models.Unit[].class); return body == null ? List.of() : Arrays.asList(body); }); }
    public List<Models.Unit> available(LocalDate from, LocalDate to) {
        return call(() -> { Models.Unit[] body = catalog.get().uri(uri -> uri.path("/api/catalog/available")
            .queryParam("from", from).queryParam("to", to).build()).retrieve().body(Models.Unit[].class);
            return body == null ? List.of() : Arrays.asList(body); });
    }
    public Models.Unit unit(String id) { return call(() -> catalog.get().uri("/api/catalog/units/{id}", id).retrieve().body(Models.Unit.class)); }
    public Models.Unit createUnit(Models.UnitRequest data) { return call(() -> catalog.post().uri("/api/catalog/units").body(data).retrieve().body(Models.Unit.class)); }
    public Models.Unit updateUnit(String id, Models.UnitRequest data) { return call(() -> catalog.put().uri("/api/catalog/units/{id}", id).body(data).retrieve().body(Models.Unit.class)); }
    public void deleteUnit(String id) { call(() -> catalog.delete().uri("/api/catalog/units/{id}", id).retrieve().toBodilessEntity()); }
    public Map<String, Boolean> availability(String id, LocalDate from, LocalDate to) {
        return call(() -> catalog.get().uri(uri -> uri.path("/api/catalog/units/{id}/availability")
            .queryParam("from", from).queryParam("to", to).build(id)).retrieve().body(new org.springframework.core.ParameterizedTypeReference<>() { }));
    }
}
